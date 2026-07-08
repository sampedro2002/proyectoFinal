import { useEffect, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';

// Etiquetas amigables para los roles internos.
const ROLE_LABELS = { ADMIN: 'Administrador', CATERING: 'Restaurante' };
const roleLabel = (name) => ROLE_LABELS[name] || name;

const empty = {
  username: '', fullName: '', email: '', password: '',
  enabled: true, roles: ['CATERING'], restaurantId: '',
};

export default function Users() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  const [items, setItems] = useState([]);
  const [roles, setRoles] = useState([]);
  const [restaurants, setRestaurants] = useState([]);
  const [form, setForm] = useState(null);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');

  const load = () => api.get('/users')
    .then((r) => { setItems(r.data); setLoadError(''); })
    .catch((err) => { setItems([]); setLoadError(err.response?.data?.message || 'No se pudieron cargar los usuarios'); });

  useEffect(() => {
    load();
    api.get('/users/roles').then((r) => setRoles(r.data)).catch(() => {});
    api.get('/restaurants').then((r) => setRestaurants(r.data)).catch(() => {});
  }, []);

  function openNew() {
    setError('');
    setForm({ ...empty });
  }

  function openEdit(u) {
    setError('');
    setForm({
      id: u.id,
      username: u.username,
      fullName: u.fullName,
      email: u.email || '',
      password: '',
      enabled: u.enabled,
      roles: u.roles && u.roles.length ? u.roles : ['CATERING'],
      restaurantId: u.restaurantId || '',
    });
  }

  function toggleRole(name) {
    setForm((f) => {
      const has = f.roles.includes(name);
      return { ...f, roles: has ? f.roles.filter((r) => r !== name) : [...f.roles, name] };
    });
  }

  async function save(e) {
    e.preventDefault();
    setError('');
    const payload = {
      username: form.username.trim(),
      fullName: form.fullName.trim(),
      email: form.email.trim() || null,
      password: form.password ? form.password : null,
      enabled: form.enabled,
      roles: form.roles,
      restaurantId: form.restaurantId ? Number(form.restaurantId) : null,
    };
    try {
      if (form.id) await api.put(`/users/${form.id}`, payload);
      else await api.post('/users', payload);
      setForm(null); load();
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  async function toggleEnabled(u) {
    try {
      await api.patch(`/users/${u.id}/enabled`, { enabled: !u.enabled });
      load();
    } catch (err) {
      setLoadError(err.response?.data?.message || 'No se pudo cambiar el estado');
    }
  }

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Usuarios</h2>
        {isAdmin && <button onClick={openNew}>+ Nuevo usuario</button>}
      </div>
      {loadError && <p className="error-text">{loadError}</p>}
      <div className="card">
        <table>
          <thead><tr>
            <th>Usuario</th><th>Nombre</th><th>Rol</th><th>Restaurante</th><th>Estado</th><th></th>
          </tr></thead>
          <tbody>
            {items.map((u) => (
              <tr key={u.id}>
                <td>{u.username}</td>
                <td>{u.fullName}</td>
                <td>{(u.roles || []).map(roleLabel).join(', ') || '—'}</td>
                <td>{u.restaurantName || '—'}</td>
                <td>
                  <span className={`badge ${u.enabled ? 'ok' : 'off'}`}>
                    {u.enabled ? 'Activo' : 'Inactivo'}
                  </span>
                </td>
                <td className="row">
                  {isAdmin && <button className="ghost" onClick={() => openEdit(u)}>Editar</button>}
                  {isAdmin && (
                    <button className="ghost" onClick={() => toggleEnabled(u)}>
                      {u.enabled ? 'Desactivar' : 'Activar'}
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr><td colSpan="6" style={{ color: 'var(--muted)' }}>Sin usuarios.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {form && (
        <div
          className="modal-overlay"
          onClick={(e) => { if (e.target === e.currentTarget) setForm(null); }}
        >
          <form className="card modal-card" style={{ maxWidth: 460 }} onSubmit={save}>
            <div className="topbar" style={{ marginBottom: 16 }}>
              <h3 style={{ margin: 0 }}>{form.id ? 'Editar' : 'Nuevo'} usuario</h3>
              <button type="button" className="ghost" onClick={() => setForm(null)}>✕</button>
            </div>

            <div className="field"><label>Usuario</label>
              <input value={form.username} required disabled={!!form.id}
                onChange={(e) => setForm({ ...form, username: e.target.value })} /></div>

            <div className="field"><label>Nombre completo</label>
              <input value={form.fullName} required
                onChange={(e) => setForm({ ...form, fullName: e.target.value })} /></div>

            <div className="field"><label>Email (opcional)</label>
              <input type="email" value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })} /></div>

            <div className="field">
              <label>{form.id ? 'Nueva contraseña (dejar en blanco para no cambiar)' : 'Contraseña'}</label>
              <input type="password" value={form.password} required={!form.id}
                autoComplete="new-password"
                onChange={(e) => setForm({ ...form, password: e.target.value })} />
            </div>

            <div className="field">
              <label>Roles</label>
              <div className="row" style={{ flexWrap: 'wrap', gap: 12 }}>
                {(roles.length ? roles.map((r) => r.name) : ['ADMIN', 'CATERING']).map((name) => (
                  <label key={name} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <input type="checkbox" checked={form.roles.includes(name)}
                      onChange={() => toggleRole(name)} />
                    {roleLabel(name)}
                  </label>
                ))}
              </div>
            </div>

            <div className="field">
              <label>Restaurante (para operadores de punto de venta)</label>
              <select value={form.restaurantId}
                onChange={(e) => setForm({ ...form, restaurantId: e.target.value })}>
                <option value="">— Ninguno —</option>
                {restaurants.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </div>

            <div className="field">
              <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input type="checkbox" checked={form.enabled}
                  onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
                Cuenta activa
              </label>
            </div>

            {error && <p className="error-text">{error}</p>}
            <div className="row" style={{ marginTop: 16 }}>
              <button type="submit">Guardar</button>
              <button type="button" className="ghost" onClick={() => setForm(null)}>Cancelar</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
