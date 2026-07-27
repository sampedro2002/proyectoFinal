import { useEffect, useState } from 'react';
import api from '../api/client.js';

// Aviso persistente para el admin: se queda fijo arriba de CUALQUIER pestaña
// (vive en Layout, no en una pagina puntual) hasta que el lector ZKTeco quede
// conectado y usable en el servidor. Sondea el mismo endpoint que ya usa la
// pestana Huellas de Empleados (biometric-status), asi que no requiere cambios
// de backend. Desaparece solo cuando el poll detecta readerConnected=true.
export default function BiometricReaderBanner() {
  const [readerConnected, setReaderConnected] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      try {
        const { data } = await api.get('/fingerprints/biometric-status');
        if (!cancelled) setReaderConnected(!!data.readerConnected);
      } catch {
        // Si el status no responde (backend caido, 403, etc.) no se muestra
        // el aviso: no es el problema que este componente debe reportar.
      }
    };
    poll();
    const t = setInterval(poll, 5000);
    return () => { cancelled = true; clearInterval(t); };
  }, []);

  if (readerConnected) return null;

  return (
    <div style={{
      background: 'var(--err)',
      color: '#fff',
      textAlign: 'center',
      fontWeight: 700,
      letterSpacing: '.5px',
      padding: '10px 16px',
      marginBottom: 16,
      borderRadius: 8,
    }}>
      ⚠ CONECTE EL LECTOR ZKTECO AL SERVIDOR PARA PODER USAR EL SERVICIO BIOMÉTRICO
    </div>
  );
}
