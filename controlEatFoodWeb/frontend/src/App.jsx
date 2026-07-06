import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext.jsx';
import Layout from './components/Layout.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Employees from './pages/Employees.jsx';
import Caterings from './pages/Caterings.jsx';
import Schedules from './pages/Schedules.jsx';
import Reports from './pages/Reports.jsx';
import Audit from './pages/Audit.jsx';
import ManualScan from './pages/ManualScan.jsx';
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
        <Route path="employees" element={<Protected roles={['ADMIN']}><Employees /></Protected>} />
        <Route path="caterings" element={<Protected roles={['ADMIN']}><Caterings /></Protected>} />
        <Route path="schedules" element={<Protected roles={['ADMIN']}><Schedules /></Protected>} />
        <Route path="reports" element={<Protected roles={['ADMIN']}><Reports /></Protected>} />
        <Route path="audit" element={<Protected roles={['ADMIN']}><Audit /></Protected>} />
        <Route path="manual-scan" element={<Protected roles={['ADMIN']}><ManualScan /></Protected>} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
