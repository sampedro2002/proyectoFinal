import { useEffect, useRef, useState } from 'react';
import api from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { isValidCedulaEC } from '../utils/cedula.js';

/**
 * Registro manual de consumo.
 *
 * Dos modos:
 *   - 'proxy'    : "Retira por otro". Un empleado (Pepe) retira comidas a
 *                  nombre de uno o varios titulares (Juan, Luis, Maria...).
 *                  Para cada titular se marcan los tipos de comida. El backend
 *                  crea una fila de consumption por (titular x comida) con
 *                  method='MANUAL', empleado_apoderado_id=Pepe y observacion
 *                  "Pepe retira de Juan" autogenerada. No se valida horario,
 *                  permisos ni duplicados de los titulares (override admin).
 *   - 'external' : persona externa (visitante / contratista) con cédula o
 *                  pasaporte, sin retira-por. method='EXTERNAL'.
 */
/**
 * Buscador de empleados con autosugerencias. DEBE estar a nivel de módulo (no dentro
 * de ManualScan): si se define dentro del componente, cada render crea una función
 * nueva → React lo trata como un tipo distinto, desmonta/remonta el <input> en cada
 * tecla y se pierde el foco tras escribir una sola letra.
 */
function EmployeePicker({ label, term, setTerm, suggestions, show, setShow, onPick, selected, selectedLabel, placeholder, onClear }) {
  const [highlight, setHighlight] = useState(-1);
  const inputRef = useRef(null);

  const handleKeyDown = (e) => {
    if (!show || suggestions.length === 0) {
      if (e.key === 'ArrowDown') {
        setShow(true);
        setHighlight(0);
        e.preventDefault();
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown':
        setHighlight(i => Math.min(i + 1, suggestions.length - 1));
        e.preventDefault();
        break;
      case 'ArrowUp':
        setHighlight(i => Math.max(i - 1, 0));
        e.preventDefault();
        break;
      case 'Enter':
        if (highlight >= 0 && highlight < suggestions.length) {
          onPick(suggestions[highlight]);
          setShow(false);
          setHighlight(-1);
        }
        e.preventDefault();
        break;
      case 'Escape':
        setShow(false);
        setHighlight(-1);
        inputRef.current?.blur();
        e.preventDefault();
        break;
    }
  };

  const handlePick = (emp) => {
    onPick(emp);
    setShow(false);
    setHighlight(-1);
  };

  return (
    <div className="field" style={{ position: 'relative' }}>
      <label>{label}</label>
      <input
        ref={inputRef}
        value={term}
        onChange={(e) => { setTerm(e.target.value); if (onPick) onPick(null); if (onClear) onClear(); setHighlight(0); }}
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
          zIndex: 50, maxHeight: 240, overflowY: 'auto',
        }}>
          {suggestions.map((emp, idx) => (
            <li
              key={emp.id}
              onMouseDown={() => handlePick(emp)}
              style={{
                padding: '8px 12px', cursor: 'pointer',
                borderBottom: '1px solid var(--border, #334155)',
                background: highlight === idx ? 'rgba(255,255,255,.06)' : 'transparent',
              }}
              onMouseEnter={() => setHighlight(idx)}
            >
              <div>{emp.fullName}</div>
              <div style={{ fontSize: 12, color: '#64748b' }}>
                {emp.identityCard}
                {emp.status !== 'ACTIVE' && ` · ${emp.status}`}
              </div>
            </li>
          ))}
        </ul>
      )}
      {selected && (
        <div style={{ fontSize: 12, color: 'var(--ok, #16a34a)', marginTop: 4 }}>
          {selectedLabel}
        </div>
      )}
    </div>
  );
}

export default function ManualScan() {
  const { hasRole } = useAuth();
  const [mode, setMode] = useState('proxy'); // 'proxy' | 'external'

  // --- sugerencias reutilizables (autosuggest de empleados) ---
  const [proxyTerm, setProxyTerm] = useState('');
  const [proxySuggestions, setProxySuggestions] = useState([]);
  const [showProxySuggest, setShowProxySuggest] = useState(false);
  const [proxy, setProxy] = useState(null);

  const [titularTerm, setTitularTerm] = useState('');
  const [titularSuggestions, setTitularSuggestions] = useState([]);
  const [showTitularSuggest, setShowTitularSuggest] = useState(false);
  const [titulars, setTitulars] = useState([]); // [{ ...emp, mealCodes: [...] }]

  // --- persona externa ---
  const [extCard, setExtCard] = useState('');
  const [extName, setExtName] = useState('');
  const [isPassport, setIsPassport] = useState(false);

  // --- comunes ---
  const [restaurants, setRestaurants] = useState([]);
  const [meals, setMeals] = useState([]);
  const [restaurantId, setRestaurantId] = useState('');
  const [selectedMealCodes, setSelectedMealCodes] = useState([]); // solo para external
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
    }).catch(() => {});
  }, []);

  // ---- Autosuggest (proxy y titulares usan el mismo endpoint /employees) ----
  const proxySeqRef = useRef(0);
  useEffect(() => {
    if (mode !== 'proxy' || !proxyTerm || proxyTerm.trim().length < 2) {
      setProxySuggestions([]); return;
    }
    const t = setTimeout(() => {
      const seq = ++proxySeqRef.current;
      api.get('/employees', { params: { term: proxyTerm.trim(), size: 8 } })
        .then((r) => {
          if (seq !== proxySeqRef.current) return;
          const data = r.data.content || r.data || [];
          setProxySuggestions(data.filter(e => e.status === 'ACTIVE'));
          setShowProxySuggest(true);
        })
        .catch(() => { if (seq === proxySeqRef.current) setProxySuggestions([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [proxyTerm, mode]);

  const titularSeqRef = useRef(0);
  useEffect(() => {
    if (mode !== 'proxy' || !titularTerm || titularTerm.trim().length < 2) {
      setTitularSuggestions([]); return;
    }
    const t = setTimeout(() => {
      const seq = ++titularSeqRef.current;
      api.get('/employees', { params: { term: titularTerm.trim(), size: 8 } })
        .then((r) => {
          if (seq !== titularSeqRef.current) return;
          const data = r.data.content || r.data || [];
          setTitularSuggestions(data.filter(e => e.status === 'ACTIVE'));
          setShowTitularSuggest(true);
        })
        .catch(() => { if (seq === titularSeqRef.current) setTitularSuggestions([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [titularTerm, mode]);

  function selectProxy(emp) {
    // El onChange del buscador llama onPick(null) para deseleccionar mientras se
    // escribe: en ese caso solo se limpia el proxy (sin tocar el término tecleado,
    // que ya lo actualizó el propio onChange) y sin dereferenciar emp.
    if (!emp) { setProxy(null); setResult(null); setError(''); return; }
    // Bloquear si la persona que retira ya está en la lista de titulares
    if (titulars.find((t) => t.id === emp.id)) {
      setError('El empleado que retira no puede ser al mismo tiempo titular. Quítalo de la lista de titulares primero.');
      setProxyTerm('');
      setProxySuggestions([]);
      setShowProxySuggest(false);
      return;
    }
    setProxy(emp);
    setProxyTerm(`${emp.fullName} · ${emp.identityCard}`);
    setShowProxySuggest(false);
    setResult(null); setError('');
  }

  async function addTitular(emp) {
    // Igual que selectProxy: onChange manda null al escribir. Un titular solo se
    // agrega al elegirlo de las sugerencias, así que con null no se hace nada.
    if (!emp) return;
    // Bloquear si el titular seleccionado es el mismo que quien retira
    if (proxy && proxy.id === emp.id) {
      setError('El titular no puede ser el mismo que el empleado que retira.');
      setTitularTerm('');
      setTitularSuggestions([]);
      setShowTitularSuggest(false);
      return;
    }
    if (titulars.find((t) => t.id === emp.id)) {
      setTitularTerm(''); setTitularSuggestions([]); setShowTitularSuggest(false);
      return;
    }

    // Se consulta al backend qué comidas puede registrarse HOY este empleado:
    // las permitidas (allowsLunch/allowsSnack) y que aún no consumió. Con eso se
    // pre-seleccionan solo las disponibles y se bloquean las ya registradas.
    let avail = null;
    try {
      const { data } = await api.get(`/manual-consumptions/availability/${emp.id}`);
      avail = data;
    } catch { /* si falla, se cae a los flags del propio empleado (sin datos de consumo de hoy) */ }

    const allowsLunch = avail ? avail.allowsLunch : !!emp.allowsLunch;
    const allowsSnack = avail ? avail.allowsSnack : !!(emp.allowsSnack ?? emp.effectiveSnack);
    const hadAlmuerzo = avail ? avail.hadAlmuerzo : false;
    const hadMerienda = avail ? avail.hadMerienda : false;
    const availableCodes = avail ? avail.availableCodes : [
      ...(allowsLunch ? ['BREAKFAST'] : []),
      ...(allowsSnack ? ['LUNCH'] : []),
    ];

    setTitulars((arr) => arr.find((t) => t.id === emp.id)
      ? arr
      : [...arr, { ...emp, allowsLunch, allowsSnack, hadAlmuerzo, hadMerienda, mealCodes: [...availableCodes] }]);
    setTitularTerm('');
    setTitularSuggestions([]);
    setShowTitularSuggest(false);
    setResult(null); setError('');
  }

  function removeTitular(id) {
    setTitulars((arr) => arr.filter((t) => t.id !== id));
  }

  function setTitularMeals(id, code, checked) {
    setTitulars((arr) => arr.map((t) => t.id === id
      ? { ...t, mealCodes: checked ? [...t.mealCodes, code] : t.mealCodes.filter((c) => c !== code) }
      : t));
  }

  function switchMode(nextMode) {
    setMode(nextMode);
    setError(''); setResult(null);
    setProxy(null); setProxyTerm(''); setProxySuggestions([]);
    setTitulars([]); setTitularTerm(''); setTitularSuggestions([]);
    setExtCard(''); setExtName(''); setIsPassport(false);
    setSelectedMealCodes([]); setObservation('');
  }

  async function submit(e) {
    e.preventDefault();
    setError(''); setResult(null);

    if (mode === 'proxy') {
      if (!proxy) { setError('Seleccione el empleado que retira.'); return; }
      if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
      if (titulars.length === 0) { setError('Agregue al menos un titular.'); return; }
      const items = titulars
        .filter((t) => t.mealCodes.length > 0)
        .map((t) => ({ employeeId: t.id, mealTypeCodes: t.mealCodes }));
      if (items.length === 0) { setError('Seleccione al menos un tipo de comida por titular.'); return; }

      setLoading(true);
      try {
        const { data } = await api.post('/manual-consumptions', {
          proxyEmployeeId: proxy.id,
          restaurantId: Number(restaurantId),
          titulars: items,
        });
        setResult({
          status: data.status,
          message: data.status === 'SUCCESS'
            ? `Se crearon ${data.created} registro(s): ${proxy.fullName} retiró por ${titulars.map((t) => t.fullName).join(', ')}`
            : data.message,
          employeeName: proxy.fullName,
        });
        setTitulars([]);
      } catch (err) {
        setError(err.response?.data?.message || 'No se pudo registrar el consumo');
      } finally {
        setLoading(false);
      }
    } else {
      // Persona externa
      const card = extCard.trim();
      if (!isPassport && !isValidCedulaEC(card)) {
        setError('La cédula ingresada no es una cédula ecuatoriana válida (10 dígitos con verificador).');
        return;
      }
      if (!extName.trim()) { setError('Ingrese el nombre.'); return; }
      if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
      if (selectedMealCodes.length === 0) { setError('Seleccione al menos un tipo de comida.'); return; }

      setLoading(true);
      const successResults = [];
      let lastError = null;
      for (const code of selectedMealCodes) {
        try {
          const { data } = await api.post('/manual-consumptions/external', {
            identityCard: extCard.trim(),
            isPassport,
            fullName: extName.trim(),
            mealTypeCode: code,
            restaurantId: Number(restaurantId),
            observation: observation.trim() || null,
          });
          successResults.push(data);
        } catch (err) {
          lastError = err.response?.data?.message || 'No se pudo registrar el consumo';
          break;
        }
      }
      setLoading(false);
      if (lastError) {
        setError(successResults.length > 0
          ? `Registrado parcialmente. Error: ${lastError}` : lastError);
      } else {
        setResult({
          status: 'SUCCESS',
          message: `Consumos registrados: ${successResults.map((r) => r.mealName).join(' y ')}`,
          employeeName: successResults[0]?.employeeName,
        });
      }
    }
  }

  const resultColor =
    result?.status === 'SUCCESS' ? 'var(--ok, #16a34a)' : 'var(--error, #ef4444)';

  const canSubmit =
    mode === 'proxy'
      ? proxy && restaurantId && titulars.some((t) => t.mealCodes.length > 0)
      : extCard.trim() && extName.trim() && restaurantId && selectedMealCodes.length > 0;

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Registro manual de consumo</h2>
      </div>

      <div className="card" style={{ maxWidth: 640 }}>
        <div className="row" style={{ marginBottom: 16, gap: 8 }}>
          <button
            type="button"
            className={mode === 'proxy' ? '' : 'ghost'}
            onClick={() => switchMode('proxy')}
            style={{ flex: 1 }}
          >
            Retira por otro
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
          {mode === 'proxy'
            ? 'Un empleado retira comidas a nombre de uno o varios titulares. Para cada titular marque los tipos de comida. Se creará un registro por (titular × comida) con la descripción "X retira de Y" autogenerada. No se valida horario, permisos ni duplicados.'
            : 'Registre un consumo para una persona externa (visitante, contratista, etc.). No es necesario que esté en la lista de empleados. El consumo aparecerá en el feed del kiosk y en reportes.'}
        </p>

        <form onSubmit={submit}>
          {mode === 'proxy' ? (
            <>
              <EmployeePicker
                label="Empleado que retira"
                term={proxyTerm}
                setTerm={setProxyTerm}
                suggestions={proxySuggestions}
                show={showProxySuggest}
                setShow={setShowProxySuggest}
                onPick={selectProxy}
                onClear={() => setResult(null)}
                selected={proxy}
                selectedLabel={proxy ? `Seleccionado: ${proxy.fullName} · ${proxy.identityCard}` : null}
                placeholder="Busque por nombre o cédula a quien retira…"
              />

              <EmployeePicker
                label="Agregar titular"
                term={titularTerm}
                setTerm={setTitularTerm}
                suggestions={titularSuggestions}
                show={showTitularSuggest}
                setShow={setShowTitularSuggest}
                onPick={addTitular}
                onClear={() => setResult(null)}
                selected={null}
                selectedLabel={null}
                placeholder="Busque y seleccione titulares para agregar…"
              />

              {titulars.length > 0 && (
                <div className="field">
                  <label>Titulares ({titulars.length})</label>
                  <div style={{ display: 'grid', gap: 8 }}>
                    {titulars.map((t) => (
                      <div key={t.id} style={{
                        border: '1px solid var(--border, #334155)',
                        borderRadius: 8, padding: '8px 10px',
                        background: 'rgba(255,255,255,0.02)',
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                          <div>
                            <strong>{t.fullName}</strong>
                            <span style={{ marginLeft: 8, fontSize: 12, color: '#94a3b8' }}>{t.identityCard}</span>
                          </div>
                          <button
                            type="button"
                            className="ghost"
                            style={{ padding: '2px 8px', fontSize: 12 }}
                            onClick={() => removeTitular(t.id)}
                          >
                            Quitar
                          </button>
                        </div>
                        <div className="row" style={{ gap: 12, marginTop: 6, flexWrap: 'wrap' }}>
                          {(() => {
                            // Se muestran SOLO las comidas permitidas para el empleado; las
                            // que ya consumió hoy salen deshabilitadas y marcadas. LUNCH=Merienda
                            // (requiere allowsSnack), BREAKFAST=Almuerzo (requiere allowsLunch).
                            const allowedMeals = meals.filter((m) =>
                              m.code === 'LUNCH' ? t.allowsSnack : t.allowsLunch);
                            if (allowedMeals.length === 0) {
                              return <span style={{ fontSize: 12, color: '#94a3b8' }}>Sin comidas habilitadas para este empleado.</span>;
                            }
                            
                            const consumedMeals = allowedMeals.filter(m => m.code === 'LUNCH' ? t.hadMerienda : t.hadAlmuerzo);

                            return (
                              <div style={{ width: '100%' }}>
                                <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
                                  {allowedMeals.map((m) => {
                                    const consumed = m.code === 'LUNCH' ? t.hadMerienda : t.hadAlmuerzo;
                                    return (
                                      <label key={m.id} style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal', cursor: consumed ? 'not-allowed' : 'pointer', margin: 0, opacity: consumed ? 0.5 : 1 }}>
                                        <input
                                          type="checkbox"
                                          disabled={consumed}
                                          checked={!consumed && t.mealCodes.includes(m.code)}
                                          onChange={(e) => setTitularMeals(t.id, m.code, e.target.checked)}
                                        />
                                        {m.name}{consumed ? ' (ya registrada hoy)' : ''}
                                      </label>
                                    );
                                  })}
                                </div>
                                {consumedMeals.length > 0 && (
                                  <div style={{ color: 'var(--err, #ef4444)', fontSize: 13, marginTop: 6, fontWeight: 500 }}>
                                    {t.fullName} ya consumió su {consumedMeals.map(m => m.name).join(' y ')}.
                                  </div>
                                )}
                              </div>
                            );
                          })()}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          ) : (
            <>
              <div className="field">
                <label>Tipo de Documento</label>
                <select value={isPassport ? 'PASSPORT' : 'CEDULA'}
                        onChange={(e) => { setIsPassport(e.target.value === 'PASSPORT'); setResult(null); }}>
                  <option value="CEDULA">Cédula</option>
                  <option value="PASSPORT">Pasaporte</option>
                </select>
              </div>
              <div className="field">
                <label>{isPassport ? 'Pasaporte' : 'Cédula'}</label>
                <input
                  value={extCard}
                  onChange={(e) => { setExtCard(e.target.value); setResult(null); }}
                  placeholder={`Ingrese ${isPassport ? 'el pasaporte' : 'la cédula'} de la persona externa`}
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
              <div className="field">
                <label>Tipo de comida</label>
                <div className="row" style={{ gap: 16 }}>
                  {meals.map((m) => (
                    <label key={m.id} style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal', cursor: 'pointer', margin: 0 }}>
                      <input
                        type="checkbox"
                        checked={selectedMealCodes.includes(m.code)}
                        onChange={(e) => {
                          if (e.target.checked) {
                            setSelectedMealCodes([...selectedMealCodes, m.code]);
                          } else {
                            setSelectedMealCodes(selectedMealCodes.filter((c) => c !== m.code));
                          }
                          setResult(null);
                        }}
                      />
                      {m.name}
                    </label>
                  ))}
                </div>
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
            </>
          )}

          {mode === 'proxy' && (
            <div className="field">
              <label>Restaurante</label>
              <select value={restaurantId} onChange={(e) => setRestaurantId(e.target.value)}>
                {restaurants.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </div>
          )}
          {mode === 'external' && (
            <>
              <div className="field">
                <label>Restaurante</label>
                <select value={restaurantId} onChange={(e) => setRestaurantId(e.target.value)}>
                  {restaurants.map((c) => (
                    <option key={c.id} value={c.id}>{c.name}</option>
                  ))}
                </select>
              </div>
            </>
          )}

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
                setProxyTerm(''); setProxy(null); setProxySuggestions([]);
                setTitulars([]); setTitularTerm(''); setTitularSuggestions([]);
                setExtCard(''); setExtName(''); setObservation(''); setIsPassport(false);
                setSelectedMealCodes([]); setResult(null); setError('');
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