import { useEffect, useState } from 'react';
import api from '../api/client.js';

export default function Schedules() {
  const [schedules, setSchedules] = useState([]);
  const [meals, setMeals] = useState([]);
  const [edit, setEdit] = useState({});
  const [errors, setErrors] = useState({});

  async function load() {
    const [s, m] = await Promise.all([api.get('/schedules'), api.get('/meal-types')]);
    setSchedules(s.data);
    setMeals(m.data);
    const map = {};
    s.data.forEach((x) => { map[x.mealTypeId] = { startTime: x.startTime, endTime: x.endTime }; });
    setEdit(map);
  }
  useEffect(() => { load(); }, []);

  async function save(mealTypeId) {
    setErrors((prev) => ({ ...prev, [mealTypeId]: '' }));
    try {
      const e = edit[mealTypeId];
      await api.post('/schedules', { mealTypeId, startTime: e.startTime, endTime: e.endTime, active: true });
      load();
    } catch (err) {
      setErrors((prev) => ({ ...prev, [mealTypeId]: err.response?.data?.message || 'Error al guardar' }));
    }
  }

  return (
    <div>
      <h2>Horarios (globales)</h2>
      <div className="card" style={{ maxWidth: 560 }}>
        <table>
          <thead><tr><th>Comida</th><th>Inicio</th><th>Fin</th><th></th></tr></thead>
          <tbody>
            {meals.map((m) => {
              const e = edit[m.id] || { startTime: '12:00', endTime: '13:00' };
              return (
                <tr key={m.id}>
                  <td>{m.name}</td>
                  <td><input type="time" value={(e.startTime || '').slice(0,5)}
                    onChange={(ev) => setEdit({ ...edit, [m.id]: { ...e, startTime: ev.target.value } })} /></td>
                  <td><input type="time" value={(e.endTime || '').slice(0,5)}
                    onChange={(ev) => setEdit({ ...edit, [m.id]: { ...e, endTime: ev.target.value } })} /></td>
                  <td>
                    <button onClick={() => save(m.id)}>Guardar</button>
                    {errors[m.id] && <p className="error-text" style={{ margin: '4px 0 0' }}>{errors[m.id]}</p>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
