import { useEffect, useRef, useState } from 'react';
import api from '../api/client.js';
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
 *                  "Pepe retira de Juan" autogenerada. Solo se permite dentro
 *                  del horario configurado; los platos no permitidos o ya
 *                  registrados hoy se omiten y se informan en el mensaje.
 *   - 'external' : persona externa (visitante / contratista) con cédula o
 *                  pasaporte, sin retira-por. method='EXTERNAL'. También exige
 *                  estar dentro del horario configurado. Las personas externas
 *                  viven en su propia tabla (nunca en empleados) y se gestionan
 *                  desde esta misma vista (lista de "Personas externas
 *                  registradas" debajo del formulario).
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
  const [extFound, setExtFound] = useState(false);           // ¿cédula ya registrada?
  const [extFoundSource, setExtFoundSource] = useState('');   // 'EMPLOYEE' | 'EXTERNAL'
  const [extLookupLoading, setExtLookupLoading] = useState(false);
  const extLookupSeq = useRef(0);
  const [extProxyEnabled, setExtProxyEnabled] = useState(false);
  const [extProxyTerm, setExtProxyTerm] = useState('');
  const [extProxySuggestions, setExtProxySuggestions] = useState([]);
  const [showExtProxySuggest, setShowExtProxySuggest] = useState(false);
  const [extProxy, setExtProxy] = useState(null);

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
      api.get('/manual-consumptions/proxy-candidates', { params: { term: proxyTerm.trim() } })
        .then((r) => {
          if (seq !== proxySeqRef.current) return;
          setProxySuggestions(r.data || []);
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
      api.get('/manual-consumptions/proxy-candidates', { params: { term: titularTerm.trim() } })
        .then((r) => {
          if (seq !== titularSeqRef.current) return;
          setTitularSuggestions(r.data || []);
          setShowTitularSuggest(true);
        })
        .catch(() => { if (seq === titularSeqRef.current) setTitularSuggestions([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [titularTerm, mode]);

  // Buscador de empleado que retira en modo "external" (checkbox "Retira otra persona").
  const extProxySeqRef = useRef(0);
  useEffect(() => {
    if (mode !== 'external' || !extProxyEnabled || !extProxyTerm || extProxyTerm.trim().length < 2) {
      setExtProxySuggestions([]); return;
    }
    const t = setTimeout(() => {
      const seq = ++extProxySeqRef.current;
      api.get('/manual-consumptions/proxy-candidates', { params: { term: extProxyTerm.trim() } })
        .then((r) => {
          if (seq !== extProxySeqRef.current) return;
          setExtProxySuggestions(r.data || []);
          setShowExtProxySuggest(true);
        })
        .catch(() => { if (seq === extProxySeqRef.current) setExtProxySuggestions([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [extProxyTerm, extProxyEnabled, mode]);

  function selectExtProxy(emp) {
    if (!emp) { setExtProxy(null); setResult(null); setError(''); return; }
    setExtProxy(emp);
    setExtProxyTerm(`${emp.fullName} · ${emp.identityCard}`);
    setShowExtProxySuggest(false);
    setResult(null); setError('');
  }

  // ── Lookup de cédula en tiempo real (persona externa) ──
  useEffect(() => {
    if (mode !== 'external') return;
    const card = extCard.trim();
    if (card.length < 5) {
      setExtFound(false); setExtFoundSource(''); setExtLookupLoading(false);
      return;
    }
    setExtLookupLoading(true);
    const seq = ++extLookupSeq.current;
    const t = setTimeout(async () => {
      try {
        const { data } = await api.get('/external-persons/lookup', { params: { identityCard: card } });
        if (seq !== extLookupSeq.current) return;
        if (data.found) {
          setExtFound(true);
          setExtFoundSource(data.source);
          setExtName(data.fullName);
        } else {
          setExtFound(false);
          setExtFoundSource('');
        }
      } catch {
        if (seq === extLookupSeq.current) {
          setExtFound(false); setExtFoundSource('');
        }
      } finally {
        if (seq === extLookupSeq.current) setExtLookupLoading(false);
      }
    }, 400);
    return () => clearTimeout(t);
  }, [extCard, mode]);



  function selectProxy(emp) {
    // El onChange del buscador llama onPick(null) para deseleccionar mientras se
    // escribe: en ese caso solo se limpia el proxy (sin tocar el término tecleado,
    // que ya lo actualizó el propio onChange) y sin dereferenciar emp.
    if (!emp) { setProxy(null); setResult(null); setError(''); return; }
    // Bloquear si la persona que retira ya está en la lista de titulares
    if (titulars.find((t) => t.id === emp.id && t.type === emp.type)) {
      setError('La persona que retira no puede ser al mismo tiempo titular. Quítalo de la lista de titulares primero.');
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
    if (proxy && proxy.id === emp.id && proxy.type === emp.type) {
      setError('El titular no puede ser el mismo que la persona que retira.');
      setTitularTerm('');
      setTitularSuggestions([]);
      setShowTitularSuggest(false);
      return;
    }
    if (titulars.find((t) => t.id === emp.id && t.type === emp.type)) {
      setTitularTerm(''); setTitularSuggestions([]); setShowTitularSuggest(false);
      return;
    }

    let avail = null;
    let allowsLunch = true;
    let allowsSnack = true;
    let hadAlmuerzo = false;
    let hadMerienda = false;

    // Solo consultar disponibilidad si es empleado
    if (emp.type === 'EMPLOYEE') {
      try {
        const { data } = await api.get(`/manual-consumptions/availability/${emp.id}`);
        avail = data;
      } catch { /* ignorar */ }
      allowsLunch = avail ? avail.allowsLunch : false;
      allowsSnack = avail ? avail.allowsSnack : false;
      hadAlmuerzo = avail ? avail.hadAlmuerzo : false;
      hadMerienda = avail ? avail.hadMerienda : false;
    }
    const availableCodes = avail ? avail.availableCodes : [
      ...(allowsLunch ? ['BREAKFAST'] : []),
      ...(allowsSnack ? ['LUNCH'] : []),
    ];

    setTitulars((arr) => arr.find((t) => t.id === emp.id && t.type === emp.type)
      ? arr
      : [...arr, { ...emp, allowsLunch, allowsSnack, hadAlmuerzo, hadMerienda, mealCodes: [...availableCodes] }]);
    setTitularTerm('');
    setTitularSuggestions([]);
    setShowTitularSuggest(false);
    setResult(null); setError('');
  }

  function removeTitular(id, type) {
    setTitulars((arr) => arr.filter((t) => !(t.id === id && t.type === type)));
  }

  function setTitularMeals(id, type, code, checked) {
    setTitulars((arr) => arr.map((t) => (t.id === id && t.type === type)
      ? { ...t, mealCodes: checked ? [...t.mealCodes, code] : t.mealCodes.filter((c) => c !== code) }
      : t));
  }

  function switchMode(nextMode) {
    setMode(nextMode);
    setError(''); setResult(null);
    setProxy(null); setProxyTerm(''); setProxySuggestions([]);
    setTitulars([]); setTitularTerm(''); setTitularSuggestions([]);
    setExtCard(''); setExtName(''); setIsPassport(false);
    setExtFound(false); setExtFoundSource(''); setExtLookupLoading(false);
    setSelectedMealCodes([]); setObservation('');
    setExtProxyEnabled(false); setExtProxy(null); setExtProxyTerm(''); setExtProxySuggestions([]);
  }

  async function submit(e) {
    e.preventDefault();
    setError(''); setResult(null);

    if (mode === 'proxy') {
      if (!proxy) { setError('Seleccione la persona que retira.'); return; }
      if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
      if (titulars.length === 0) { setError('Agregue al menos un titular.'); return; }
      const items = titulars
        .filter((t) => t.mealCodes.length > 0)
        .map((t) => ({
          employeeId: t.type === 'EMPLOYEE' ? t.id : null,
          externalPersonId: t.type === 'EXTERNAL' ? t.id : null,
          mealTypeCodes: t.mealCodes
        }));
      if (items.length === 0) { setError('Seleccione al menos un tipo de comida por titular.'); return; }

      setLoading(true);
      try {
        const { data } = await api.post('/manual-consumptions', {
          proxyEmployeeId: proxy.type === 'EMPLOYEE' ? proxy.id : null,
          proxyExternalPersonId: proxy.type === 'EXTERNAL' ? proxy.id : null,
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
      if (extProxyEnabled && !extProxy) { setError('Seleccione la persona que retira.'); return; }

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
            proxyEmployeeId: extProxyEnabled && extProxy && extProxy.type === 'EMPLOYEE' ? extProxy.id : null,
            proxyExternalPersonId: extProxyEnabled && extProxy && extProxy.type === 'EXTERNAL' ? extProxy.id : null,
          });
          // El endpoint responde 200 también cuando NO registró (OUT_OF_SCHEDULE,
          // DUPLICATE…), así que el éxito se decide por data.status, no por el HTTP.
          if (data.status !== 'SUCCESS') {
            lastError = data.message || 'No se pudo registrar el consumo';
            break;
          }
          successResults.push(data);
        } catch (err) {
          lastError = err.response?.data?.message || 'No se pudo registrar el consumo';
          break;
        }
      }
      setLoading(false);
      // Si se registró al menos un consumo, la persona externa ya existe en su tabla.
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
      : extCard.trim() && extName.trim() && restaurantId && selectedMealCodes.length > 0
        && (!extProxyEnabled || !!extProxy);

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
            ? 'Una persona retira comidas a nombre de uno o varios titulares. Para cada titular marque los tipos de comida. Se creará un registro por (titular × comida) con la descripción "X retira de Y" autogenerada. Solo se puede registrar dentro del horario configurado; se omiten los platos no permitidos o ya registrados hoy.'
            : 'Registre un consumo para una persona externa (visitante, contratista, etc.). No es necesario que esté en la lista de personas registradas. Solo se puede registrar dentro del horario configurado. El consumo aparecerá en el feed del kiosk y en reportes.'}
        </p>

        <form onSubmit={submit}>
          {mode === 'proxy' ? (
            <>
              <EmployeePicker
                label="Persona que retira"
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
                      <div key={t.id + '-' + t.type} style={{
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
                            onClick={() => removeTitular(t.id, t.type)}
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
                              return <span style={{ fontSize: 12, color: '#94a3b8' }}>Sin comidas habilitadas para esta persona.</span>;
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
                                          onChange={(e) => setTitularMeals(t.id, t.type, m.code, e.target.checked)}
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
                  onChange={(e) => { setExtCard(e.target.value); setResult(null); setExtFound(false); setExtFoundSource(''); setExtName(''); }}
                  placeholder={`Ingrese ${isPassport ? 'el pasaporte' : 'la cédula'} de la persona externa`}
                  required
                />
                {extLookupLoading && (
                  <p style={{ color: 'var(--muted)', fontSize: 13, margin: '4px 0 0' }}>Verificando…</p>
                )}
              </div>
              <div className="field">
                <label>Nombre completo</label>
                <input
                  value={extName}
                  onChange={(e) => { if (!extFound) { setExtName(e.target.value); setResult(null); } }}
                  placeholder="Nombre de la persona externa"
                  required
                  readOnly={extFound}
                  style={extFound ? { opacity: 0.7, cursor: 'not-allowed' } : {}}
                />
                {extFound && (
                  <p style={{ color: 'var(--ok, #16a34a)', fontSize: 13, margin: '4px 0 0' }}>
                    ✓ Persona ya registrada{extFoundSource === 'EMPLOYEE' ? ' (empleado)' : ' (persona externa)'}. Nombre autocompletado.
                  </p>
                )}
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
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 'normal', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={extProxyEnabled}
                    onChange={(e) => {
                      setExtProxyEnabled(e.target.checked);
                      if (!e.target.checked) {
                        setExtProxy(null); setExtProxyTerm(''); setExtProxySuggestions([]);
                      }
                      setResult(null);
                    }}
                  />
                  Retira otra persona
                </label>
              </div>
              {extProxyEnabled && (
                <EmployeePicker
                  label="Persona que retira"
                  term={extProxyTerm}
                  setTerm={setExtProxyTerm}
                  suggestions={extProxySuggestions}
                  show={showExtProxySuggest}
                  setShow={setShowExtProxySuggest}
                  onPick={selectExtProxy}
                  onClear={() => setResult(null)}
                  selected={extProxy}
                  selectedLabel={extProxy ? `Seleccionado: ${extProxy.fullName} · ${extProxy.identityCard}` : null}
                  placeholder="Busque por nombre o cédula a quien retira…"
                />
              )}
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
                setExtFound(false); setExtFoundSource(''); setExtLookupLoading(false);
                setSelectedMealCodes([]); setResult(null); setError('');
                setExtProxyEnabled(false); setExtProxy(null); setExtProxyTerm(''); setExtProxySuggestions([]);
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