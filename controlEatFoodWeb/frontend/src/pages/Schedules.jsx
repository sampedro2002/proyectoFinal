import { useEffect, useState } from 'react';
import api from '../api/client.js';

export default function Schedules() {
  const [schedule, setSchedule] = useState({ startTime: '12:00', endTime: '13:00' });
  const [error, setError] = useState('');
  const [savedMsg, setSavedMsg] = useState('');
  const [loadError, setLoadError] = useState('');

  async function load() {
    setLoadError('');
    try {
      const res = await api.get('/schedules');
      if (res.data && res.data.length > 0) {
         const first = res.data[0];
         setSchedule({ startTime: first.startTime, endTime: first.endTime });
      }
    } catch (err) {
      setLoadError(err.response?.data?.message || 'No se pudieron cargar los horarios');
    }
  }
  useEffect(() => { load(); }, []);

  useEffect(() => {
    if (!savedMsg) return;
    const t = setTimeout(() => setSavedMsg(''), 3000);
    return () => clearTimeout(t);
  }, [savedMsg]);

  function changeTime(field, value) {
    setSavedMsg('');
    setSchedule({ ...schedule, [field]: value });
  }

  async function save() {
    setError('');
    setSavedMsg('');
    try {
      await api.post('/schedules', { startTime: schedule.startTime, endTime: schedule.endTime, active: true });
      setSavedMsg('Hora guardada.');
      load();
    } catch (err) {
      setError(err.response?.data?.message || 'Error al guardar');
    }
  }

  return (
    <div>
      <h2>Horario general</h2>
      {loadError && <p className="error-text">{loadError}</p>}
      <div className="card" style={{ maxWidth: 560 }}>
        <table>
          <thead><tr><th>Inicio</th><th>Fin</th><th></th></tr></thead>
          <tbody>
            <tr>
              <td><input type="time" value={(schedule.startTime || '').slice(0,5)}
                onChange={(ev) => changeTime('startTime', ev.target.value)} /></td>
              <td><input type="time" value={(schedule.endTime || '').slice(0,5)}
                onChange={(ev) => changeTime('endTime', ev.target.value)} /></td>
              <td style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                <button onClick={save}>Guardar</button>
                {error && <span className="error-text">{error}</span>}
                {savedMsg && <span className="badge ok" style={{ fontSize: '13px', padding: '6px 12px' }}>✓ {savedMsg}</span>}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
