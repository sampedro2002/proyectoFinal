import { useEffect, useState } from 'react';
import api from '../api/client.js';

// Aviso de "activación" para el admin: se queda fijo arriba de CUALQUIER pestaña
// (vive en Layout, no en una pagina puntual) hasta que el lector ZKTeco se
// conecte UNA VEZ tras una instalación/reinstalación/reinicio. Usa
// readerEverConnected (no readerConnected): a diferencia del pill de la
// pestaña Huellas, este aviso NO debe reaparecer si alguien desconecta el
// lector despues durante el uso normal -- una vez detectado, se apaga para
// el resto de la sesion del backend (el propio backend resetea la bandera en
// su proximo arranque). Sondea el mismo endpoint que ya usa Empleados
// (biometric-status), asi que no requiere mas llamadas nuevas al backend.
export default function BiometricReaderBanner() {
  // null = aun no se conoce la primera respuesta (no se renderiza nada, para
  // no parpadear el aviso si el lector ya estaba conectado desde el arranque).
  const [everConnected, setEverConnected] = useState(null);

  useEffect(() => {
    if (everConnected) return; // ya se detecto una vez: no hace falta seguir sondeando
    let cancelled = false;
    const poll = async () => {
      try {
        const { data } = await api.get('/fingerprints/biometric-status');
        if (cancelled) return;
        setEverConnected(!!data.readerEverConnected);
      } catch {
        // Si el status no responde (backend caido, 403, etc.) no se muestra
        // el aviso: no es el problema que este componente debe reportar.
      }
    };
    poll();
    const t = setInterval(poll, 5000);
    return () => { cancelled = true; clearInterval(t); };
  }, [everConnected]);

  if (everConnected !== false) return null;

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
