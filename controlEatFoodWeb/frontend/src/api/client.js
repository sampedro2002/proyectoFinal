import axios from 'axios';

const api = axios.create({ baseURL: '/api' });

let onAuthFailure = () => {};
export function setAuthFailureHandler(fn) { onAuthFailure = fn; }

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) config.headers.Authorization = `Bearer ${token}`;
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
      const refreshToken = localStorage.getItem('refreshToken');
      if (!refreshToken) { onAuthFailure(); return Promise.reject(error); }
      try {
        refreshing = refreshing || axios.post('/api/auth/refresh', { refreshToken });
        const { data } = await refreshing;
        if (!data?.accessToken) { onAuthFailure(); return Promise.reject(error); }
        localStorage.setItem('accessToken', data.accessToken);
        // Sólo sobrescribir el refreshToken si el backend entregó uno nuevo;
        // si no, conservamos el anterior para no dejarlo como "undefined".
        if (data?.refreshToken) localStorage.setItem('refreshToken', data.refreshToken);
        original.headers.Authorization = `Bearer ${data.accessToken}`;
        return api(original);
      } catch (e) {
        onAuthFailure();
        return Promise.reject(e);
      } finally {
        refreshing = null;
      }
    }

    // 403 sin token en localStorage significa sesión perdida, no falta de permisos
    if (status === 403 && !localStorage.getItem('accessToken')) {
      onAuthFailure();
    }

    return Promise.reject(error);
  }
);

export default api;
