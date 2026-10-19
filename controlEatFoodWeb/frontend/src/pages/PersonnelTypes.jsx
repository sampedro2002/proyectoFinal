import { useEffect, useState } from 'react';
import api from '../api/client.js';
import ConfirmModal from '../components/ConfirmModal.jsx';

export default function PersonnelTypes() {
  const [items, setItems] = useState([]);
  const [form, setForm] = useState(null);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [confirm, setConfirm] = useState(null);

  async function load() {
    try {
      const { data } = await api.get('/personnel-types', { params: { includeInactive: true } });
      setItems(data);
      setLoadError('');
    } catch (err) {
      setLoadError(err.response?.data?.message || 'No se pudieron cargar los tipos de persona');
    }
  }
  useEffect(() => { load(); }, []);

  async function save(e) {
    e.preventDefault();
    setError('');
    try {
      if (form.id) await api.put(`/personnel-types/${form.id}`, { name: form.name.trim(), sortOrder: Number(form.sortOrder), active: form.active });
      else await api.post('/personnel-types', { name: form.name.trim(), sortOrder: Number(form.sortOrder) });
      setForm(null);
      load();
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  function changeActive(type) {
    const nextActive = !type.active;
    setConfirm({
      title: nextActive ? 'Activar tipo de persona' : 'Desactivar tipo de persona',
      message: `¿Desea ${nextActive ? 'activar' : 'desactivar'} “${type.name}”?`,
      onConfirm: async () => {
        setConfirm(null);
        try {
          await api.put(`/personnel-types/${type.id}`, { name: type.name, sortOrder: type.sortOrder, active: nextActive });
          load();
        } catch (err) {
          setLoadError(err.response?.data?.message || 'Error al actualizar el estado');
        }
      },
    });
  }

  useEffect(() => {
    if (!form) return;
    const handler = e => { if (e.key === 'Escape') setForm(null); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [form]);

  return <div>
    <div className="topbar">
      <h2 style={{ margin: 0 }}>Tipo de persona</h2>
      <button onClick={() => { setError(''); setForm({ name: '', sortOrder: items.length + 1, active: true }); }}>+ Nuevo</button>
    </div>
    {loadError && <p className="error-text">{loadError}</p>}
    <div className="card">
      <table>
        <thead><tr><th>Nombre</th><th>Código</th><th>Orden</th><th>Estado</th><th></th></tr></thead>
        <tbody>
          {items.map(type => <tr key={type.id}>
            <td>{type.name}</td><td>{type.code}</td><td>{type.sortOrder}</td>
            <td><span className={`badge ${type.active ? 'ok' : 'off'}`}>{type.active ? 'Activo' : 'Inactivo'}</span></td>
            <td className="row">
              <button className="ghost" onClick={() => { setError(''); setForm({ ...type }); }}>Editar</button>
              <button className="ghost" onClick={() => changeActive(type)}>{type.active ? 'Desactivar' : 'Activar'}</button>
            </td>
          </tr>)}
          {items.length === 0 && <tr><td colSpan="5" style={{ color: 'var(--muted)' }}>Sin tipos de persona.</td></tr>}
        </tbody>
      </table>
    </div>
    {form && <div className="modal-overlay">
      <form className="card modal-card" style={{ maxWidth: 420 }} onSubmit={save}>
        <div className="topbar"><h3 style={{ margin: 0 }}>{form.id ? 'Editar tipo de persona' : 'Nuevo tipo de persona'}</h3>
          <button type="button" className="ghost" onClick={() => setForm(null)}>×</button></div>
        <div className="field"><label>Nombre</label><input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></div>
        <div className="field"><label>Orden</label><input type="number" required value={form.sortOrder} onChange={e => setForm({ ...form, sortOrder: e.target.value })} /></div>
        {form.id && <p className="muted">Código: {form.code}</p>}
        {error && <p className="error-text">{error}</p>}
        <div className="row"><button type="submit">Guardar</button><button type="button" className="ghost" onClick={() => setForm(null)}>Cancelar</button></div>
      </form>
    </div>}
    <ConfirmModal isOpen={!!confirm} title={confirm?.title} message={confirm?.message}
      onConfirm={confirm?.onConfirm} onCancel={() => setConfirm(null)} />
  </div>;
}
