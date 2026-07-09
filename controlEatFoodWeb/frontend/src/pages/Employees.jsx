import { useEffect, useMemo, useRef, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { ZkFingerClient } from '../biometric/zkfinger.js';
import ConfirmModal from '../components/ConfirmModal.jsx';
import Drawer from '../components/Drawer.jsx';

const FINGERS = [
  'Pulgar derecho', 'Índice derecho', 'Medio derecho', 'Anular derecho', 'Meñique derecho',
  'Pulgar izquierdo', 'Índice izquierdo', 'Medio izquierdo', 'Anular izquierdo', 'Meñique izquierdo',
];

const empty = {
  identityCard: '', fullName: '', observation: '',
  status: 'ACTIVE', allowsLunch: true, allowsSnack: false,
};

function isoDaysAgo(days) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}
function isoToday() {
  return new Date().toISOString().slice(0, 10);
}

export default function Employees() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');

  const [items, setItems]         = useState([]);
  const [term, setTerm]           = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [error, setError]         = useState('');
  const [loading, setLoading]     = useState(false);
  const [confirmDialog, setConfirmDialog] = useState(null);

  // modal (crear/editar)
  const [form, setForm]     = useState(null);
  const [tab, setTab]       = useState('data');
  const [savedMsg, setSavedMsg] = useState('');

  // fingerprint state
  const [fingerprints, setFingerprints] = useState([]);
  const [fingerIndex, setFingerIndex]   = useState(0);
  const [bioStatus, setBioStatus]       = useState('idle');
  const [bioMsg, setBioMsg]             = useState('');
  const zkRef = useRef(null);
  const formIdRef = useRef(null);
  const loadSeq = useRef(0);


  async function load() {
    // Descarta respuestas obsoletas: el debounce y el botón "Buscar"/Enter pueden disparar
    // varias peticiones en vuelo, y sin esto una respuesta lenta y vieja podía sobrescribir
    // la lista con resultados que ya no corresponden al texto actual del buscador.
    const seq = ++loadSeq.current;
    setLoading(true);
    try {
      const { data } = await api.get('/employees', { params: { term, size: 200 } });
      if (seq !== loadSeq.current) return;
      setItems(data.content || data);
      setError('');
    } catch (err) {
      if (seq !== loadSeq.current) return;
      setItems([]);
      setError(err.response?.data?.message || 'No se pudieron cargar los empleados');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }

  async function loadFp(empId) {
    try {
      const { data } = await api.get(`/fingerprints/employee/${empId}`);
      setFingerprints(data);
    } catch (err) {
      setFingerprints([]);
    }
  }

  // Búsqueda con debounce: se dispara 350 ms después de dejar de escribir
  // (y también en el montaje inicial). El botón "Buscar" y Enter siguen funcionando.
  useEffect(() => {
    const t = setTimeout(() => { load(); }, 350);
    return () => clearTimeout(t);
  }, [term]);

  useEffect(() => {
    formIdRef.current = form?.id ?? null;
    if (form?.id) loadFp(form.id);
    else setFingerprints([]);
  }, [form?.id]);

  // Cerrar el WebSocket del lector si el componente se desmonta.
  useEffect(() => {
    return () => {
      try { zkRef.current && zkRef.current.close(); } catch (_) {}
      zkRef.current = null;
    };
  }, []);

  const filtered = useMemo(() => {
    if (statusFilter === 'ALL') return items;
    return items.filter(e => e.status === statusFilter);
  }, [items, statusFilter]);

  function openNew() {
    setError(''); setSavedMsg(''); setBioMsg(''); setBioStatus('idle');
    setFingerIndex(0); setTab('data');
    setForm({ ...empty });
  }

  function openEdit(emp) {
    setError(''); setSavedMsg(''); setBioMsg(''); setBioStatus('idle');
    setFingerIndex(0); setTab('data');
    setForm({
      ...emp,
      observation:   emp.observation ?? '',
      allowsLunch:   emp.allowsLunch ?? true,
      allowsSnack:   emp.allowsSnack ?? emp.effectiveSnack ?? false,
    });
  }

  function closeForm() {
    // Corta cualquier captura de huella en curso; si no, su promesa sigue resolviendo
    // en segundo plano y termina aplicando su resultado al modal del siguiente empleado
    // que se abra (ver guardas por formIdRef en enroll()).
    try { zkRef.current && zkRef.current.close(); } catch (_) {}
    zkRef.current = null;
    setForm(null);
    setSavedMsg('');
    load();
  }

  async function save(e) {
    e.preventDefault();
    setError('');
    setSavedMsg('');
    const payload = {
      identityCard: form.identityCard,
      fullName: form.fullName,
      observation: form.observation || null,
      status: form.status,
      allowsLunch: form.allowsLunch,
      allowsSnack: form.allowsSnack,
    };
    try {
      if (form.id) {
        await api.put(`/employees/${form.id}`, payload);
        setSavedMsg('Cambios guardados.');
        load();
      } else {
        const { data } = await api.post('/employees', payload);
        setSavedMsg('Empleado creado correctamente. Ahora puede registrar hasta 3 huellas.');
        setForm({
          ...data,
          observation:   data.observation ?? '',
          allowsLunch:   data.allowsLunch ?? true,
          allowsSnack:   data.allowsSnack ?? data.effectiveSnack ?? false,
        });
        setTab('fingerprints');
        load();
      }
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  async function enroll() {
    if (!form?.id) return;
    // Fija el empleado objetivo: si el admin cierra este modal o abre el de otro empleado
    // mientras la captura (hasta 60s) sigue en curso, las actualizaciones de estado de más
    // abajo se descartan en vez de aplicarse al modal que quedó abierto.
    const targetId = form.id;
    const isStale = () => formIdRef.current !== targetId;
    setBioMsg('');
    if (fingerprints.length >= 3) { setBioMsg('Máximo 3 huellas por empleado.'); return; }
    try {
      setBioStatus('connecting');
      const client = new ZkFingerClient({
        onStatus: (s) => {
          if (isStale()) return;
          if (s === 'no-device' || s === 'error' || s === 'disconnected') {
            setBioStatus('idle');
            setBioMsg('Conecte el ZKTeco9500 al puerto USB para registrar huellas.');
          }
        },
        onProgress: (step, total) => {
          if (!isStale()) setBioMsg(`Captura ${step}/${total} completada — levante el dedo y vuelva a colocarlo...`);
        },
      });
      zkRef.current = client;
      await client.connect();
      if (isStale()) { client.close(); zkRef.current = null; return; }
      if (!client.ready) {
        setBioStatus('idle');
        setBioMsg('Conecte el ZKTeco9500 al puerto USB para registrar huellas.');
        return;
      }
      setBioStatus('capturing');
      setBioMsg('Coloque el dedo en el lector... (1/3)');
      const templateB64 = await client.capture(60000, 'register');
      client.close();
      zkRef.current = null;
      if (isStale()) return;
      setBioStatus('saving');
      setBioMsg('');
      await api.post('/fingerprints/enroll', {
        employeeId: Number(targetId),
        fingerIndex: Number(fingerIndex),
        templateB64,
      });
      if (isStale()) return;
      setBioStatus('idle');
      setBioMsg('Huella registrada correctamente.');
      loadFp(targetId);
    } catch (err) {
      try { zkRef.current && zkRef.current.close(); } catch (_) {}
      zkRef.current = null;
      if (isStale()) return;
      setBioStatus('idle');
      setBioMsg(err.response?.data?.message || err.message || 'Error al registrar la huella');
    }
  }

  function removeFp(fpId) {
    setConfirmDialog({
      title: 'Eliminar huella',
      message: '¿Está seguro de eliminar esta huella?',
      onConfirm: async () => {
        try {
          await api.delete(`/fingerprints/${fpId}`);
          loadFp(form.id);
        } catch (err) {
          setError(err.response?.data?.message || 'Error al eliminar huella');
        }
        setConfirmDialog(null);
      },
      onCancel: () => setConfirmDialog(null)
    });
  }

  // ----- Exportación -----
  async function exportDb(format) {
    try {
      const { data, headers } = await api.get('/employees/export', {
        params: { format },
        responseType: 'blob',
      });
      const blob = new Blob([data], { type: headers['content-type'] });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = format === 'csv' ? 'empleados.csv' : 'empleados.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      setError('No se pudo exportar la base de empleados.');
    }
  }


  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Empleados</h2>
        <div className="row">
          <input placeholder="Buscar…" value={term}
                 onChange={e => setTerm(e.target.value)}
                 onKeyDown={e => e.key === 'Enter' && load()} />
          <button onClick={load}>Buscar</button>
          <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
            <option value="ALL">Todos</option>
            <option value="ACTIVE">Activo</option>
            <option value="INACTIVE">Inactivo</option>
          </select>
          {isAdmin && <button onClick={openNew}>+ Nuevo empleado</button>}
          {isAdmin && <button className="ghost" onClick={() => exportDb('excel')}>Exportar Excel</button>}
          {isAdmin && <button className="ghost" onClick={() => exportDb('csv')}>Exportar CSV</button>}
        </div>
      </div>

      {error && !form && <p className="error-text">{error}</p>}

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Código</th><th>Cédula</th><th>Nombre</th>
              <th>Almuerzo</th><th>Merienda</th><th>Huellas</th><th>Estado</th><th></th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(e => (
              <tr key={e.id}>
                <td>{e.publicCode || '—'}</td>
                <td>{e.identityCard}</td>
                <td>{e.fullName}</td>
                <td>{e.allowsLunch ? 'Sí' : 'No'}</td>
                <td>{(e.allowsSnack ?? e.effectiveSnack) ? 'Sí' : 'No'}</td>
                <td>{e.fingerprintCount}/3</td>
                <td>
                  <span className={`badge ${e.status === 'ACTIVE' ? 'ok' : 'off'}`}>
                    {e.status}
                  </span>
                </td>
                <td className="row">
                  {isAdmin && (
                    <button className="ghost" onClick={() => openEdit(e)}>Editar</button>
                  )}
                </td>
              </tr>
            ))}
            {loading && (
              <tr><td colSpan="8" style={{ color: 'var(--muted)' }}>Cargando…</td></tr>
            )}
            {!loading && filtered.length === 0 && (
              <tr><td colSpan="8" style={{ color: 'var(--muted)' }}>Sin empleados.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* ── Modal central crear/editar ── */}
      {form && (
        <div
          className="modal-overlay"
          onClick={(e) => { if (e.target === e.currentTarget) closeForm(); }}
        >
          <div className="card modal-card">
            <div className="topbar" style={{ marginBottom: 12 }}>
              <h3 style={{ margin: 0 }}>{form.id ? 'Editar empleado' : 'Nuevo empleado'}</h3>
              <button type="button" className="ghost" onClick={closeForm}>✕</button>
            </div>

            <div className="tabs">
              <button
                type="button"
                className={`tab ${tab === 'data' ? 'active' : ''}`}
                onClick={() => setTab('data')}
              >Datos</button>
              <button
                type="button"
                className={`tab ${tab === 'fingerprints' ? 'active' : ''}`}
                onClick={() => form.id && setTab('fingerprints')}
                disabled={!form.id}
                title={!form.id ? 'Guarde el empleado para registrar huellas' : ''}
              >Huellas</button>
            </div>

            {savedMsg && <p style={{ color: 'var(--ok)', marginTop: 12 }}>{savedMsg}</p>}

            {tab === 'data' && (
              <form onSubmit={save} style={{ marginTop: 12 }}>
                {form.publicCode && (
                  <div className="field">
                    <label>Código</label>
                    <input value={form.publicCode} readOnly disabled />
                  </div>
                )}
                <div className="field">
                  <label>Cédula</label>
                  <input value={form.identityCard} required
                         onChange={e => setForm({ ...form, identityCard: e.target.value })} />
                </div>
                <div className="field">
                  <label>Nombres</label>
                  <input value={form.fullName} required
                         onChange={e => setForm({ ...form, fullName: e.target.value })} />
                </div>
                <div className="row">
                  <label>
                    <input type="checkbox" checked={form.allowsLunch}
                      onChange={e => setForm({ ...form, allowsLunch: e.target.checked })} />
                    {' '}Almuerzo
                  </label>
                  <label>
                    <input type="checkbox" checked={form.allowsSnack}
                      onChange={e => setForm({ ...form, allowsSnack: e.target.checked })} />
                    {' '}Merienda
                  </label>
                </div>

                <div className="field" style={{ marginTop: 12 }}>
                  <label>Estado</label>
                  <select value={form.status}
                          onChange={e => setForm({ ...form, status: e.target.value })}>
                    <option value="ACTIVE">Activo</option>
                    <option value="INACTIVE">Inactivo</option>
                  </select>
                </div>

                <div className="field">
                  <label>Observación (opcional)</label>
                  <textarea value={form.observation} rows={3}
                            onChange={e => setForm({ ...form, observation: e.target.value })} />
                </div>

                {error && <p className="error-text">{error}</p>}
                <div className="row" style={{ marginTop: 12 }}>
                  <button type="submit">{form.id ? 'Guardar cambios' : 'Crear empleado'}</button>
                  <button type="button" className="ghost" onClick={closeForm}>
                    {form.id ? 'Cerrar' : 'Cancelar'}
                  </button>
                </div>
              </form>
            )}

            {tab === 'fingerprints' && (
              <div style={{ marginTop: 12 }}>
                {!form.id ? (
                  <p style={{ color: 'var(--muted)' }}>
                    Guarde el empleado para poder registrar huellas.
                  </p>
                ) : (
                  <>
                    <h4 style={{ margin: '0 0 12px' }}>
                      Huellas digitales ({fingerprints.length}/3)
                    </h4>
                    <table>
                      <thead>
                        <tr><th>Dedo</th><th>Registrada</th><th></th></tr>
                      </thead>
                      <tbody>
                        {fingerprints.map(fp => (
                          <tr key={fp.id}>
                            <td>{FINGERS[fp.fingerIndex] ?? `Dedo ${fp.fingerIndex}`}</td>
                            <td>{new Date(fp.enrolledAt).toLocaleString()}</td>
                            <td>
                              <button className="danger" onClick={() => removeFp(fp.id)}>
                                Eliminar
                              </button>
                            </td>
                          </tr>
                        ))}
                        {fingerprints.length === 0 && (
                          <tr>
                            <td colSpan="3" style={{ color: 'var(--muted)' }}>
                              Sin huellas registradas.
                            </td>
                          </tr>
                        )}
                      </tbody>
                    </table>

                    {fingerprints.length < 3 ? (
                      <>
                        <div className="row" style={{ marginTop: 12, flexWrap: 'wrap', gap: 8 }}>
                          <select
                            value={fingerIndex}
                            onChange={e => setFingerIndex(e.target.value)}
                            style={{ flex: '1 1 180px' }}
                          >
                            {FINGERS.map((f, i) => (
                              <option key={i} value={i}>{f}</option>
                            ))}
                          </select>
                          <button onClick={enroll} disabled={bioStatus !== 'idle'}>
                            {bioStatus === 'idle'       && 'Capturar huella'}
                            {bioStatus === 'connecting' && 'Conectando lector…'}
                            {bioStatus === 'capturing'  && 'Leyendo huellas (3×)…'}
                            {bioStatus === 'saving'     && 'Guardando…'}
                          </button>
                        </div>
                        {bioMsg && (
                          <p style={{
                            marginTop: 10,
                            color: bioMsg.includes('correctamente')
                              ? 'var(--ok)'
                              : bioMsg.startsWith('Captura') || bioMsg.startsWith('Coloque')
                                ? 'var(--muted)'
                                : 'var(--err)',
                          }}>
                            {bioMsg}
                          </p>
                        )}
                      </>
                    ) : (
                      <p style={{ marginTop: 10, color: 'var(--muted)' }}>
                        Límite de 3 huellas alcanzado. Elimine una para agregar otra.
                      </p>
                    )}
                  </>
                )}
              </div>
            )}
          </div>
        </div>
      )}


      <ConfirmModal
        isOpen={!!confirmDialog}
        title={confirmDialog?.title}
        message={confirmDialog?.message}
        onConfirm={confirmDialog?.onConfirm}
        onCancel={confirmDialog?.onCancel}
      />
    </div>
  );
}
