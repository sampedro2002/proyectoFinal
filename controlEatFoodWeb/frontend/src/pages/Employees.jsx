import { useEffect, useRef, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { ZkFingerClient } from '../biometric/zkfinger.js';
import ConfirmModal from '../components/ConfirmModal.jsx';

const FINGERS = [
  'Pulgar derecho', 'Índice derecho', 'Medio derecho', 'Anular derecho', 'Meñique derecho',
  'Pulgar izquierdo', 'Índice izquierdo', 'Medio izquierdo', 'Anular izquierdo', 'Meñique izquierdo',
];

const empty = {
  identityCard: '', fullName: '', positionId: '', status: 'ACTIVE',
  allowsLunch: true, allowsSnack: false,
};

export default function Employees() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');

  const [items, setItems]         = useState([]);
  const [positions, setPositions] = useState([]);
  const [term, setTerm]           = useState('');
  const [form, setForm]           = useState(null);
  const [error, setError]         = useState('');
  const [savedMsg, setSavedMsg]   = useState('');
  const [confirmDialog, setConfirmDialog] = useState(null);

  // fingerprint state
  const [fingerprints, setFingerprints] = useState([]);
  const [fingerIndex, setFingerIndex]   = useState(0);
  const [bioStatus, setBioStatus]       = useState('idle');
  const [bioMsg, setBioMsg]             = useState('');
  const zkRef = useRef(null);

  async function load() {
    try {
      const { data } = await api.get('/employees', { params: { term, size: 100 } });
      setItems(data.content || data);
      setError('');
    } catch (err) {
      setItems([]);
      setError(err.response?.data?.message || 'No se pudieron cargar los empleados');
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

  useEffect(() => {
    load();
    api.get('/positions').then(r => setPositions(r.data)).catch(() => {});
  }, []);

  useEffect(() => {
    if (form?.id) loadFp(form.id);
    else setFingerprints([]);
  }, [form?.id]);

  // Cerrar el WebSocket del lector si el componente se desmonta en medio de una
  // captura (p. ej. el usuario navega a otra página mientras enrola).
  useEffect(() => {
    return () => {
      try { zkRef.current && zkRef.current.close(); } catch (_) {}
      zkRef.current = null;
    };
  }, []);

  function openNew() {
    setError(''); setSavedMsg(''); setBioMsg(''); setBioStatus('idle');
    setFingerIndex(0);
    setForm({ ...empty });
  }

  function openEdit(emp) {
    setError(''); setSavedMsg(''); setBioMsg(''); setBioStatus('idle');
    setFingerIndex(0);
    setForm({
      ...emp,
      positionId:   emp.positionId   ?? '',
      allowsLunch:  emp.allowsLunch  ?? true,
      allowsSnack:  emp.effectiveSnack  ?? false,
    });
  }

  function closeForm() {
    setForm(null);
    setSavedMsg('');
    load();
  }

  async function save(e) {
    e.preventDefault();
    setError('');
    setSavedMsg('');
    const payload = {
      ...form,
      positionId: form.positionId || null,
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
          positionId:   data.positionId   ?? '',
          allowsLunch:  data.allowsLunch  ?? true,
          allowsSnack:  data.effectiveSnack  ?? false,
        });
        load();
      }
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  async function enroll() {
    if (!form?.id) return;
    setBioMsg('');
    if (fingerprints.length >= 3) { setBioMsg('Máximo 3 huellas por empleado.'); return; }
    try {
      setBioStatus('connecting');
      const client = new ZkFingerClient({
        onStatus: (s) => {
          if (s === 'no-device' || s === 'error' || s === 'disconnected') {
            setBioStatus('idle');
            setBioMsg('Conecte el ZKTeco9500 al puerto USB para registrar huellas.');
          }
        },
        onProgress: (step, total) => {
          setBioMsg(`Captura ${step}/${total} completada — levante el dedo y vuelva a colocarlo...`);
        },
      });
      zkRef.current = client;
      await client.connect();
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
      setBioStatus('saving');
      setBioMsg('');
      await api.post('/fingerprints/enroll', {
        employeeId: Number(form.id),
        fingerIndex: Number(fingerIndex),
        templateB64,
      });
      setBioStatus('idle');
      setBioMsg('Huella registrada correctamente.');
      loadFp(form.id);
    } catch (err) {
      // Asegurar cierre del WebSocket incluso si capture() lanza (timeout, etc.).
      try { zkRef.current && zkRef.current.close(); } catch (_) {}
      zkRef.current = null;
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

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Empleados</h2>
        <div className="row">
          <input placeholder="Buscar…" value={term}
                 onChange={e => setTerm(e.target.value)}
                 onKeyDown={e => e.key === 'Enter' && load()} />
          <button onClick={load}>Buscar</button>
          {isAdmin && <button onClick={openNew}>+ Nuevo</button>}
        </div>
      </div>

      {error && !form && <p className="error-text">{error}</p>}

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Cédula</th><th>Nombre</th><th>Cargo</th>
              <th>Almuerzo</th><th>Merienda</th><th>Huellas</th><th>Estado</th><th></th>
            </tr>
          </thead>
          <tbody>
            {items.map(e => (
              <tr key={e.id}>
                <td>{e.identityCard}</td>
                <td>{e.fullName}</td>
                <td>{e.positionName || '—'}</td>
                <td>{e.allowsLunch ? '1' : '0'}</td>
                <td>{e.effectiveSnack ? '1' : '0'}</td>
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
          </tbody>
        </table>
      </div>

      {form && (
        <div className="card" style={{ marginTop: 16, maxWidth: 560 }}>
          <h3 style={{ marginTop: 0 }}>
            {form.id ? 'Editar empleado' : 'Nuevo empleado'}
          </h3>

          {savedMsg && (
            <p style={{ color: 'var(--ok, #16a34a)', marginTop: 0 }}>{savedMsg}</p>
          )}

          <form onSubmit={save}>
            <div className="field">
              <label>Cédula</label>
              <input value={form.identityCard} required
                     onChange={e => setForm({ ...form, identityCard: e.target.value })} />
            </div>
            <div className="field">
              <label>Nombre completo</label>
              <input value={form.fullName} required
                     onChange={e => setForm({ ...form, fullName: e.target.value })} />
            </div>
            <div className="field">
              <label>Cargo</label>
              <select value={form.positionId}
                      onChange={e => setForm({ ...form, positionId: e.target.value })}>
                <option value="">— Sin cargo —</option>
                {positions.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
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
            {error && <p className="error-text">{error}</p>}
            <div className="row" style={{ marginTop: 12 }}>
              <button type="submit">
                {form.id ? 'Guardar cambios' : 'Crear empleado'}
              </button>
              <button type="button" className="ghost" onClick={closeForm}>
                {form.id ? 'Cerrar' : 'Cancelar'}
              </button>
            </div>
          </form>

          {/* ── Sección de huellas: sólo disponible cuando el empleado ya existe ── */}
          {form.id && isAdmin && (
            <div style={{
              marginTop: 24,
              paddingTop: 20,
              borderTop: '1px solid var(--border, #334155)',
            }}>
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
                      <td colSpan="3" style={{ color: '#94a3b8' }}>
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
                        ? 'var(--ok, #16a34a)'
                        : bioMsg.startsWith('Captura') || bioMsg.startsWith('Coloque')
                          ? 'var(--muted, #94a3b8)'
                          : 'var(--error, #ef4444)',
                    }}>
                      {bioMsg}
                    </p>
                  )}
                </>
              ) : (
                <p style={{ marginTop: 10, color: '#94a3b8' }}>
                  Límite de 3 huellas alcanzado. Elimine una para agregar otra.
                </p>
              )}
            </div>
          )}
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
