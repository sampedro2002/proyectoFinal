import { useEffect, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';

const empty = { name: '', location: '', maxDevices: 2, active: true };

export default function Caterings() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  const [items, setItems] = useState([]);
  const [form, setForm] = useState(null);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');

  const load = () => api.get('/caterings')
    .then((r) => { setItems(r.data); setLoadError(''); })
    .catch((err) => { setItems([]); setLoadError(err.response?.data?.message || 'No se pudieron cargar los caterings'); });
  useEffect(() => { load(); }, []);

  async function save(e) {
    e.preventDefault();
    setError('');
    try {
      if (form.id) await api.put(`/caterings/${form.id}`, form);
      else await api.post('/caterings', form);
      setForm(null); load();
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Caterings</h2>
        {isAdmin && <button onClick={() => setForm({ ...empty })}>+ Nuevo</button>}
      </div>
      {loadError && <p className="error-text">{loadError}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Nombre</th><th>Ubicación</th><th>Máx. disp.</th><th>Conectados</th><th>Activo</th><th></th></tr></thead>
          <tbody>
            {items.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td><td>{c.location || '—'}</td>
                <td>{c.maxDevices}</td>
                <td>{c.connectedDevices}/{c.maxDevices}</td>
                <td>{c.active ? '✓' : '—'}</td>
                <td>{isAdmin && <button className="ghost" onClick={() => setForm(c)}>Editar</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {form && (
        <form className="card" style={{ marginTop: 16, maxWidth: 420 }} onSubmit={save}>
          <h3 style={{ marginTop: 0 }}>{form.id ? 'Editar' : 'Nuevo'} catering</h3>
          <div className="field"><label>Nombre</label>
            <input value={form.name} required onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
          <div className="field"><label>Ubicación</label>
            <input value={form.location || ''} onChange={(e) => setForm({ ...form, location: e.target.value })} /></div>
          <div className="field"><label>Máximo de dispositivos</label>
            <input type="number" min="1" value={form.maxDevices}
              onChange={(e) => {
                const n = Number(e.target.value);
                setForm({ ...form, maxDevices: Number.isFinite(n) && n >= 1 ? n : 1 });
              }} /></div>
          {error && <p className="error-text">{error}</p>}
          <div className="row">
            <button type="submit">Guardar</button>
            <button type="button" className="ghost" onClick={() => setForm(null)}>Cancelar</button>
          </div>
        </form>
      )}
    </div>
  );
}
