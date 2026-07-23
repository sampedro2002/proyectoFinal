import { useEffect, useState } from 'react';
import api from '../api/client.js';
import EmployeeSelect from '../components/EmployeeSelect.jsx';

function today() { return new Date().toISOString().slice(0, 10); }

const METHOD_LABEL = { FINGERPRINT: 'Huella', MANUAL: 'Manual', EXTERNAL: 'Externo' };
const METHOD_OPTIONS = [
  { value: 'FINGERPRINT', label: 'Huella' },
  { value: 'MANUAL', label: 'Manual' },
  { value: 'EXTERNAL', label: 'Externo' },
];

export default function Reports() {
  const [filters, setFilters] = useState({
    from: today(), to: today(),
    restaurantId: '', employeeId: '', method: 'ALL',
  });
  const [rows, setRows] = useState([]);
  const [restaurants, setRestaurants] = useState([]);
  const [employees, setEmployees] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all([
      api.get('/restaurants').then((r) => setRestaurants(r.data)).catch(() => {}),
      api.get('/employees', { params: { size: 2000, status: 'ACTIVE' } }).then((r) => setEmployees(r.data.content || r.data)).catch(() => {}),
    ]);
  }, []);

  function params() {
    const p = { from: filters.from, to: filters.to };
    if (filters.restaurantId) p.restaurantId = filters.restaurantId;
    if (filters.employeeId) p.employeeId = filters.employeeId;
    if (filters.method && filters.method !== 'ALL') p.method = filters.method;
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
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudo exportar el reporte');
    }
  }

  function rowClass(method) {
    if (method === 'MANUAL') return 'row-manual';
    if (method === 'EXTERNAL') return 'row-external';
    return '';
  }

  function buildDescription(r) {
    if (r.observation && r.observation.trim() !== '') return r.observation;
    return '—';
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
          <div className="field"><label>Restaurante</label>
            <select value={filters.restaurantId} onChange={(e) => setFilters({ ...filters, restaurantId: e.target.value })}>
              <option value="">Todos</option>
              {restaurants.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select></div>

          <div className="field" style={{ flex: 1, minWidth: '220px' }}><label>Empleado</label>
            <EmployeeSelect
              employees={employees}
              value={filters.employeeId}
              onChange={(id) => setFilters({ ...filters, employeeId: id })}
            />
          </div>
          <div className="field"><label>Tipo</label>
            <select value={filters.method} onChange={(e) => setFilters({ ...filters, method: e.target.value })}>
              <option value="ALL">Todos</option>
              {METHOD_OPTIONS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
            </select>
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
            <th>N°</th><th>Fecha</th><th>Hora</th><th>Cédula</th><th>Empleado</th>
            <th>Restaurante</th><th>Comida</th><th>Tipo</th><th>Descripción</th>
          </tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.id} className={rowClass(r.method)}>
                <td>{i + 1}</td>
                <td>{r.businessDate}</td>
                <td>{new Date(r.consumedAt).toLocaleTimeString('en-US')}</td>
                <td>{r.identityCard}</td>
                <td>{r.employeeName}</td>
                <td>{r.restaurantName}</td>
                <td>{r.mealName}</td>
                <td>
                  <span className={`badge ${r.method === 'MANUAL' ? 'manual' : r.method === 'EXTERNAL' ? 'external' : 'ok'}`}>
                    {METHOD_LABEL[r.method] || r.method}
                  </span>
                </td>
                <td>{buildDescription(r)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}