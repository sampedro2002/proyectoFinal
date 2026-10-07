export function businessDateNow() {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Guayaquil', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const part = (type) => parts.find((p) => p.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function businessTime(value) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'America/Guayaquil', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).format(new Date(value));
}

/**
 * mode='emergency': registro por contingencia fijo (sin casilla) y motivo siempre
 * obligatorio. El padre es quien envía contingency=true.
 */
export default function ConsumptionDateFields({ value, onChange, editing = false, disabled = false, mode = 'default' }) {
  const emergency = mode === 'emergency';
  const otherDate = value.businessDate !== businessDateNow();
  const reasonRequired = emergency || editing || otherDate || value.contingency;
  const set = (field, next) => onChange({ ...value, [field]: next });
  return (
    <fieldset disabled={disabled} style={{ border: 0, padding: 0, margin: '0 0 16px', minWidth: 0 }}>
      <div className="row">
        <div className="field">
          <label>Fecha del consumo (Ecuador)</label>
          <input type="date" required value={value.businessDate}
            onChange={(e) => set('businessDate', e.target.value)} />
        </div>
        <div className="field">
          <label>Hora del consumo (Ecuador)</label>
          <input type="time" step="1" required={otherDate || editing} value={value.consumptionTime}
            onChange={(e) => set('consumptionTime', e.target.value)} />
        </div>
      </div>
      {!editing && !otherDate && <p className="muted">Deje la hora vacía para usar la hora actual.</p>}
      {value.businessDate > businessDateNow() && (
        <p>Se contabilizará como consumo de la fecha futura y no permitirá repetir esa comida ese día.</p>
      )}
      {!emergency && (
        <label>
          <input type="checkbox" checked={value.contingency}
            onChange={(e) => set('contingency', e.target.checked)} />{' '}
          Registro por contingencia (permite guardar fuera del horario de comidas)
        </label>
      )}
      <div className="field" style={{ marginTop: 8 }}>
        <label>Motivo {reasonRequired ? '(obligatorio)' : '(opcional)'}</label>
        <textarea value={value.reason} required={reasonRequired} maxLength={500} rows={2}
          placeholder="Ej.: corte de luz, caída de internet o corrección de fecha"
          onChange={(e) => set('reason', e.target.value)} />
      </div>
    </fieldset>
  );
}
