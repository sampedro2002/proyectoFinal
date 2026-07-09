import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext.jsx';
import Layout from './components/Layout.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Employees from './pages/Employees.jsx';
import Restaurants from './pages/Restaurants.jsx';
import Users from './pages/Users.jsx';
import Schedules from './pages/Schedules.jsx';
import Reports from './pages/Reports.jsx';
import Audit from './pages/Audit.jsx';
import ManualScan from './pages/ManualScan.jsx';
import ServerQr from './pages/ServerQr.jsx';
import Kiosk from './pages/Kiosk.jsx';

function Protected({ children, roles }) {
  const { user, loading, hasRole } = useAuth();
  // Mientras se resuelve el refresh silencioso inicial no se sabe aún si hay sesión
  // válida (cookie httpOnly); redirigir aquí produciría un salto a /login en cada F5.
  if (loading) return null;
  if (!user) return <Navigate to="/login" replace />;
  if (roles && !hasRole(...roles)) return <Navigate to="/" replace />;
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/kiosk" element={<Kiosk />} />

      <Route path="/" element={<Protected><Layout /></Protected>}>
        <Route index element={<Dashboard />} />
        <Route path="employees" element={<Protected roles={['ADMIN']}><Employees /></Protected>} />
        <Route path="restaurants" element={<Protected roles={['ADMIN']}><Restaurants /></Protected>} />
        <Route path="users" element={<Protected roles={['ADMIN']}><Users /></Protected>} />
        <Route path="schedules" element={<Protected roles={['ADMIN']}><Schedules /></Protected>} />
        <Route path="reports" element={<Protected roles={['ADMIN']}><Reports /></Protected>} />
        <Route path="audit" element={<Protected roles={['ADMIN']}><Audit /></Protected>} />
        <Route path="manual-scan" element={<Protected roles={['ADMIN']}><ManualScan /></Protected>} />
        <Route path="conexion" element={<Protected roles={['ADMIN']}><ServerQr /></Protected>} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
