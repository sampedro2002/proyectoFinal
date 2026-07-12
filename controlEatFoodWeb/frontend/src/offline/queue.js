import { openDB } from 'idb';

/**
 * Cola local de consumos para el modo offline.
 * Cada registro lleva un clientUuid único que garantiza idempotencia al sincronizar
 * (el backend rechaza duplicados por clientUuid y por empleado+comida+día).
 */
const DB_NAME = 'eatfood-offline';
const STORE = 'pending-scans';

async function db() {
  return openDB(DB_NAME, 1, {
    upgrade(d) {
      if (!d.objectStoreNames.contains(STORE)) {
        d.createObjectStore(STORE, { keyPath: 'clientUuid' });
      }
    }
  });
}

export function newUuid() {
  // crypto.randomUUID() solo esta disponible en "secure context" (HTTPS o
  // localhost). En una LAN aislada servida por HTTP plano (http://192.168.x.x)
  // el navegador no expone randomUUID y revienta. Se usa un polyfill basado en
  // crypto.getRandomValues (que SI esta disponible en cualquier contexto) que
  // genera un UUID v4 conforme al RFC 9562.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map((x) => x.toString(16).padStart(2, '0'));
    return `${h.slice(0, 4).join('')}-${h.slice(4, 6).join('')}-${h.slice(6, 8).join('')}-${h.slice(8, 10).join('')}-${h.slice(10, 16).join('')}`;
  }
  // Fallback definitivo (navegador muy viejo sin Web Crypto): Math.random.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export async function enqueue(record) {
  const d = await db();
  await d.put(STORE, record);
}

export async function pending() {
  const d = await db();
  return d.getAll(STORE);
}

export async function remove(clientUuid) {
  const d = await db();
  await d.delete(STORE, clientUuid);
}

export async function count() {
  const d = await db();
  return d.count(STORE);
}
