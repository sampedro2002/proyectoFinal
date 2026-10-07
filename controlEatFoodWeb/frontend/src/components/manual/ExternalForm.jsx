import { useEffect, useRef, useState } from 'react';
import api from '../../api/client.js';
import ConsumptionDateFields, { businessDateNow } from '../ConsumptionDateFields.jsx';
import { isValidCedulaEC } from '../../utils/cedula.js';
import EmployeePicker from './EmployeePicker.jsx';
import useCandidateSearch from './useCandidateSearch.js';
import {
  EMERGENCY_HELP, ResultBox, RestaurantSelect, checkLabelStyle,
  emergencyDatePayload, emptyDateFields, todayDatePayload,
} from './FormParts.jsx';

/**
 * Pestaña "Persona externa": visitante / contratista con cédula o pasaporte.
 * method='EXTERNAL'. Las personas externas viven en su propia tabla (nunca en
 * empleados). Opcionalmente otra persona retira a su nombre, y la casilla
 * "Registro de emergencia" habilita fecha/hora/motivo fuera de horario.
 */
export default function ExternalForm({ meals, restaurants, restaurantId, setRestaurantId, onReset }) {
  const [extCard, setExtCard] = useState('');
  const [extName, setExtName] = useState('');
  const [isPassport, setIsPassport] = useState(false);
  const [extFound, setExtFound] = useState(false);           // ¿cédula ya registrada?
  const [extFoundSource, setExtFoundSource] = useState('');   // 'EMPLOYEE' | 'EXTERNAL'
  const [extLookupLoading, setExtLookupLoading] = useState(false);
  const extLookupSeq = useRef(0);
  const [extProxyEnabled, setExtProxyEnabled] = useState(false);
  const [extProxyTerm, setExtProxyTerm] = useState('');
  const [extProxy, setExtProxy] = useState(null);
  const [selectedMealCodes, setSelectedMealCodes] = useState([]);
  const [observation, setObservation] = useState('');
  const [emergency, setEmergency] = useState(false);
  const [dateFields, setDateFields] = useState(emptyDateFields);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const proxySearch = useCandidateSearch(extProxyTerm, extProxyEnabled);

  function selectExtProxy(emp) {
    if (!emp) { setExtProxy(null); setResult(null); setError(''); return; }
    setExtProxy(emp);
    setExtProxyTerm(`${emp.fullName} · ${emp.identityCard}`);
    proxySearch.setShow(false);
    setResult(null); setError('');
  }

  // ── Lookup de cédula en tiempo real ──
  useEffect(() => {
    const card = extCard.trim();
    if (card.length < 5) {
      extLookupSeq.current++;
      setExtFound(false); setExtFoundSource(''); setExtLookupLoading(false);
      return undefined;
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
  }, [extCard]);

  async function submit(e) {
    e.preventDefault();
    setError(''); setResult(null);
    const card = extCard.trim();
    if (!isPassport && !isValidCedulaEC(card)) {
      setError('La cédula ingresada no es una cédula ecuatoriana válida (10 dígitos con verificador).');
      return;
    }
    if (isPassport && isValidCedulaEC(card)) {
      setError('Ese número corresponde a una cédula ecuatoriana válida; no puede registrarse como pasaporte.');
      return;
    }
    if (!extName.trim()) { setError('Ingrese el nombre.'); return; }
    if (!restaurantId) { setError('Seleccione un restaurante.'); return; }
    if (selectedMealCodes.length === 0) { setError('Seleccione al menos un tipo de comida.'); return; }
    if (extProxyEnabled && !extProxy) { setError('Seleccione la persona que retira.'); return; }
    if (emergency) {
      if (!dateFields.businessDate) { setError('Seleccione la fecha del consumo.'); return; }
      if (dateFields.businessDate !== businessDateNow() && !dateFields.consumptionTime) {
        setError('Indique la hora del consumo para la fecha seleccionada.'); return;
      }
      if (!dateFields.reason.trim()) { setError('Indique el motivo del registro.'); return; }
    }
    const datePayload = emergency ? emergencyDatePayload(dateFields) : todayDatePayload();

    setLoading(true);
    const successResults = [];
    let lastError = null;
    for (const code of selectedMealCodes) {
      try {
        const { data } = await api.post('/manual-consumptions/external', {
          ...datePayload,
          identityCard: card,
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
    if (lastError) {
      setError(successResults.length > 0 ? `Registrado parcialmente. Error: ${lastError}` : lastError);
    } else {
      setResult({
        status: 'SUCCESS',
        message: `Consumos registrados: ${successResults.map((r) => r.mealName).join(' y ')} (Fecha: ${datePayload.businessDate})`,
        employeeName: successResults[0]?.employeeName,
      });
    }
  }

  const canSubmit = extCard.trim() && extName.trim() && restaurantId && selectedMealCodes.length > 0
    && (!extProxyEnabled || !!extProxy) && (!emergency || dateFields.reason.trim());

  return (
    <form onSubmit={submit}>
      <p style={{ color: '#94a3b8', marginTop: 0, fontSize: 13 }}>
        Registre un consumo para una persona externa (visitante, contratista, etc.). El consumo aparecerá en los reportes de esa fecha.
      </p>

      <div className="field">
        <label style={checkLabelStyle}>
          <input type="checkbox" checked={emergency}
            onChange={(e) => { setEmergency(e.target.checked); setDateFields(emptyDateFields()); setResult(null); }} />
          Registro de emergencia
        </label>
      </div>
      {emergency && (
        <>
          <p style={{ color: '#94a3b8', marginTop: 0, fontSize: 13 }}>{EMERGENCY_HELP}</p>
          <ConsumptionDateFields mode="emergency" value={dateFields} onChange={setDateFields} disabled={loading} />
        </>
      )}

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
            <label key={m.id} style={checkLabelStyle}>
              <input
                type="checkbox"
                checked={selectedMealCodes.includes(m.code)}
                onChange={(e) => {
                  setSelectedMealCodes((codes) => (e.target.checked
                    ? [...codes, m.code] : codes.filter((c) => c !== m.code)));
                  setResult(null);
                }}
              />
              {m.name}
            </label>
          ))}
        </div>
      </div>
      <div className="field">
        <label style={{ ...checkLabelStyle, gap: 8 }}>
          <input
            type="checkbox"
            checked={extProxyEnabled}
            onChange={(e) => {
              setExtProxyEnabled(e.target.checked);
              if (!e.target.checked) { setExtProxy(null); setExtProxyTerm(''); proxySearch.clear(); }
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
          suggestions={proxySearch.suggestions}
          show={proxySearch.show}
          setShow={proxySearch.setShow}
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

      <RestaurantSelect restaurants={restaurants} value={restaurantId} onChange={setRestaurantId} />

      {error && <p className="error-text">{error}</p>}
      <ResultBox result={result} />

      <div className="row" style={{ marginTop: 12 }}>
        <button type="submit" disabled={loading || !canSubmit}>
          {loading ? 'Registrando…' : 'Registrar consumo'}
        </button>
        <button type="button" className="ghost" onClick={onReset}>Limpiar</button>
      </div>
    </form>
  );
}
