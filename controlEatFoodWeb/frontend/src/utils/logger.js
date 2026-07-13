/**
 * Wrapper único de logging para el frontend.
 *
 * · debug/info se silencian en build de producción (Vite define
 *   import.meta.env.DEV=false) para no ensuciar la consola del navegador
 *   del operador ni exponer detalles internos.
 * · warn/error siempre se imprimen: son útiles para diagnosticar al cliente.
 * · Cada línea va prefijada con un tag `[CEF nivel]` para distinguirlo
 *   fácilmente del ruido propio de React/Vite/Axios en devtools.
 *
 * Cualquier log nuevo debe usar este wrapper, nunca `console.*` directo.
 *
 * TODO (futuro): enganchar un Sentry/OTel en `error` para producción.
 */

const isDev = import.meta.env.DEV === true;

function fmt(level, args) {
  const prefix = `%c[CEF ${level}]`;
  // style only colors the prefix label; the rest stays default.
  const styles = {
    DEBUG: 'color:#6b7280',
    INFO: 'color:#2563eb',
    WARN: 'color:#d97706',
    ERROR: 'color:#dc2626;font-weight:bold',
  };
  return [prefix, styles[level] || '', ...args];
}

export const logger = {
  debug(...args) {
    if (isDev) console.debug(...fmt('DEBUG', args));
  },
  info(...args) {
    if (isDev) console.log(...fmt('INFO', args));
  },
  warn(...args) {
    console.warn(...fmt('WARN', args));
  },
  error(...args) {
    console.error(...fmt('ERROR', args));
  },
};

export default logger;