import { useEffect, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';

const empty = { name: '', allowsSnack: false, active: true };

export default function Positions() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  const [items, setItems] = useState([]);
  const [form, setForm] = useState(null);
  const [error, setError] = useState('');

  const load = () => api.get('/positions').then((r) => setItems(r.data));
  useEffect(() => { load(); }, []);

  async function save(e) {
    e.preventDefault();
    setError('');
    try {
      if (form.id) await api.put(`/positions/${form.id}`, form);
      else await api.post('/positions', form);
      setForm(null); load();
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Cargos</h2>
        {isAdmin && <button onClick={() => setForm({ ...empty })}>+ Nuevo</button>}
      </div>
      <div className="card">
        <table>
          <thead><tr><th>Nombre</th><th>Merienda</th><th>Activo</th><th></th></tr></thead>
          <tbody>
            {items.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td>
                <td>{p.allowsSnack ? '✓' : '—'}</td>
                <td>{p.active ? '✓' : '—'}</td>
                <td>{isAdmin && <button className="ghost" onClick={() => setForm(p)}>Editar</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {form && (
        <form className="card" style={{ marginTop: 16, maxWidth: 420 }} onSubmit={save}>
          <h3 style={{ marginTop: 0 }}>{form.id ? 'Editar' : 'Nuevo'} cargo</h3>
          <div className="field"><label>Nombre</label>
            <input value={form.name} required onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
          <label><input type="checkbox" checked={form.allowsSnack}
            onChange={(e) => setForm({ ...form, allowsSnack: e.target.checked })} /> Permite merienda</label>
          {error && <p className="error-text">{error}</p>}
          <div className="row" style={{ marginTop: 12 }}>
            <button type="submit">Guardar</button>
            <button type="button" className="ghost" onClick={() => setForm(null)}>Cancelar</button>
          </div>
        </form>
      )}
    </div>
  );
}
