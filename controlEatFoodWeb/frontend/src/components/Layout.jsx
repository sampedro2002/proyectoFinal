import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';

const ROLE_LABELS = { ADMIN: 'Administrador', CATERING: 'Restaurante', RECURSOS_HUMANOS: 'Recursos Humanos' };
const roleLabel = (name) => ROLE_LABELS[name] || name;

export default function Layout() {
  const { user, logout, hasRole } = useAuth();
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <img src="/logo.png" alt="Club Castillo Amaguaña" className="brand-logo" />
          <div className="brand-text">
            <span className="brand-name">Club Castillo Amaguaña</span>
            <span className="brand-tag">Control de Alimentos</span>
          </div>
        </div>
        <NavLink to="/" end>Dashboard</NavLink>
        {(hasRole('ADMIN') || hasRole('RECURSOS_HUMANOS')) && <NavLink to="/employees">Empleados</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/restaurants">Restaurantes</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/users">Usuarios</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/schedules">Horarios</NavLink>}
        {(hasRole('ADMIN') || hasRole('RECURSOS_HUMANOS')) && <NavLink to="/reports">Reportes</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/audit">Auditoría</NavLink>}
        {(hasRole('ADMIN') || hasRole('RECURSOS_HUMANOS')) && <NavLink to="/manual-scan">Registro manual</NavLink>}
        {(hasRole('ADMIN') || hasRole('RECURSOS_HUMANOS')) && <NavLink to="/edit-consumptions">Editar Consumos</NavLink>}
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
