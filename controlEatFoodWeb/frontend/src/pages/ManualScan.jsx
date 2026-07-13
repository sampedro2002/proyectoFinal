import { useEffect, useRef, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { looksLikeCedula, isValidCedulaEC } from '../utils/cedula.js';

export default function ManualScan() {
  const { hasRole } = useAuth();
  const [mode, setMode] = useState('employee'); // 'employee' | 'external'
  const [term, setTerm] = useState('');
  const [suggestions, setSuggestions] = useState([]);
  const [showSuggest, setShowSuggest] = useState(false);
  const [employee, setEmployee] = useState(null);
  const [extCard, setExtCard] = useState('');
  const [extName, setExtName] = useState('');
  const [restaurants, setRestaurants] = useState([]);
  const [meals, setMeals] = useState([]);
  const [restaurantId, setRestaurantId] = useState('');
  const [mealCode, setMealCode] = useState('');
  const [observation, setObservation] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    api.get('/restaurants').then((r) => {
      setRestaurants(r.data);
      if (r.data.length > 0) setRestaurantId(r.data[0].id);
    }).catch(() => {});
    api.get('/meal-types').then((r) => {
      setMeals(r.data);
      const lunch = r.data.find((m) => m.code === 'LUNCH');
      if (lunch) setMealCode(lunch.code);
    }).catch(() => {});
  }, []);

  // Autocompletar: busca empleados por término (debounce ~300ms).
  const suggestSeq = useRef(0);
  useEffect(() => {
    if (mode !== 'employee' || !term || term.trim().length < 2) {
      setSuggestions([]);
      return;
    }
    const t = setTimeout(() => {
      // Descarta la respuesta si ya se disparó una búsqueda más nueva mientras esta
      // seguía en vuelo: evita que una respuesta lenta de un término viejo sobrescriba
      // las sugerencias del término que el admin ve actualmente en el input.
      const seq = ++suggestSeq.current;
      api.get('/employees', { params: { term: term.trim(), size: 8 } })
        .then((r) => {
          if (seq !== suggestSeq.current) return;
          const list = r.data.content || r.data || [];
          setSuggestions(list);
          setShowSuggest(true);
        })
        .catch(() => { if (seq === suggestSeq.current) setSuggestions([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [term, mode]);

  function selectEmployee(emp) {
    setEmployee(emp);
    setTerm(`${emp.fullName} · ${emp.identityCard}`);
    setShowSuggest(false);
    setResult(null);
    setError('');
  }

  function switchMode(nextMode) {
    setMode(nextMode);
    setError('');
    setResult(null);
    setEmployee(null);
    setTerm('');
    setExtCard('');
    setExtName('');
  }

  async function submit(e) {
    e.preventDefault();
    setError('');
    setResult(null);

    if (mode === 'employee') {
      if (!employee) { setError('Seleccione un empleado de la lista.'); return; }
      if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
      if (!mealCode) { setError('Seleccione un tipo de comida.'); return; }
      setLoading(true);
      try {
        const { data } = await api.post('/manual-consumptions', {
          employeeId: employee.id,
          mealTypeCode: mealCode,
          restaurantId: Number(restaurantId),
          observation: observation.trim() || null,
        });
        setResult(data);
      } catch (err) {
        setError(err.response?.data?.message || 'No se pudo registrar el consumo');
      } finally {
        setLoading(false);
      }
    } else {
      // Persona externa
      const card = extCard.trim();
      if (!card) { setError('Ingrese la cédula.'); return; }
      // Si tiene forma de cédula (10 dígitos) debe ser válida; un pasaporte
      // u otro documento alfanumérico se acepta tal cual.
      if (looksLikeCedula(card) && !isValidCedulaEC(card)) {
        setError('La cédula ingresada no es una cédula ecuatoriana válida. Si es un pasaporte, ingréselo con sus letras.');
        return;
      }
      if (!extName.trim()) { setError('Ingrese el nombre.'); return; }
      if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
      if (!mealCode) { setError('Seleccione un tipo de comida.'); return; }
      setLoading(true);
      try {
        const { data } = await api.post('/manual-consumptions/external', {
          identityCard: extCard.trim(),
          fullName: extName.trim(),
          mealTypeCode: mealCode,
          restaurantId: Number(restaurantId),
          observation: observation.trim() || null,
        });
        setResult(data);
      } catch (err) {
        setError(err.response?.data?.message || 'No se pudo registrar el consumo');
      } finally {
        setLoading(false);
      }
    }
  }

  const resultColor =
    result?.status === 'SUCCESS' ? 'var(--ok, #16a34a)' : 'var(--error, #ef4444)';

  const canSubmit =
    mode === 'employee'
      ? employee && restaurantId && mealCode
      : extCard.trim() && extName.trim() && restaurantId && mealCode;

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Registro manual de consumo</h2>
      </div>

      <div className="card" style={{ maxWidth: 520 }}>
        {/* Toggle de modo */}
        <div className="row" style={{ marginBottom: 16, gap: 8 }}>
          <button
            type="button"
            className={mode === 'employee' ? '' : 'ghost'}
            onClick={() => switchMode('employee')}
            style={{ flex: 1 }}
          >
            Empleado
          </button>
          <button
            type="button"
            className={mode === 'external' ? '' : 'ghost'}
            onClick={() => switchMode('external')}
            style={{ flex: 1 }}
          >
            Añadir persona externa
          </button>
        </div>

        <p style={{ color: '#94a3b8', marginTop: 0, fontSize: 13 }}>
          {mode === 'employee'
            ? 'Como administrador puede registrar un consumo sin huella. No se validan horario, permiso ni duplicados: use esta opción para correcciones. El consumo aparecerá en el feed del kiosk del restaurante seleccionado.'
            : 'Registre un consumo para una persona externa (visitante, contratista, etc.). No es necesario que esté en la lista de empleados. El consumo aparecerá en el feed del kiosk y en reportes.'}
        </p>

        <form onSubmit={submit}>
          {mode === 'employee' ? (
            <div className="field" style={{ position: 'relative' }}>
              <label>Empleado</label>
              <input
                value={term}
                onChange={(e) => {
                  setTerm(e.target.value);
                  setEmployee(null);
                  setResult(null);
                }}
                onFocus={() => { if (suggestions.length) setShowSuggest(true); }}
                onBlur={() => setTimeout(() => setShowSuggest(false), 150)}
                placeholder="Escriba nombre o cédula…"
                autoComplete="off"
              />
              {showSuggest && suggestions.length > 0 && (
                <ul style={{
                  position: 'absolute', top: '100%', left: 0, right: 0,
                  background: 'var(--panel, #1e293b)', border: '1px solid var(--border, #334155)',
                  borderRadius: 6, margin: 0, padding: 0, listStyle: 'none',
                  zIndex: 50, maxHeight: 240, overflowY: 'auto',
                }}>
                  {suggestions.map((emp) => (
                    <li
                      key={emp.id}
                      onMouseDown={() => selectEmployee(emp)}
                      style={{
                        padding: '8px 12px', cursor: 'pointer',
                        borderBottom: '1px solid var(--border, #334155)',
                      }}
                      onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(255,255,255,.06)')}
                      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                    >
                      <div>{emp.fullName}</div>
                      <div style={{ fontSize: 12, color: '#64748b' }}>
                        {emp.identityCard}
                        {emp.status !== 'ACTIVE' && ` · ${emp.status}`}
                      </div>
                    </li>
                  ))}
                  {suggestions.length === 0 && (
                    <li style={{ padding: '8px 12px', color: '#64748b' }}>
                      Sin resultados — escriba al menos 2 caracteres.
                    </li>
                  )}
                </ul>
              )}
              {employee && (
                <div style={{ fontSize: 12, color: 'var(--ok, #16a34a)', marginTop: 4 }}>
                  Seleccionado: {employee.fullName} (ID {employee.id})
                </div>
              )}
            </div>
          ) : (
            <>
              <div className="field">
                <label>Cédula</label>
                <input
                  value={extCard}
                  onChange={(e) => { setExtCard(e.target.value); setResult(null); }}
                  placeholder="Cédula de la persona externa"
                  required
                />
              </div>
              <div className="field">
                <label>Nombre completo</label>
                <input
                  value={extName}
                  onChange={(e) => { setExtName(e.target.value); setResult(null); }}
                  placeholder="Nombre de la persona externa"
                  required
                />
              </div>
            </>
          )}

          <div className="field">
            <label>Restaurante</label>
            <select value={restaurantId} onChange={(e) => setRestaurantId(e.target.value)}>
              {restaurants.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </div>

          <div className="field">
            <label>Tipo de comida</label>
            <select value={mealCode} onChange={(e) => setMealCode(e.target.value)}>
              <option value="">— Seleccione —</option>
              {meals.map((m) => (
                <option key={m.id} value={m.code}>{m.name}</option>
              ))}
            </select>
          </div>

          <div className="field">
            <label>Observación (opcional)</label>
            <textarea
              value={observation}
              rows={2}
              placeholder="Nota sobre este registro (opcional)"
              onChange={(e) => setObservation(e.target.value)}
            />
          </div>

          {error && <p className="error-text">{error}</p>}

          {result && (
            <div style={{
              padding: 12, borderRadius: 8, marginBottom: 12,
              background: 'rgba(255,255,255,.04)', color: resultColor,
            }}>
              <strong>{result.status === 'SUCCESS' ? '✓ ' : '✕ '}{result.message}</strong>
              {result.employeeName && <div style={{ marginTop: 4 }}>{result.employeeName}</div>}
              {result.mealName && <div>{result.mealName}</div>}
            </div>
          )}

          <div className="row" style={{ marginTop: 12 }}>
            <button type="submit" disabled={loading || !canSubmit}>
              {loading ? 'Registrando…' : 'Registrar consumo'}
            </button>
            <button
              type="button"
              className="ghost"
              onClick={() => {
                setTerm(''); setEmployee(null); setResult(null); setError('');
                setExtCard(''); setExtName(''); setObservation('');
              }}
            >
              Limpiar
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
