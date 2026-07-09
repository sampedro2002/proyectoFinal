import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';

const ROLE_LABELS = { ADMIN: 'Administrador', CATERING: 'Restaurante' };
const roleLabel = (name) => ROLE_LABELS[name] || name;

export default function Layout() {
  const { user, logout, hasRole } = useAuth();
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <img src="/logo.png" alt="EatFood" className="brand-logo" />
          <div className="brand-text">
            <span className="brand-name">EatFood</span>
            <span className="brand-tag">Control de Alimentos</span>
          </div>
        </div>
        <NavLink to="/" end>Dashboard</NavLink>
        {hasRole('ADMIN') && <NavLink to="/employees">Empleados</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/restaurants">Restaurantes</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/users">Usuarios</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/schedules">Horarios</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/reports">Reportes</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/audit">Auditoría</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/manual-scan">Registro manual</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/conexion">Conexión (QR)</NavLink>}
        <div className="spacer" />
        <button className="ghost" onClick={logout}>Cerrar sesión</button>
      </aside>
      <main className="content">
        <div className="topbar">
          <div />
          <div className="user">{user?.fullName} · {user?.roles?.map(roleLabel).join(', ')}</div>
        </div>
        <Outlet />
      </main>
    </div>
  );
}
