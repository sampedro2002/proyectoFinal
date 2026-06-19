import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext.jsx';
import Layout from './components/Layout.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Employees from './pages/Employees.jsx';
import EmployeeFingerprints from './pages/EmployeeFingerprints.jsx';
import Positions from './pages/Positions.jsx';
import Caterings from './pages/Caterings.jsx';
import Schedules from './pages/Schedules.jsx';
import Reports from './pages/Reports.jsx';
import Audit from './pages/Audit.jsx';
import Kiosk from './pages/Kiosk.jsx';

function Protected({ children, roles }) {
  const { user, hasRole } = useAuth();
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
        <Route path="employees" element={<Protected roles={['ADMIN','SUPERVISOR']}><Employees /></Protected>} />
        <Route path="employees/:id/fingerprints" element={<Protected roles={['ADMIN']}><EmployeeFingerprints /></Protected>} />
        <Route path="positions" element={<Protected roles={['ADMIN','SUPERVISOR']}><Positions /></Protected>} />
        <Route path="caterings" element={<Protected roles={['ADMIN','SUPERVISOR']}><Caterings /></Protected>} />
        <Route path="schedules" element={<Protected roles={['ADMIN']}><Schedules /></Protected>} />
        <Route path="reports" element={<Protected roles={['ADMIN','SUPERVISOR']}><Reports /></Protected>} />
        <Route path="audit" element={<Protected roles={['ADMIN']}><Audit /></Protected>} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
