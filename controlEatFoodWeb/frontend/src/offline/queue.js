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
  return crypto.randomUUID();
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
