// Indicador en vivo del estado del lector ZK9500, compartido por el Kiosco y el
// panel Admin para que ambos muestren lo mismo que el pill de la app móvil
// (ui/Common.kt → ReaderStatusPill).

export const READER_STATUS = {
  CONNECTING: 'connecting',
  READY: 'ready',
  NO_DEVICE: 'no-device',
  ERROR: 'error',
  DISCONNECTED: 'disconnected',
};

// Mismas etiquetas/colores que el pill del móvil (Conectando… / ZKTeco Conectado ✓ /
// Lector no detectado / Error de Hardware / Desconectado).
const LABELS = {
  [READER_STATUS.CONNECTING]:   { text: 'Conectando…',        color: '#f59e0b' },
  [READER_STATUS.READY]:        { text: 'ZKTeco Conectado ✓', color: '#16a34a' },
  [READER_STATUS.NO_DEVICE]:    { text: 'Lector no detectado', color: '#dc2626' },
  [READER_STATUS.ERROR]:        { text: 'Error de Hardware',  color: '#dc2626' },
  [READER_STATUS.DISCONNECTED]: { text: 'Desconectado',       color: '#dc2626' },
};

/**
 * @param {string} status  Uno de READER_STATUS.
 * @param {object} [style] Estilos extra (p. ej. posición fija en el kiosco).
 */
export default function ReaderStatusPill({ status, style }) {
  const info = LABELS[status] || LABELS[READER_STATUS.DISCONNECTED];
  return (
    <div style={{
      background: 'rgba(0,0,0,.6)',
      border: `1px solid ${info.color}`,
      borderRadius: 999,
      padding: '4px 14px',
      fontSize: 13,
      color: info.color,
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      backdropFilter: 'blur(6px)',
      ...style,
    }}>
      <span style={{
        width: 8,
        height: 8,
        borderRadius: '50%',
        background: info.color,
        animation: status === READER_STATUS.CONNECTING ? 'pulse 1s infinite' : 'none',
      }} />
      {info.text}
    </div>
  );
}
