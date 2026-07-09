import axios from 'axios';

const api = axios.create({ baseURL: '/api', withCredentials: true });

// El accessToken vive solo en memoria (nunca en localStorage/sessionStorage): un XSS que
// logre ejecutar JS no puede leerlo del almacenamiento del navegador porque no queda ahí.
// Se pierde a propósito al recargar la página; AuthContext lo repone con un refresh
// silencioso contra la cookie httpOnly del refreshToken (ver /auth/refresh).
let accessTokenMemory = null;
export function getAccessToken() { return accessTokenMemory; }
export function setAccessToken(token) { accessTokenMemory = token; }

let onAuthFailure = () => {};
export function setAuthFailureHandler(fn) { onAuthFailure = fn; }

api.interceptors.request.use((config) => {
  if (accessTokenMemory) config.headers.Authorization = `Bearer ${accessTokenMemory}`;
  return config;
});

let refreshing = null;

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error?.config || {};
    const status = error.response?.status;

    // Los propios endpoints de auth (login/refresh/logout) nunca deben disparar un refresh:
    // un 401 ahí es "credenciales inválidas" o "refresh token vencido", no "access token vencido".
    const isAuthEndpoint = typeof original.url === 'string' && original.url.includes('/auth/');

    if (status === 401 && !original._retry && !isAuthEndpoint) {
      original._retry = true;
      try {
        // Sin body: el refreshToken viaja en la cookie httpOnly (Path=/api/auth) que el
        // navegador adjunta solo gracias a withCredentials. axios plano (no `api`) evita
        // reentrar en este mismo interceptor.
        refreshing = refreshing || axios.post('/api/auth/refresh', undefined, { withCredentials: true });
        const { data } = await refreshing;
        if (!data?.accessToken) { onAuthFailure(); return Promise.reject(error); }
        setAccessToken(data.accessToken);
        original.headers.Authorization = `Bearer ${data.accessToken}`;
        return api(original);
      } catch (e) {
        onAuthFailure();
        return Promise.reject(e);
      } finally {
        refreshing = null;
      }
    }

    // 403 sin token en memoria significa sesión perdida, no falta de permisos
    if (status === 403 && !accessTokenMemory) {
      onAuthFailure();
    }

    return Promise.reject(error);
  }
);

export default api;
