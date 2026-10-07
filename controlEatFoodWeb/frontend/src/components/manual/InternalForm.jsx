import { useRef, useState } from 'react';
import api from '../../api/client.js';
import ConsumptionDateFields, { businessDateNow } from '../ConsumptionDateFields.jsx';
import EmployeePicker from './EmployeePicker.jsx';
import TitularCard, { loadAvailability, samePerson, toEntry, toItem, withAvailability } from './TitularCard.jsx';
import useCandidateSearch from './useCandidateSearch.js';
import { EMERGENCY_HELP, ResultBox, RestaurantSelect, checkLabelStyle, emergencyDatePayload, emptyDateFields, todayDatePayload } from './FormParts.jsx';

const LIMIT_MESSAGE = 'Límite de 10 personas por día alcanzado. Solo puede agregar personas para las que ya retiró hoy.';

/** Misma clave que devuelve el backend en proxy-usage: "E:id" empleado, "X:id" externo. */
const personKey = (p) => `${p.type === 'EMPLOYEE' ? 'E' : 'X'}:${p.id}`;

export default function InternalForm(props) {
  const [resetKey, setResetKey] = useState(0);
  return <RegistrationForm key={resetKey} {...props} onReset={() => setResetKey(k => k + 1)} />;
}

function RegistrationForm({ meals, restaurants, restaurantId, setRestaurantId, onReset }) {
  const [emergency, setEmergency] = useState(false);
  const [dateFields, setDateFields] = useState(emptyDateFields);
  const [proxyTerm, setProxyTerm] = useState('');
  const [proxy, setProxy] = useState(null);
  const [self, setSelf] = useState(null);
  const [titularTerm, setTitularTerm] = useState('');
  const [titulars, setTitulars] = useState([]);
  const [usage, setUsage] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const dateRef = useRef(dateFields.businessDate);
  const proxyRef = useRef(null);
  const titularsRef = useRef([]);
  const firstTitularAdded = useRef(false);
  const selfSeq = useRef(0);
  const usageSeq = useRef(0);
  const dateSeq = useRef(0);
  titularsRef.current = titulars;
  // Solo los titulares que el backend aún no contó hoy consumen cupo: volver a
  // retirar (p. ej. la Merienda) para alguien ya atendido no descuenta.
  const countedKeys = new Set(usage?.titularKeys ?? []);
  const newCount = (list) => list.filter(t => !countedKeys.has(personKey(t))).length;
  const atLimit = !!usage && newCount(titulars) >= usage.remaining;
  const proxySearch = useCandidateSearch(proxyTerm);
  const titularSearch = useCandidateSearch(titularTerm, !!proxy && !!usage);

  function clearFeedback() { setResult(null); setError(''); }

  async function loadUsage(person, date = dateRef.current) {
    const seq = ++usageSeq.current;
    setUsage(null);
    try {
      const { data } = await api.get('/manual-consumptions/proxy-usage', { params: {
        employeeId: person.type === 'EMPLOYEE' ? person.id : undefined,
        externalPersonId: person.type === 'EXTERNAL' ? person.id : undefined, date,
      } });
      if (seq === usageSeq.current && date === dateRef.current && samePerson(person, proxyRef.current)) setUsage(data);
    } catch {
      if (seq === usageSeq.current) setError('No se pudo consultar el contador de titulares.');
    }
  }

  async function loadSelf(person, marked = false, preserve = false) {
    const seq = ++selfSeq.current;
    const date = dateRef.current;
    const avail = await loadAvailability(person, date);
    if (seq !== selfSeq.current || date !== dateRef.current || !samePerson(person, proxyRef.current)) return;
    setSelf(previous => {
      if (preserve && previous && samePerson(previous, person)) return withAvailability(previous, avail);
      const entry = toEntry(person, avail);
      if (!marked && (firstTitularAdded.current || titularsRef.current.length)) entry.mealCodes = [];
      return entry;
    });
  }

  function selectProxy(person) {
    selfSeq.current++; usageSeq.current++;
    proxyRef.current = person;
    setProxy(person); setSelf(null); setUsage(null); clearFeedback();
    if (!person) return;
    const wasTitular = titulars.some(t => samePerson(t, person));
    if (wasTitular) setTitulars(arr => arr.filter(t => !samePerson(t, person)));
    setProxyTerm(`${person.fullName} · ${person.identityCard}`);
    proxySearch.setShow(false);
    loadSelf(person, wasTitular); loadUsage(person);
  }

  async function addTitular(person) {
    if (!person) return;
    setTitularTerm(''); titularSearch.clear();
    if (!proxy || !usage || samePerson(proxy, person) || titulars.some(t => samePerson(t, person))) return;
    const isNew = !countedKeys.has(personKey(person));
    if (isNew && atLimit) { setError(LIMIT_MESSAGE); return; }
    const date = dateRef.current;
    const seq = usageSeq.current;
    const avail = await loadAvailability(person, date);
    if (seq !== usageSeq.current || date !== dateRef.current || !samePerson(proxy, proxyRef.current)) return;
    if (titularsRef.current.some(t => samePerson(t, person))) return;
    if (isNew && newCount(titularsRef.current) >= usage.remaining) { setError(LIMIT_MESSAGE); return; }
    if (!firstTitularAdded.current) {
      firstTitularAdded.current = true;
      setSelf(entry => entry ? { ...entry, mealCodes: [] } : entry);
    }
    const next = [...titularsRef.current, toEntry(person, avail)];
    titularsRef.current = next;
    setTitulars(next); clearFeedback();
  }

  async function changeDateFields(next) {
    setDateFields(next);
    if (next.businessDate === dateRef.current) return;
    const date = next.businessDate;
    dateRef.current = date;
    const seq = ++dateSeq.current;
    clearFeedback();
    if (proxy) { loadUsage(proxy, date); loadSelf(proxy, false, true); }
    const people = titularsRef.current;
    const avails = await Promise.all(people.map(p => loadAvailability(p, date)));
    if (seq !== dateSeq.current || date !== dateRef.current) return;
    setTitulars(arr => arr.map(p => {
      const index = people.findIndex(t => samePerson(t, p));
      return index < 0 ? p : withAvailability(p, avails[index]);
    }));
  }

  function toggleEmergency(on) {
    setEmergency(on); clearFeedback(); changeDateFields(emptyDateFields());
  }

  function toggleMeal(entry, code, checked) {
    return { ...entry, mealCodes: checked ? [...entry.mealCodes, code] : entry.mealCodes.filter(c => c !== code) };
  }

  const items = [...(self ? [self] : []), ...titulars].filter(t => t.mealCodes.length).map(toItem);
  const mealCount = items.reduce((n, item) => n + item.mealTypeCodes.length, 0);
  const validEmergency = !emergency || (dateFields.businessDate && dateFields.reason.trim()
    && (dateFields.businessDate === businessDateNow() || dateFields.consumptionTime));
  const canSubmit = proxy && restaurantId && items.length && validEmergency;

  async function submit(e) {
    e.preventDefault(); clearFeedback();
    if (!proxy) { setError('Seleccione la persona que retira.'); return; }
    if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
    if (!validEmergency) { setError('Indique fecha, hora y motivo del registro de emergencia.'); return; }
    if (!items.length) { setError('Seleccione al menos un tipo de comida.'); return; }
    const datePayload = emergency ? emergencyDatePayload(dateFields) : todayDatePayload();
    setLoading(true);
    try {
      const { data } = await api.post('/manual-consumptions', {
        ...datePayload, proxyEmployeeId: proxy.type === 'EMPLOYEE' ? proxy.id : null,
        proxyExternalPersonId: proxy.type === 'EXTERNAL' ? proxy.id : null,
        restaurantId: Number(restaurantId), titulars: items,
      });
      setResult({ status: data.status, message: `${data.message} (Fecha: ${datePayload.businessDate})`, employeeName: proxy.fullName });
      if (data.status === 'SUCCESS') {
        setTitulars([]); titularsRef.current = [];
        loadSelf(proxy, false, true); loadUsage(proxy);
      }
    } catch (err) { setError(err.response?.data?.message || 'No se pudo registrar el consumo'); }
    finally { setLoading(false); }
  }

  return (
    <form onSubmit={submit}>
      <p className="muted">Busque a la persona que retira. Puede registrar su propia comida, la de hasta 10 personas por día, o ambas. Se omiten los platos no permitidos o ya registrados.</p>
      <div className="field">
        <label style={checkLabelStyle}><input type="checkbox" checked={emergency} disabled={loading}
          onChange={e => toggleEmergency(e.target.checked)} />Registro de emergencia</label>
      </div>
      {emergency && <><p className="muted">{EMERGENCY_HELP}</p><ConsumptionDateFields mode="emergency" value={dateFields} onChange={changeDateFields} disabled={loading} /></>}
      <EmployeePicker label="Persona que retira" term={proxyTerm} setTerm={setProxyTerm}
        suggestions={proxySearch.suggestions} show={proxySearch.show} setShow={proxySearch.setShow}
        onPick={selectProxy} selected={proxy} selectedLabel={proxy ? `Seleccionado: ${proxy.fullName} · ${proxy.identityCard}` : null}
        placeholder="Busque por nombre o cédula a quien retira…" disabled={loading} />
      {proxy && !self && <p className="muted">Consultando comidas disponibles…</p>}
      {self && <TitularCard titular={self} meals={meals} tag="(quien retira)"
        onToggleMeal={(code, checked) => setSelf(t => toggleMeal(t, code, checked))} />}
      <EmployeePicker label="Agregar titular (opcional)" term={titularTerm} setTerm={setTitularTerm}
        suggestions={titularSearch.suggestions} show={titularSearch.show} setShow={titularSearch.setShow}
        onPick={addTitular} selected={null} placeholder="Busque y seleccione titulares para agregar…"
        disabled={loading || !proxy || !usage} />
      {proxy && <div className="field">
        <label>{usage
          ? `Titulares (${titulars.length}) · ${usage.used + newCount(titulars)}/${usage.limit} personas ${dateFields.businessDate === businessDateNow() ? 'hoy' : 'el ' + dateFields.businessDate}`
          : 'Consultando contador de titulares…'}</label>
        {atLimit && <p className="muted">{LIMIT_MESSAGE}</p>}
        <div style={{ display: 'grid', gap: 8 }}>{titulars.map(t => <TitularCard key={`${t.type}-${t.id}`} titular={t} meals={meals}
          onToggleMeal={(code, checked) => setTitulars(arr => arr.map(p => samePerson(p, t) ? toggleMeal(p, code, checked) : p))}
          onRemove={() => setTitulars(arr => arr.filter(p => !samePerson(p, t)))} />)}</div>
      </div>}
      <RestaurantSelect restaurants={restaurants} value={restaurantId} onChange={setRestaurantId} />
      {error && <p className="error-text">{error}</p>}<ResultBox result={result} />
      <div className="row" style={{ marginTop: 12 }}>
        <button type="submit" disabled={loading || !canSubmit}>{loading ? 'Registrando…' : `Registrar ${mealCount} comida(s) para ${items.length} persona(s)`}</button>
        <button type="button" className="ghost" onClick={onReset} disabled={loading}>Limpiar</button>
      </div>
    </form>
  );
}
