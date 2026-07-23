import { useCallback, useEffect, useRef, useState } from 'react';
import api from '../api/client.js';
import ConfirmModal from '../components/ConfirmModal.jsx';

const METHOD_LABEL = { MANUAL: 'Manual', EXTERNAL: 'Externo' };
const MEALS = [
  { value: 'Almuerzo', label: 'Almuerzo' },
  { value: 'Merienda', label: 'Merienda' },
];

/* ─────────────────────────────────────────────────────────
   EmployeePicker — buscador con autosugerencias (igual que
   ManualScan). Debe estar FUERA del componente principal
   para evitar re-montajes al escribir.
───────────────────────────────────────────────────────── */
function EmployeePicker({ label, term, setTerm, suggestions, show, setShow, onPick, hint, placeholder }) {
  const [highlight, setHighlight] = useState(-1);
  const inputRef = useRef(null);

  const handleKeyDown = (e) => {
    if (!show || suggestions.length === 0) {
      if (e.key === 'ArrowDown') { setShow(true); setHighlight(0); e.preventDefault(); }
      return;
    }
    switch (e.key) {
      case 'ArrowDown':  setHighlight(i => Math.min(i + 1, suggestions.length - 1)); e.preventDefault(); break;
      case 'ArrowUp':    setHighlight(i => Math.max(i - 1, 0));                       e.preventDefault(); break;
      case 'Enter':
        if (highlight >= 0 && highlight < suggestions.length) {
          onPick(suggestions[highlight]); setShow(false); setHighlight(-1);
        }
        e.preventDefault();
        break;
      case 'Escape':
        setShow(false); setHighlight(-1); inputRef.current?.blur();
        e.preventDefault();
        break;
    }
  };

  return (
    <div className="field" style={{ position: 'relative' }}>
      <label>{label}</label>
      <input
        ref={inputRef}
        value={term}
        onChange={e => { setTerm(e.target.value); onPick(null); setHighlight(0); }}
        onFocus={() => { if (suggestions.length) { setShow(true); setHighlight(0); } }}
        onBlur={() => setTimeout(() => { setShow(false); setHighlight(-1); }, 150)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        autoComplete="off"
      />
      {show && suggestions.length > 0 && (
        <ul style={{
          position: 'absolute', top: '100%', left: 0, right: 0,
          background: 'var(--panel, #1e293b)', border: '1px solid var(--border, #334155)',
          borderRadius: 6, margin: 0, padding: 0, listStyle: 'none',
          zIndex: 60, maxHeight: 220, overflowY: 'auto',
        }}>
          {suggestions.map((emp, idx) => (
            <li
              key={emp.id}
              onMouseDown={() => { onPick(emp); setShow(false); setHighlight(-1); }}
              style={{
                padding: '8px 12px', cursor: 'pointer',
                borderBottom: '1px solid var(--border, #334155)',
                background: highlight === idx ? 'rgba(255,255,255,.06)' : 'transparent',
              }}
              onMouseEnter={() => setHighlight(idx)}
            >
              <div>{emp.fullName}</div>
              <div style={{ fontSize: 12, color: '#64748b' }}>{emp.identityCard}</div>
            </li>
          ))}
        </ul>
      )}
      {hint && (
        <div style={{ fontSize: 12, color: 'var(--ok, #16a34a)', marginTop: 4 }}>{hint}</div>
      )}
    </div>
  );
}

/* ─────────────────────────────────────────────────────────
   Componente principal
───────────────────────────────────────────────────────── */
export default function EditManualConsumptions() {
  const [rows, setRows]                 = useState([]);
  const [totalPages, setTotalPages]     = useState(0);
  const [page, setPage]                 = useState(0);
  const [search, setSearch]             = useState('');
  const [restaurantId, setRestaurantId] = useState('');
  const [restaurants, setRestaurants]   = useState([]);
  const [showCancelled, setShowCancelled] = useState(false);
  const [editTarget, setEditTarget]     = useState(null);
  const [saving, setSaving]             = useState(false);
  const [loadError, setLoadError]       = useState('');
  const [formError, setFormError]       = useState('');
  const [confirmId, setConfirmId]       = useState(null);
  const [toast, setToast]               = useState(null);

  /* buscador de titular en el modal */
  const [titularTerm, setTitularTerm]         = useState('');
  const [titularSuggestions, setTitularSugg]  = useState([]);
  const [showTitularSuggest, setShowTitular]  = useState(false);

  /* buscador de proxy (retira por) en el modal */
  const [proxyTerm, setProxyTerm]             = useState('');
  const [proxySuggestions, setProxySugg]      = useState([]);
  const [showProxySuggest, setShowProxy]      = useState(false);

  const titularSeqRef = useRef(0);
  const proxySeqRef   = useRef(0);

  const showToast = (msg, type) => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3500);
  };

  /* ───── autocomplete titular ───── */
  useEffect(() => {
    if (!editTarget || !titularTerm || titularTerm.trim().length < 2) {
      setTitularSugg([]); return;
    }
    const t = setTimeout(() => {
      const seq = ++titularSeqRef.current;
      api.get('/employees', { params: { term: titularTerm.trim(), size: 8 } })
        .then(r => {
          if (seq !== titularSeqRef.current) return;
          const data = r.data.content || r.data || [];
          setTitularSugg(data.filter(e => e.status === 'ACTIVE'));
          setShowTitular(true);
        })
        .catch(() => { if (seq === titularSeqRef.current) setTitularSugg([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [titularTerm, editTarget]);

  /* ───── autocomplete proxy ───── */
  useEffect(() => {
    if (!editTarget || !proxyTerm || proxyTerm.trim().length < 2) {
      setProxySugg([]); return;
    }
    const t = setTimeout(() => {
      const seq = ++proxySeqRef.current;
      api.get('/employees', { params: { term: proxyTerm.trim(), size: 8 } })
        .then(r => {
          if (seq !== proxySeqRef.current) return;
          const data = r.data.content || r.data || [];
          setProxySugg(data.filter(e => e.status === 'ACTIVE'));
          setShowProxy(true);
        })
        .catch(() => { if (seq === proxySeqRef.current) setProxySugg([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [proxyTerm, editTarget]);

  /* ───── carga de lista ───── */
  const fetchList = useCallback(async (p) => {
    setLoadError('');
    try {
      const q = new URLSearchParams();
      if (search)       q.set('search', search);
      if (restaurantId) q.set('restaurantId', restaurantId);
      if (!showCancelled) q.set('cancelled', 'false');
      q.set('page', p ?? page);
      q.set('size', '20');
      const res = await api.get(`/manual-consumptions?${q}`);
      setRows(res.data.content ?? []);
      setTotalPages(res.data.totalPages ?? 0);
    } catch (err) {
      setRows([]);
      setLoadError(err.response?.data?.message || 'No se pudieron cargar los consumos');
    }
  }, [search, restaurantId, showCancelled, page]);

  useEffect(() => { fetchList(0); setPage(0); }, [fetchList]);

  /* Cerrar modal con Escape */
  useEffect(() => {
    if (!editTarget) return;
    const h = (e) => { if (e.key === 'Escape') closeEdit(); };
    document.addEventListener('keydown', h);
    return () => document.removeEventListener('keydown', h);
  }, [editTarget]);

  /* carga inicial */
  useEffect(() => {
    api.get('/restaurants').then(r => setRestaurants(r.data)).catch(() => {});
  }, []);

  /* ───── abrir modal con datos actuales ───── */
  const openEdit = async (row) => {
    setFormError('');
    try {
      const res = await api.get(`/manual-consumptions/${row.id}`);
      const d = res.data;
      setEditTarget({
        ...d,
        /* campos adicionales para los pickers */
        _titularLabel: d.employeeName ? `${d.employeeName} · ${d.identityCard || ''}` : '',
        _proxyLabel:   d.proxyEmployeeName ? `${d.proxyEmployeeName}` : '',
      });
      setTitularTerm(d.employeeName ? `${d.employeeName}${d.identityCard ? ' · ' + d.identityCard : ''}` : '');
      setProxyTerm(d.proxyEmployeeName || '');
      setTitularSugg([]); setProxySugg([]);
      // Detectar dato incorrecto existente (titular = apoderado)
      if (d.employeeId && d.proxyEmployeeId && d.employeeId === d.proxyEmployeeId) {
        setFormError('⚠ Este registro tiene un dato inválido: el titular y el apoderado son la misma persona. Debes cambiar uno de los dos antes de guardar.');
      }
    } catch { showToast('Error al cargar detalle', 'err'); }
  };

  const closeEdit = () => {
    setEditTarget(null);
    setTitularTerm(''); setProxyTerm('');
    setTitularSugg([]); setProxySugg([]);
    setFormError('');
  };

  /* ───── acciones ───── */
  const handleCancel = async (id) => {
    try {
      await api.post(`/manual-consumptions/${id}/cancel`);
      showToast('Consumo cancelado', 'ok');
      fetchList();
    } catch { showToast('Error al cancelar', 'err'); }
    setConfirmId(null);
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
    if (!editTarget.employeeId) { setFormError('Seleccione un empleado titular.'); return; }
    if (!editTarget.mealName)   { setFormError('Seleccione el tipo de comida.'); return; }
    if (editTarget.proxyEmployeeId && editTarget.proxyEmployeeId === editTarget.employeeId) {
      setFormError('El empleado que retira no puede ser el mismo que el titular.');
      return;
    }
    setSaving(true);
    setFormError('');
    try {
      const body = {
        employeeId:      editTarget.employeeId,
        restaurantId:    editTarget.restaurantId,
        mealName:        editTarget.mealName,
        proxyEmployeeId: editTarget.proxyEmployeeId || null,
      };
      await api.put(`/manual-consumptions/${editTarget.id}`, body);
      showToast('Consumo actualizado', 'ok');
      closeEdit();
      fetchList();
    } catch (err) {
      setFormError(err.response?.data?.message || 'Error al actualizar');
    } finally { setSaving(false); }
  };

  /* ───── helpers ───── */
  const fmt = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d)) return iso;
    return d.toLocaleDateString('es-EC', { day: '2-digit', month: '2-digit', year: 'numeric' });
  };

  const pickTitular = (emp) => {
    if (!emp) { setEditTarget(t => t ? { ...t, employeeId: null } : t); return; }
    // Bloquear si la persona seleccionada es el mismo que el apoderado actual
    if (editTarget?.proxyEmployeeId && editTarget.proxyEmployeeId === emp.id) {
      setFormError('El titular no puede ser el mismo que el empleado que retira. Primero quita al apoderado o elige a otra persona.');
      setTitularTerm('');
      setTitularSugg([]);
      setShowTitular(false);
      return;
    }
    setFormError('');
    setTitularTerm(`${emp.fullName} · ${emp.identityCard}`);
    setEditTarget(t => ({
      ...t,
      employeeId:    emp.id,
      _titularLabel: `${emp.fullName} · ${emp.identityCard}`,
    }));
    setShowTitular(false);
  };

  const pickProxy = (emp) => {
    if (!emp) { setEditTarget(t => t ? { ...t, proxyEmployeeId: null } : t); return; }
    // Bloquear si es el mismo que el titular
    if (editTarget?.employeeId && editTarget.employeeId === emp.id) {
      setFormError('El empleado que retira no puede ser el mismo que el titular.');
      setProxyTerm('');
      setProxySugg([]);
      setShowProxy(false);
      return;
    }
    setFormError('');
    setProxyTerm(`${emp.fullName} · ${emp.identityCard}`);
    setEditTarget(t => ({ ...t, proxyEmployeeId: emp.id, _proxyLabel: emp.fullName }));
    setShowProxy(false);
  };

  const clearProxy = () => {
    setProxyTerm('');
    setProxySugg([]);
    setEditTarget(t => t ? { ...t, proxyEmployeeId: null } : t);
  };

  /* ───── render ───── */
  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Editar Consumos</h2>
      </div>

      {toast && (
        <p className={toast.type === 'ok' ? 'success-text' : 'error-text'}>{toast.msg}</p>
      )}
      {loadError && <p className="error-text">{loadError}</p>}

      {/* ── filtros ── */}
      <div className="card" style={{ marginBottom: 12 }}>
        <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
          <input
            placeholder="Buscar empleado o cédula..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ flex: '1 1 180px' }}
          />
          <select
            value={restaurantId}
            onChange={e => setRestaurantId(e.target.value)}
            style={{ flex: '1 1 160px' }}
          >
            <option value="">Todos los restaurantes</option>
            {restaurants.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
          <label style={{ display: 'flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}>
            <input
              type="checkbox"
              checked={showCancelled}
              onChange={e => setShowCancelled(e.target.checked)}
            />
            Mostrar cancelados
          </label>
        </div>
      </div>

      {/* ── tabla ── */}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Empleado</th>
              <th>Cédula</th>
              <th>Tipo</th>
              <th>Retira por</th>
              <th>Restaurante</th>
              <th>Comida</th>
              <th>Observación</th>
              <th>Fecha</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td>{r.id}</td>
                <td>{r.employeeName}</td>
                <td>{r.identityCard || '—'}</td>
                <td>
                  <span className={`badge ${r.method === 'MANUAL' ? 'manual' : 'external'}`}>
                    {METHOD_LABEL[r.method] || r.method}
                  </span>
                </td>
                <td>{r.proxyEmployeeName || '—'}</td>
                <td>{r.restaurantName}</td>
                <td>{r.mealName}</td>
                <td style={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {r.observation || '—'}
                </td>
                <td>{fmt(r.businessDate)}</td>
                <td>
                  {r.cancelled
                    ? <span className="badge off">Cancelado</span>
                    : <span className="badge ok">Activo</span>}
                </td>
                <td className="row" style={{ gap: 4 }}>
                  {!r.cancelled && (
                    <button className="ghost" onClick={() => openEdit(r)}>Editar</button>
                  )}
                  {!r.cancelled && (
                    <button
                      className="ghost"
                      style={{ color: 'var(--err, #ef4444)' }}
                      onClick={() => setConfirmId(r.id)}
                    >
                      Cancelar
                    </button>
                  )}
                  {r.cancelled && (
                    <button className="ghost" onClick={() => handleUncancel(r.id)}>Reactivar</button>
                  )}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={11} style={{ color: 'var(--muted)', textAlign: 'center' }}>
                  No hay consumos manuales
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* ── paginación ── */}
      {totalPages > 1 && (
        <div className="row" style={{ marginTop: 12, justifyContent: 'center', gap: 8 }}>
          <button className="ghost" disabled={page === 0}
            onClick={() => { setPage(p => p - 1); fetchList(page - 1); }}>
            ← Anterior
          </button>
          <span style={{ alignSelf: 'center' }}>Página {page + 1} de {totalPages}</span>
          <button className="ghost" disabled={page >= totalPages - 1}
            onClick={() => { setPage(p => p + 1); fetchList(page + 1); }}>
            Siguiente →
          </button>
        </div>
      )}

      {/* ── modal de edición ── */}
      {editTarget && (
        <div className="modal-overlay">
          <form className="card modal-card" style={{ maxWidth: 480 }} onSubmit={handleEdit}>
            <div className="topbar" style={{ marginBottom: 16 }}>
              <h3 style={{ margin: 0 }}>Editar consumo #{editTarget.id}</h3>
              <button type="button" className="ghost" onClick={closeEdit}>✕</button>
            </div>

            {/* ── Titular ── */}
            <EmployeePicker
              label="Empleado titular"
              term={titularTerm}
              setTerm={setTitularTerm}
              suggestions={titularSuggestions}
              show={showTitularSuggest}
              setShow={setShowTitular}
              onPick={pickTitular}
              hint={editTarget.employeeId ? `✓ Seleccionado: ${editTarget._titularLabel || ''}` : null}
              placeholder="Buscar por nombre o cédula…"
            />

            {/* ── Retira por ── */}
            <div style={{ position: 'relative' }}>
              <EmployeePicker
                label="Retira por (apoderado, opcional)"
                term={proxyTerm}
                setTerm={setProxyTerm}
                suggestions={proxySuggestions}
                show={showProxySuggest}
                setShow={setShowProxy}
                onPick={pickProxy}
                hint={editTarget.proxyEmployeeId ? `✓ Seleccionado: ${editTarget._proxyLabel || ''}` : null}
                placeholder="Buscar por nombre o cédula… (dejar vacío = ninguno)"
              />
              {editTarget.proxyEmployeeId && (
                <button
                  type="button"
                  className="ghost"
                  style={{ position: 'absolute', top: 24, right: 0, fontSize: 12, padding: '2px 8px' }}
                  onClick={clearProxy}
                >
                  Quitar
                </button>
              )}
            </div>

            {/* ── Restaurante ── */}
            <div className="field">
              <label>Restaurante</label>
              <select
                value={editTarget.restaurantId ?? ''}
                onChange={e => setEditTarget({ ...editTarget, restaurantId: +e.target.value })}
              >
                <option value="">— Seleccionar —</option>
                {restaurants.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>
            </div>

            {/* ── Tipo de comida (checkboxes, igual que ManualScan) ── */}
            <div className="field">
              <label>Tipo de comida</label>
              <div className="row" style={{ gap: 16 }}>
                {MEALS.map(m => (
                  <label
                    key={m.value}
                    style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal', cursor: 'pointer', margin: 0 }}
                  >
                    <input
                      type="checkbox"
                      checked={editTarget.mealName === m.value}
                      onChange={() => setEditTarget({ ...editTarget, mealName: m.value })}
                    />
                    {m.label}
                  </label>
                ))}
              </div>
            </div>

            {formError && <p className="error-text">{formError}</p>}

            <div className="row" style={{ marginTop: 16 }}>
              <button type="submit" disabled={saving}>
                {saving ? 'Guardando...' : 'Guardar'}
              </button>
              <button type="button" className="ghost" onClick={closeEdit}>
                Cancelar
              </button>
            </div>
          </form>
        </div>
      )}

      {/* ── confirmación de cancelación ── */}
      {confirmId && (
        <ConfirmModal
          isOpen={!!confirmId}
          title="Cancelar consumo"
          message="¿Cancelar este consumo? Dejará de contarse en reportes."
          onConfirm={() => handleCancel(confirmId)}
          onCancel={() => setConfirmId(null)}
        />
      )}
    </div>
  );
}
