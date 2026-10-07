import { businessDateNow } from '../ConsumptionDateFields.jsx';

/** Campos de fecha para un registro normal: hoy, hora actual, sin contingencia. */
export function todayDatePayload() {
  return { businessDate: businessDateNow(), consumptionTime: null, contingency: false, reason: null };
}

export function emergencyDatePayload(dateFields) {
  return {
    businessDate: dateFields.businessDate,
    consumptionTime: dateFields.consumptionTime || null,
    contingency: true,
    reason: dateFields.reason.trim(),
  };
}

export function emptyDateFields() {
  return { businessDate: businessDateNow(), consumptionTime: '', contingency: true, reason: '' };
}

export function RestaurantSelect({ restaurants, value, onChange }) {
  return (
    <div className="field">
      <label>Restaurante</label>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {restaurants.map((c) => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>
    </div>
  );
}

export function ResultBox({ result }) {
  if (!result) return null;
  const color = result.status === 'SUCCESS' ? 'var(--ok, #16a34a)' : 'var(--error, #ef4444)';
  return (
    <div style={{ padding: 12, borderRadius: 8, marginBottom: 12, background: 'rgba(255,255,255,.04)', color }}>
      <strong>{result.status === 'SUCCESS' ? '✓ ' : '✕ '}{result.message}</strong>
      {result.employeeName && <div style={{ marginTop: 4 }}>{result.employeeName}</div>}
      {result.mealName && <div>{result.mealName}</div>}
    </div>
  );
}

export const EMERGENCY_HELP = 'Registro de emergencia: permite guardar fuera del horario de comidas o cargar el control en papel de otra fecha. El motivo es obligatorio y queda auditado.';

export const checkLabelStyle = { display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal', cursor: 'pointer', margin: 0 };
