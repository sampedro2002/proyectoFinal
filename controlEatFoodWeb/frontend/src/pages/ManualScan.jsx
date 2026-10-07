import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import api from '../api/client.js';
import InternalForm from '../components/manual/InternalForm.jsx';
import ExternalForm from '../components/manual/ExternalForm.jsx';

/**
 * Registro manual de consumo, en dos pestañas (?tab=interno|externa):
 *   - Registro interno: formulario único para comida propia y hasta 10 titulares
 *     por día, con registro de emergencia opcional. El backend crea una fila de consumption por (titular x comida) con
 *     method='MANUAL'; si quien retira es también titular, su consumo va sin
 *     apoderado ("Retira personalmente").
 *   - Persona externa: visitante / contratista con cédula o pasaporte,
 *     method='EXTERNAL', con "Registro de emergencia" opcional.
 * Cada pestaña se monta con key propia: cambiar de pestaña o "Limpiar" reinicia el formulario.
 */
const TABS = [
  { id: 'interno', label: 'Registro interno' },
  { id: 'externa', label: 'Persona externa' },
];

export default function ManualScan() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'externa' ? 'externa' : 'interno';
  const [resetKey, setResetKey] = useState(0);
  const [restaurants, setRestaurants] = useState([]);
  const [meals, setMeals] = useState([]);
  const [restaurantId, setRestaurantId] = useState('');

  useEffect(() => {
    api.get('/restaurants').then((r) => {
      setRestaurants(r.data);
      if (r.data.length > 0) setRestaurantId(r.data[0].id);
    }).catch(() => {});
    api.get('/meal-types').then((r) => setMeals(r.data)).catch(() => {});
  }, []);

  const shared = { meals, restaurants, restaurantId, setRestaurantId };

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Registro manual de consumo</h2>
      </div>

      <div className="card" style={{ maxWidth: 640 }}>
        <div className="row" style={{ marginBottom: 16, gap: 8 }}>
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              className={tab === t.id ? '' : 'ghost'}
              onClick={() => setParams({ tab: t.id }, { replace: true })}
              style={{ flex: 1 }}
            >
              {t.label}
            </button>
          ))}
        </div>

        {tab === 'interno' ? (
          <InternalForm key={`interno-${resetKey}`} {...shared} />
        ) : (
          <ExternalForm key={`externa-${resetKey}`} {...shared}
            onReset={() => setResetKey((k) => k + 1)} />
        )}
      </div>
    </div>
  );
}
