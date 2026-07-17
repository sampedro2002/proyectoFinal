import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import api from '../api/client.js';

function Stat({ value, label }) {
  return (
    <div className="card stat">
      <div className="value">{value}</div>
      <div className="label">{label}</div>
    </div>
  );
}

export default function Dashboard() {
  const [stats, setStats] = useState(null);
  const [trend, setTrend] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  async function load() {
    setLoading(true);
    setError('');
    try {
      const statsRes = await api.get('/reports/dashboard');
      setStats(statsRes.data);
      const to = new Date();
      const from = new Date();
      from.setDate(to.getDate() - 6);
      const fmt = (d) => d.toISOString().slice(0, 10);
      try {
        const trendRes = await api.get('/reports/trend', { params: { from: fmt(from), to: fmt(to) } });
        setTrend(trendRes.data);
      } catch (_) {
        setTrend([]); // la tendencia es secundaria: si falla, no bloquea el panel
      }
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudieron cargar las estadísticas');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  if (error) return (
    <div className="card" style={{ textAlign: 'center' }}>
      <p className="error-text">{error}</p>
      <button onClick={load}>Reintentar</button>
    </div>
  );
  if (loading || !stats) return <p>Cargando…</p>;

  return (
    <div className="grid" style={{ gap: 24 }}>
      <h2 style={{ margin: 0 }}>Estadísticas de hoy</h2>
      <div className="grid cols-3">
        <Stat value={stats.totalConsumptions} label="Consumos de hoy" />
        <Stat value={stats.almuerzoCount} label="Almuerzos" />
        <Stat value={stats.meriendaCount} label="Meriendas" />
      </div>
      <div className="grid cols-4">
        <Stat value={stats.expectedEmployees} label="Empleados esperados" />
        <Stat value={stats.employeesConsumed} label="Empleados que consumieron" />
        <Stat value={stats.employeesPending} label="Empleados pendientes" />
        <Stat value={`${stats.consumptionPercentage ?? 0}%`} label="Porcentaje de consumo" />
      </div>
      <div className="grid cols-2">
        <Stat value={stats.failedNotFound} label="Huellas no reconocidas" />
        <Stat value={stats.failedOutOfSchedule} label="Intentos fuera de horario" />
      </div>

      <div className="card">
        <h3 style={{ marginTop: 0 }}>Tendencia de consumo (7 días)</h3>
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={trend}>
            <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
            <XAxis dataKey="date" stroke="#94a3b8" />
            <YAxis stroke="#94a3b8" />
            <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid #334155' }} />
            <Line type="monotone" dataKey="records" stroke="#4ade80" strokeWidth={2} name="Registros" />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
