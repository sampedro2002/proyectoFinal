import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';

export default function Layout() {
  const { user, logout, hasRole } = useAuth();
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <h1>🍽 EatFood</h1>
        <NavLink to="/" end>Dashboard</NavLink>
        {hasRole('ADMIN', 'SUPERVISOR') && <NavLink to="/employees">Empleados</NavLink>}
        {hasRole('ADMIN', 'SUPERVISOR') && <NavLink to="/positions">Cargos</NavLink>}
        {hasRole('ADMIN', 'SUPERVISOR') && <NavLink to="/caterings">Caterings</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/schedules">Horarios</NavLink>}
        {hasRole('ADMIN', 'SUPERVISOR') && <NavLink to="/reports">Reportes</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/audit">Auditoría</NavLink>}
        {hasRole('ADMIN') && <NavLink to="/manual-scan">Registro manual</NavLink>}
        <a href="/kiosk" target="_blank" rel="noopener noreferrer">Pantalla Catering ↗</a>
        <div className="spacer" />
        <button className="ghost" onClick={logout}>Cerrar sesión</button>
      </aside>
      <main className="content">
        <div className="topbar">
          <div />
          <div className="user">{user?.fullName} · {user?.roles?.join(', ')}</div>
        </div>
        <Outlet />
      </main>
    </div>
  );
}
