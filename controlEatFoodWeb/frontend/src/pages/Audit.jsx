import { useEffect, useState } from 'react';
import api from '../api/client.js';

export default function Audit() {
  const [rows, setRows] = useState([]);
  const [entity, setEntity] = useState('');
  const [error, setError] = useState('');

  const load = () => api.get('/audit', { params: { entity, size: 100, sort: 'createdAt,desc' } })
    .then((r) => { setRows(r.data.content || r.data); setError(''); })
    .catch((err) => { setRows([]); setError(err.response?.data?.message || 'No se pudo cargar la auditoría'); });
  useEffect(() => { load(); }, []);

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Auditoría</h2>
        <div className="row">
          <input placeholder="Filtrar por entidad…" value={entity}
            onChange={(e) => setEntity(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && load()} />
          <button onClick={load}>Filtrar</button>
        </div>
      </div>
      {error && <p className="error-text">{error}</p>}
      <div className="card">
        <table>
          <thead><tr>
            <th>Fecha</th><th>Usuario</th><th>Entidad</th><th>ID</th>
            <th>Acción</th><th>Antes</th><th>Después</th><th>IP</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{new Date(r.createdAt).toLocaleString()}</td>
                <td>{r.username}</td>
                <td>{r.entityName}</td>
                <td>{r.entityId}</td>
                <td>{r.action}</td>
                <td>{r.oldValue || '—'}</td>
                <td>{r.newValue || '—'}</td>
                <td>{r.ipAddress || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
