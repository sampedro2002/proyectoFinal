import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api, { setAccessToken, setAuthFailureHandler } from '../api/client.js';

const AuthContext = createContext(null);
export const useAuth = () => useContext(AuthContext);

function pickUser(data) {
  const { id, username, fullName, roles, restaurantId } = data;
  return { id, username, fullName, roles, restaurantId };
}

export function AuthProvider({ children }) {
  const navigate = useNavigate();
  const [user, setUser] = useState(null);
  // true hasta que el refresh silencioso inicial resuelve; evita que las rutas
  // protegidas redirijan a /login antes de saber si la cookie sigue siendo válida.
  const [loading, setLoading] = useState(true);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
  }, []);

  const logout = useCallback(() => {
    api.post('/auth/logout').catch(() => {});
    clearSession();
    navigate('/login');
  }, [navigate, clearSession]);

  useEffect(() => {
    setAuthFailureHandler(() => clearSession());
  }, [clearSession]);

  // Al montar, intenta renovar la sesión con el refreshToken de la cookie httpOnly.
  // Sustituye al antiguo localStorage.getItem('user'): como el token ya no persiste
  // en el navegador, la sesión "se recuerda" del lado del servidor, no del cliente.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.post('/auth/refresh');
        if (cancelled) return;
        setAccessToken(data.accessToken);
        setUser(pickUser(data));
      } catch (_) {
        if (!cancelled) clearSession();
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [clearSession]);

  async function login(username, password) {
    const { data } = await api.post('/auth/login', { username, password });
    setAccessToken(data.accessToken);
    setUser(pickUser(data));
    return data;
  }

  const hasRole = (...roles) => !!user?.roles?.some((r) => roles.includes(r));

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, hasRole }}>
      {children}
    </AuthContext.Provider>
  );
}
