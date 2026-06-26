import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import api from '../api/client.js';
import { ZkFingerClient } from '../biometric/zkfinger.js';

const FINGERS = [
  'Pulgar derecho', 'Índice derecho', 'Medio derecho', 'Anular derecho', 'Meñique derecho',
  'Pulgar izquierdo', 'Índice izquierdo', 'Medio izquierdo', 'Anular izquierdo', 'Meñique izquierdo'
];

export default function EmployeeFingerprints() {
  const { id } = useParams();
  const [fingerprints, setFingerprints] = useState([]);
  const [employee, setEmployee] = useState(null);
  const [fingerIndex, setFingerIndex] = useState(1);
  const [status, setStatus] = useState('idle');
  const [msg, setMsg] = useState('');
  const [loadError, setLoadError] = useState('');
  const clientRef = useRef(null);

  async function load() {
    setLoadError('');
    try {
      const [fps, emp] = await Promise.all([
        api.get(`/fingerprints/employee/${id}`),
        api.get(`/employees/${id}`)
      ]);
      setFingerprints(fps.data);
      setEmployee(emp.data);
    } catch (err) {
      setLoadError(err.response?.data?.message || 'Error al cargar los datos del empleado');
    }
  }
  useEffect(() => { load(); }, [id]);

  async function enroll() {
    setMsg('');
    if (fingerprints.length >= 3) { setMsg('Máximo 3 huellas por empleado.'); return; }
    try {
      setStatus('connecting');
      const client = new ZkFingerClient({
        onStatus: (s) => {
          if (s === 'no-device' || s === 'error' || s === 'disconnected') {
            setStatus('idle');
            setMsg('Conecte el ZKTeco9500 al puerto USB para registrar huellas.');
          }
        },
        onProgress: (step, total) => {
          setMsg(`Captura ${step}/${total} completada — levante el dedo y vuelva a colocarlo...`);
        },
      });
      clientRef.current = client;
      await client.connect();
      if (!client.ready) {
        setStatus('idle');
        setMsg('Conecte el ZKTeco9500 al puerto USB para registrar huellas.');
        return;
      }
      setStatus('capturing');
      setMsg('Coloque el dedo en el lector... (1/3)');
      const templateB64 = await client.capture(60000, 'register');
      client.close();
      setStatus('saving');
      setMsg('');
      await api.post('/fingerprints/enroll', {
        employeeId: Number(id), fingerIndex: Number(fingerIndex), templateB64
      });
      setStatus('idle');
      setMsg('Huella registrada correctamente.');
      load();
    } catch (err) {
      setStatus('idle');
      setMsg(err.response?.data?.message || err.message || 'Error al registrar la huella');
    }
  }

  async function removeFp(fpId) {
    if (!confirm('¿Eliminar esta huella?')) return;
    await api.delete(`/fingerprints/${fpId}`);
    load();
  }

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Huellas — {employee?.fullName}</h2>
        <Link to="/employees"><button className="ghost">← Volver</button></Link>
      </div>
      {loadError && <p className="error-text">{loadError}</p>}

      <div className="card" style={{ maxWidth: 560 }}>
        <div className="field">
          <label>Dedo a registrar</label>
          <select value={fingerIndex} onChange={(e) => setFingerIndex(e.target.value)}>
            {FINGERS.map((f, i) => <option key={i} value={i}>{f}</option>)}
          </select>
        </div>
        <button onClick={enroll} disabled={status !== 'idle' || fingerprints.length >= 3}>
          {status === 'idle'       && 'Capturar huella'}
          {status === 'connecting' && 'Conectando lector…'}
          {status === 'capturing'  && 'Leyendo huellas (3×)…'}
          {status === 'saving'     && 'Guardando…'}
        </button>
        {msg && <p style={{ marginTop: 12 }}>{msg}</p>}
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <h3 style={{ marginTop: 0 }}>Huellas registradas ({fingerprints.length}/3)</h3>
        <table>
          <thead><tr><th>Dedo</th><th>Registrada</th><th></th></tr></thead>
          <tbody>
            {fingerprints.map((fp) => (
              <tr key={fp.id}>
                <td>{FINGERS[fp.fingerIndex] || fp.fingerIndex}</td>
                <td>{new Date(fp.enrolledAt).toLocaleString()}</td>
                <td><button className="danger" onClick={() => removeFp(fp.id)}>Eliminar</button></td>
              </tr>
            ))}
            {fingerprints.length === 0 && <tr><td colSpan="3">Sin huellas registradas.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
