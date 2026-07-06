import { useEffect, useState } from 'react';
import api from '../api/client.js';
import EmployeeSelect from '../components/EmployeeSelect.jsx';

function today() { return new Date().toISOString().slice(0, 10); }

export default function Reports() {
  const [filters, setFilters] = useState({ from: today(), to: today(), cateringId: '', mealTypeId: '', employeeId: '' });
  const [rows, setRows] = useState([]);
  const [caterings, setCaterings] = useState([]);
  const [meals, setMeals] = useState([]);
  const [employees, setEmployees] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all([
      api.get('/caterings').then((r) => setCaterings(r.data)).catch(() => {}),
      api.get('/meal-types').then((r) => setMeals(r.data)).catch(() => {}),
      api.get('/employees', { params: { size: 2000, status: 'ACTIVE' } }).then((r) => setEmployees(r.data.content || r.data)).catch(() => {}),
    ]);
  }, []);

  function params() {
    const p = { from: filters.from, to: filters.to };
    if (filters.cateringId) p.cateringId = filters.cateringId;
    if (filters.mealTypeId) p.mealTypeId = filters.mealTypeId;
    if (filters.employeeId) p.employeeId = filters.employeeId;
    return p;
  }

  async function search() {
    setError('');
    try {
      const { data } = await api.get('/reports/consumptions', { params: params() });
      setRows(data);
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudo cargar el reporte');
    }
  }

  async function exportFile(format) {
    setError('');
    try {
      const res = await api.get('/reports/export', {
        params: { ...params(), format }, responseType: 'blob'
      });
      const url = URL.createObjectURL(res.data);
      const a = document.createElement('a');
      a.href = url;
      a.download = `consumos.${format === 'excel' ? 'xlsx' : format}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      // Retrasar el revoke para que Firefox no interrumpa la descarga.
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudo exportar el reporte');
    }
  }

  return (
    <div>
      <h2>Reportes de consumo</h2>
      {error && <p className="error-text">{error}</p>}
      <div className="card">
        <div className="row">
          <div className="field"><label>Desde</label>
            <input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} /></div>
          <div className="field"><label>Hasta</label>
            <input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} /></div>
          <div className="field"><label>Catering</label>
            <select value={filters.cateringId} onChange={(e) => setFilters({ ...filters, cateringId: e.target.value })}>
              <option value="">Todos</option>
              {caterings.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select></div>
          <div className="field"><label>Comida</label>
            <select value={filters.mealTypeId} onChange={(e) => setFilters({ ...filters, mealTypeId: e.target.value })}>
              <option value="">Todas</option>
              {meals.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            </select></div>
          <div className="field" style={{ flex: 1, minWidth: '220px' }}><label>Empleado</label>
            <EmployeeSelect 
              employees={employees} 
              value={filters.employeeId} 
              onChange={(id) => setFilters({ ...filters, employeeId: id })} 
            />
          </div>
          <button onClick={search}>Consultar</button>
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="ghost" onClick={() => exportFile('csv')}>Exportar CSV</button>
          <button className="ghost" onClick={() => exportFile('excel')}>Exportar Excel</button>
          <button className="ghost" onClick={() => exportFile('pdf')}>Exportar PDF</button>
        </div>
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <p>{rows.length} registros</p>
        <table>
          <thead><tr>
            <th>Fecha</th><th>Hora</th><th>Cédula</th><th>Empleado</th>
            <th>Cargo</th><th>Catering</th><th>Comida</th><th>Modo</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.businessDate}</td>
                <td>{new Date(r.consumedAt).toLocaleTimeString()}</td>
                <td>{r.identityCard}</td>
                <td>{r.employeeName}</td>
                <td>{r.positionName || '—'}</td>
                <td>{r.cateringName}</td>
                <td>{r.mealName}</td>
                <td>{r.offline ? <span className="badge off">offline</span> : <span className="badge ok">online</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
