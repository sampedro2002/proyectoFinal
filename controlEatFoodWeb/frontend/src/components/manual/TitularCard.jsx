import api from '../../api/client.js';

export const samePerson = (a, b) => !!a && !!b && a.id === b.id && a.type === b.type;

/**
 * Comidas permitidas/consumidas de una persona en la fecha. Solo los empleados
 * tienen restricciones; para personas externas se ofrecen ambas comidas y el
 * backend omite las ya registradas. BREAKFAST=Almuerzo (allowsLunch),
 * LUNCH=Merienda (allowsSnack).
 */
export async function loadAvailability(person, date) {
  if (person.type !== 'EMPLOYEE') {
    return { allowsLunch: true, allowsSnack: true, hadAlmuerzo: false, hadMerienda: false, availableCodes: ['BREAKFAST', 'LUNCH'] };
  }
  try {
    const { data } = await api.get(`/manual-consumptions/availability/${person.id}`, { params: { date } });
    return data;
  } catch {
    return { allowsLunch: false, allowsSnack: false, hadAlmuerzo: false, hadMerienda: false, availableCodes: [] };
  }
}

/** Persona + disponibilidad, con las comidas disponibles preseleccionadas. */
export function toEntry(person, avail) {
  return {
    ...person,
    allowsLunch: avail.allowsLunch,
    allowsSnack: avail.allowsSnack,
    hadAlmuerzo: avail.hadAlmuerzo,
    hadMerienda: avail.hadMerienda,
    mealCodes: [...avail.availableCodes],
  };
}

/** Aplica disponibilidad nueva (p. ej. tras cambiar la fecha) conservando la selección aún válida. */
export function withAvailability(entry, avail) {
  return {
    ...toEntry(entry, avail),
    mealCodes: entry.mealCodes.filter((c) => avail.availableCodes.includes(c)),
  };
}

export function toItem(entry) {
  return {
    employeeId: entry.type === 'EMPLOYEE' ? entry.id : null,
    externalPersonId: entry.type === 'EXTERNAL' ? entry.id : null,
    mealTypeCodes: entry.mealCodes,
  };
}

export default function TitularCard({ titular: t, meals, onToggleMeal, onRemove, tag, headerExtra, showMeals = true }) {
  // Se muestran SOLO las comidas permitidas; las ya consumidas en la fecha salen
  // deshabilitadas y marcadas.
  const allowedMeals = meals.filter((m) => (m.code === 'LUNCH' ? t.allowsSnack : t.allowsLunch));
  const isConsumed = (m) => (m.code === 'LUNCH' ? t.hadMerienda : t.hadAlmuerzo);
  const consumedMeals = allowedMeals.filter(isConsumed);

  return (
    <div style={{
      border: '1px solid var(--border, #334155)',
      borderRadius: 8, padding: '8px 10px',
      background: 'rgba(255,255,255,0.02)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <strong>{t.fullName}</strong>
          <span style={{ marginLeft: 8, fontSize: 12, color: '#94a3b8' }}>{t.identityCard}</span>
          {tag && <span style={{ marginLeft: 8, fontSize: 12, color: '#94a3b8' }}>{tag}</span>}
        </div>
        {onRemove && (
          <button type="button" className="ghost" style={{ padding: '2px 8px', fontSize: 12 }} onClick={onRemove}>
            Quitar
          </button>
        )}
      </div>
      {headerExtra}
      {showMeals && (
        <div className="row" style={{ gap: 12, marginTop: 6, flexWrap: 'wrap' }}>
          {allowedMeals.length === 0 ? (
            <span style={{ fontSize: 12, color: '#94a3b8' }}>Sin comidas habilitadas para esta persona.</span>
          ) : (
            <div style={{ width: '100%' }}>
              <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
                {allowedMeals.map((m) => {
                  const consumed = isConsumed(m);
                  return (
                    <label key={m.id} style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal', cursor: consumed ? 'not-allowed' : 'pointer', margin: 0, opacity: consumed ? 0.5 : 1 }}>
                      <input
                        type="checkbox"
                        disabled={consumed}
                        checked={!consumed && t.mealCodes.includes(m.code)}
                        onChange={(e) => onToggleMeal(m.code, e.target.checked)}
                      />
                      {m.name}{consumed ? ' (ya registrada en esta fecha)' : ''}
                    </label>
                  );
                })}
              </div>
              {consumedMeals.length > 0 && (
                <div style={{ color: 'var(--err, #ef4444)', fontSize: 13, marginTop: 6, fontWeight: 500 }}>
                  {t.fullName} ya consumió su {consumedMeals.map((m) => m.name).join(' y ')}.
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
