import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext.jsx';
import EmployeeSelect from '../components/EmployeeSelect.jsx';
import ConfirmModal from '../components/ConfirmModal.jsx';

export default function EditManualConsumptions() {
  const { api } = useAuth();
  const [rows, setRows] = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [restaurantId, setRestaurantId] = useState('');
  const [restaurants, setRestaurants] = useState([]);
  const [showCancelled, setShowCancelled] = useState(false);
  const [editTarget, setEditTarget] = useState(null);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState(null);

  const showToast = (msg, type) => { setToast({ msg, type }); setTimeout(() => setToast(null), 3000); };

  const fetchList = useCallback(async (p) => {
    try {
      const q = new URLSearchParams();
      if (search) q.set('search', search);
      if (restaurantId) q.set('restaurantId', restaurantId);
      if (!showCancelled) q.set('cancelled', 'false');
      q.set('page', p ?? page);
      q.set('size', '20');
      const res = await api.get(`/manual-consumptions?${q}`);
      setRows(res.data.content);
      setTotalPages(res.data.totalPages);
    } catch { setRows([]); }
  }, [search, restaurantId, showCancelled, page, api]);

  useEffect(() => { fetchList(0); setPage(0); }, [fetchList]);

  useEffect(() => {
    if (!editTarget) return;
    const handler = (e) => { if (e.key === 'Escape') setEditTarget(null); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [editTarget]);

  useEffect(() => {
    api.get('/restaurants').then(r => setRestaurants(r.data)).catch(() => {});
  }, [api]);

  const handleCancel = async (id) => {
    try {
      await api.post(`/manual-consumptions/${id}/cancel`);
      showToast('Consumo cancelado', 'ok');
      fetchList();
    } catch { showToast('Error al cancelar', 'err'); }
  };

  const handleUncancel = async (id) => {
    try {
      await api.post(`/manual-consumptions/${id}/uncancel`);
      showToast('Consumo reactivado', 'ok');
      fetchList();
    } catch { showToast('Error al reactivar', 'err'); }
  };

  const handleEdit = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      const body = {};
      if (editTarget.proxyEmployeeId) body.proxyEmployeeId = editTarget.proxyEmployeeId;
      if (editTarget.employeeId) body.employeeId = editTarget.employeeId;
      if (editTarget.restaurantId) body.restaurantId = editTarget.restaurantId;
      body.mealName = editTarget.mealName;
      await api.put(`/manual-consumptions/${editTarget.id}`, body);
      showToast('Consumo actualizado', 'ok');
      setEditTarget(null);
      fetchList();
    } catch (err) {
      const msg = err.response?.data?.message || 'Error al actualizar';
      showToast(msg, 'err');
    } finally { setSaving(false); }
  };

  const openEdit = async (row) => {
    try {
      const res = await api.get(`/manual-consumptions/${row.id}`);
      setEditTarget(res.data);
    } catch { showToast('Error al cargar detalle', 'err'); }
  };

  return (
    <div className="page">
      <h1>Editar Consumos</h1>

      {toast && <div className={`toast ${toast.type}`}>{toast.msg}</div>}

      <div className="filters">
        <input placeholder="Buscar empleado..." value={search} onChange={e => setSearch(e.target.value)} />
        <select value={restaurantId} onChange={e => setRestaurantId(e.target.value)}>
          <option value="">Todos los restaurantes</option>
          {restaurants.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
        <label className="checkbox-inline">
          <input type="checkbox" checked={showCancelled} onChange={e => setShowCancelled(e.target.checked)} />
          Mostrar cancelados
        </label>
      </div>

      <table className="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>Empleado</th>
            <th>Cédula</th>
            <th>Retira por</th>
            <th>Restaurante</th>
            <th>Comida</th>
            <th>Observación</th>
            <th>Fecha</th>
            <th>Estado</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.id} className={r.cancelled ? 'row-cancelled' : ''}>
              <td>{r.id}</td>
              <td>{r.employeeName}</td>
              <td>{r.identityCard}</td>
              <td>{r.proxyEmployeeName || '-'}</td>
              <td>{r.restaurantName}</td>
              <td>{r.mealName}</td>
              <td>{r.observation}</td>
              <td>{r.businessDate}</td>
              <td>{r.cancelled ? <span className="badge badge-err">Cancelado</span> : <span className="badge badge-ok">Activo</span>}</td>
              <td className="actions">
                {!r.cancelled && <button className="btn-sm" onClick={() => openEdit(r)}>Editar</button>}
                {!r.cancelled && <button className="btn-sm btn-danger" onClick={() => handleCancel(r.id)}>Cancelar</button>}
                {r.cancelled && <button className="btn-sm" onClick={() => handleUncancel(r.id)}>Reactivar</button>}
              </td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={10} className="empty">No hay consumos manuales</td></tr>}
        </tbody>
      </table>

      {totalPages > 1 && (
        <div className="pagination">
          <button disabled={page === 0} onClick={() => { setPage(p => p - 1); fetchList(page - 1); }}>Anterior</button>
          <span>Página {page + 1} de {totalPages}</span>
          <button disabled={page >= totalPages - 1} onClick={() => { setPage(p => p + 1); fetchList(page + 1); }}>Siguiente</button>
        </div>
      )}

      {editTarget && (
        <div className="modal-overlay">
          <div className="modal">
            <h2>Editar Consumo Manual #{editTarget.id}</h2>
            <form onSubmit={handleEdit}>
              <label>Empleado (titular)
                <EmployeeSelect
                  value={editTarget.employeeId}
                  onChange={id => setEditTarget({ ...editTarget, employeeId: id })}
                />
              </label>
              <label>Retira por (apoderado)
                <EmployeeSelect
                  value={editTarget.proxyEmployeeId}
                  onChange={id => setEditTarget({ ...editTarget, proxyEmployeeId: id })}
                />
              </label>
              <label>Restaurante
                <select value={editTarget.restaurantId} onChange={e => setEditTarget({ ...editTarget, restaurantId: +e.target.value })}>
                  {restaurants.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </label>
              <label>Comida
                <select value={editTarget.mealName} onChange={e => setEditTarget({ ...editTarget, mealName: e.target.value })}>
                  <option value="Almuerzo">Almuerzo</option>
                  <option value="Merienda">Merienda</option>
                </select>
              </label>
              <div className="modal-actions">
                <button type="button" className="btn" onClick={() => setEditTarget(null)}>Cerrar</button>
                <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Guardando...' : 'Guardar'}</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
