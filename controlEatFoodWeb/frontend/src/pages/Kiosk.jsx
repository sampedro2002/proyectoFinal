import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { ZkFingerClient, isSimulated, getWsUrl, setWsUrl } from '../biometric/zkfinger.js';
import { enqueue, pending, remove, newUuid } from '../offline/queue.js';
import { playSuccess, playError } from '../offline/sound.js';

const SUCCESS_SECONDS = 10;
const scanApi = axios.create({ baseURL: '/api' });

// ──────────────────────────────────────────────────────────────────────────────
// ESTADOS DEL LECTOR
// ──────────────────────────────────────────────────────────────────────────────
const READER_STATUS = {
  CONNECTING: 'connecting',
  READY: 'ready',
  NO_DEVICE: 'no-device',
  ERROR: 'error',
  DISCONNECTED: 'disconnected',
  SIM: 'ready-sim',
};

const readerStatusLabel = {
  [READER_STATUS.CONNECTING]:   { text: 'Conectando lector…',         color: '#f59e0b' },
  [READER_STATUS.READY]:        { text: 'Lector listo ✓',              color: '#16a34a' },
  [READER_STATUS.NO_DEVICE]:    { text: 'Lector no detectado',         color: '#dc2626' },
  [READER_STATUS.ERROR]:        { text: 'Error de conexión al agente', color: '#dc2626' },
  [READER_STATUS.DISCONNECTED]: { text: 'Agente desconectado',         color: '#dc2626' },
  [READER_STATUS.SIM]:          { text: 'Modo SIMULADO (sin ZK9500)',  color: '#8b5cf6' },
};

export default function Kiosk() {
  const [session, setSession] = useState(() => {
    const raw = localStorage.getItem('kioskSession');
    return raw ? JSON.parse(raw) : null;
  });
  const [online, setOnline] = useState(navigator.onLine);
  const [queued, setQueued] = useState(0);
  const [result, setResult] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [readerStatus, setReaderStatus] = useState(READER_STATUS.DISCONNECTED);
  const [connectError, setConnectError] = useState('');
  const [feed, setFeed] = useState([]);

  const clientRef = useRef(null);

  // ── Red ────────────────────────────────────────────────────────────────────
  useEffect(() => {
    const up = () => { setOnline(true); syncQueue(); };
    const down = () => setOnline(false);
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    const t = setInterval(() => { refreshQueued(); if (navigator.onLine) syncQueue(); }, 15000);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
      clearInterval(t);
    };
  }, [session]);

  const refreshQueued = useCallback(
    async () => setQueued(await pending().then((p) => p.length)),
    []
  );

  // ── Feed de consumos del día ────────────────────────────────────────────────
  const fetchFeed = useCallback(async () => {
    if (!session) return;
    try {
      const { data } = await scanApi.get('/scan/today', {
        params: { sessionToken: session.sessionToken },
      });
      setFeed(data);
    } catch (err) {
      if (err.response?.data?.code === 'INVALID_SESSION') {
        localStorage.removeItem('kioskSession');
        setSession(null);
      }
      // otros errores: no-crítico, la pantalla sigue funcionando
    }
  }, [session]);

  useEffect(() => {
    if (!session) return;
    fetchFeed();
    const t = setInterval(fetchFeed, 10000);
    return () => clearInterval(t);
  }, [session, fetchFeed]);

  // ── Cola offline ────────────────────────────────────────────────────────────
  const syncQueue = useCallback(async () => {
    if (!session) return;
    const items = await pending();
    if (items.length === 0) return;
    try {
      const records = items.map((i) => ({
        templateB64: i.templateB64,
        mealTypeCode: i.mealTypeCode || null,
        clientUuid: i.clientUuid,
        offline: true,
        consumedAt: i.consumedAt,
      }));
      const { data } = await scanApi.post('/scan/sync', {
        sessionToken: session.sessionToken,
        records,
      });
      for (const r of data.results) {
        if (r.status !== 'ERROR') await remove(r.clientUuid);
      }
      refreshQueued();
    } catch {
      /* sin conexión: se reintenta luego */
    }
  }, [session, refreshQueued]);

  useEffect(() => { refreshQueued(); }, [refreshQueued]);

  // ── Bucle de captura ────────────────────────────────────────────────────────
  useEffect(() => {
    if (!session) return;
    const ctx = { active: true };
    runLoop(ctx);
    return () => {
      ctx.active = false;
      if (clientRef.current) clientRef.current.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  async function runLoop(ctx) {
    while (ctx.active) {
      // Intento conectar / reconectar el agente ZKFinger
      setReaderStatus(READER_STATUS.CONNECTING);
      const client = new ZkFingerClient({
        onStatus: (s) => {
          if (ctx.active) setReaderStatus(s);
        },
      });
      clientRef.current = client;

      try {
        await client.connect();
      } catch (e) {
        if (ctx.active) setReaderStatus(READER_STATUS.ERROR);
        // Espera 5 s antes de reintentar la conexión al agente
        await sleep(5000);
        if (!ctx.active) return;
        continue;
      }

      // Bucle de capturas mientras el lector esté disponible
      if (ctx.active && client.ready) {
        if (ctx.active) setScanning(true);
        // Configuramos para escuchar múltiples capturas sin cerrar
        client.onCapture = async (templateB64) => {
          if (!ctx.active) return;
          setScanning(false);
          await processCapture(templateB64);
          if (ctx.active) {
            await sleep(SUCCESS_SECONDS * 1000);
            setResult(null);
            setScanning(true);
          }
        };
        try {
          // Enviar el comando continuous al backend (que no resolverá la promesa, usará onCapture)
          client.send({ cmd: 'capture', mode: 'continuous', fakeData: false });
          // Esperamos indefinidamente hasta que ctx.active sea false o se cierre el ws
          while (ctx.active && client.ready) {
            await sleep(1000);
          }
        } catch {
          if (ctx.active) setScanning(false);
        }
      }

      client.close();
      if (!ctx.active) return;
      // Espera antes de reintentar conexión con el agente
      await sleep(3000);
    }
  }

  async function processCapture(templateB64) {
    const clientUuid = newUuid();
    const consumedAt = new Date().toISOString();

    console.log('[SCAN] processCapture llamado', { clientUuid, consumedAt, online: navigator.onLine });

    if (navigator.onLine) {
      try {
        const { data } = await scanApi.post('/scan', {
          sessionToken: session.sessionToken,
          templateB64,
          clientUuid,
          offline: false,
          consumedAt,
        });
        console.log('[SCAN] Respuesta del servidor:', JSON.stringify(data));
        showResult(data);
        if (data.status === 'SUCCESS') fetchFeed();
        return;
      } catch (err) {
        console.error('[SCAN] Error de red en /scan:', err.response?.status, err.response?.data || err.message);
        if (err.response?.data?.code === 'INVALID_SESSION') {
          // Sesión expirada o invalidada (ej. reinicio del backend con sesión antigua)
          localStorage.removeItem('kioskSession');
          setSession(null);
          return;
        }
        /* Otras fallas de red → degradar a offline */
      }
    }
    await enqueue({ clientUuid, templateB64, consumedAt });
    refreshQueued();
    showResult({ status: 'QUEUED', message: 'REGISTRO EN COLA (OFFLINE)', time: consumedAt });
  }

  function showResult(data) {
    setResult(data);
    if (data.status === 'SUCCESS' || data.status === 'QUEUED') playSuccess();
    else playError();
  }

  // ── Conexión del dispositivo ────────────────────────────────────────────────
  async function connect(e) {
    e.preventDefault();
    setConnectError('');
    const f = new FormData(e.target);
    let deviceUid = localStorage.getItem('deviceUid');
    if (!deviceUid) {
      deviceUid = newUuid();
      localStorage.setItem('deviceUid', deviceUid);
    }

    // Guarda el override del agente; si el campo queda vacío, vuelve al agente embebido.
    setWsUrl((f.get('agentUrl') || '').trim());

    try {
      const { data } = await scanApi.post('/scan/connect', {
        cateringUsername: f.get('username'),
        cateringPassword: f.get('password'),
        deviceUid,
        deviceName: f.get('deviceName') || navigator.userAgent.slice(0, 60),
      });
      localStorage.setItem('kioskSession', JSON.stringify(data));
      setSession(data);
    } catch (err) {
      setConnectError(err.response?.data?.message || 'No se pudo conectar el dispositivo');
    }
  }

  function disconnect() {
    if (clientRef.current) clientRef.current.close();
    if (session) {
      scanApi
        .post('/scan/disconnect', null, { params: { sessionToken: session.sessionToken } })
        .catch(() => {});
    }
    localStorage.removeItem('kioskSession');
    setSession(null);
    setReaderStatus(READER_STATUS.DISCONNECTED);
    setResult(null);
  }

  // ── RENDER: formulario de conexión ──────────────────────────────────────────
  if (!session) {
    return (
      <div className="login-wrap">
        <form className="card login-card" onSubmit={connect} style={{ width: 400 }}>
          <h1 style={{ fontSize: 22, letterSpacing: 2 }}>🖐 CATERING</h1>
          <p style={{ textAlign: 'center', color: '#94a3b8', marginTop: 0 }}>
            Conecte este dispositivo al sistema
          </p>

          <div className="field">
            <label>Usuario de catering</label>
            <input name="username" required autoFocus autoComplete="username" />
          </div>

          <div className="field">
            <label>Contraseña</label>
            <div className="password-container">
              <input
                name="password"
                type={showPassword ? 'text' : 'password'}
                required
                autoComplete="current-password"
              />
              <button
                type="button"
                className="password-toggle"
                onClick={() => setShowPassword(!showPassword)}
                title={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
              >
                {showPassword ? '👁️' : '🙈'}
              </button>
            </div>
          </div>

          <div className="field">
            <label>Nombre del dispositivo (opcional)</label>
            <input name="deviceName" placeholder={`PC-${navigator.platform || 'Catering'}`} />
          </div>

          {/* URL del agente ZKFinger – configurable para cada PC / entorno */}
          <details style={{ marginBottom: 12 }}>
            <summary style={{ cursor: 'pointer', color: '#94a3b8', fontSize: 13, userSelect: 'none' }}>
              ⚙️ Configuración avanzada del lector
            </summary>
            <div className="field" style={{ marginTop: 8 }}>
              <label>URL del agente biométrico (WebSocket)</label>
              <input
                name="agentUrl"
                defaultValue={localStorage.getItem('zkfingerWsUrl') || ''}
                placeholder={getWsUrl()}
              />
              <span style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
                Déjalo vacío para usar el agente integrado del servidor (recomendado).
                Solo cambia esto si el lector está conectado a OTRO PC de la red; en ese
                caso ingresa su IP, por ejemplo: <code>ws://192.168.1.50:8080/zkfinger-ws</code>
              </span>
            </div>
          </details>

          {connectError && <p className="error-text" style={{ textAlign: 'center' }}>{connectError}</p>}

          <button type="submit" style={{ width: '100%' }}>Conectar</button>

          <div style={{
            marginTop: 16, padding: 12, background: 'rgba(255,255,255,.04)',
            borderRadius: 8, fontSize: 12, color: '#64748b', lineHeight: 1.6
          }}>
            <strong style={{ color: '#94a3b8' }}>ℹ️ Requisito del lector ZK9500</strong><br />
            El agente biométrico está integrado en el servidor (no se instala ningún
            programa externo). Solo conecta el lector USB ZK9500 al PC donde corre el
            backend y abre esta página desde ese mismo equipo. Los celulares no pueden
            usar el lector ZK9500 directamente.
          </div>
        </form>
      </div>
    );
  }

  // ── RENDER: panel lateral de consumos del día ───────────────────────────────
  const feedPanel = session && (
    <div className="kiosk-feed">
      <div className="kiosk-feed-header">
        <span>Hoy — {feed.length} {feed.length === 1 ? 'comensal' : 'comensales'}</span>
      </div>
      {feed.length === 0 ? (
        <span className="kiosk-feed-empty">Sin registros aún</span>
      ) : (
        feed.map((e, i) => (
          <div key={i} className="kiosk-feed-item">
            <span className="kiosk-feed-name">{e.employeeName}</span>
            <span className="kiosk-feed-detail">{e.mealName} · {e.time}</span>
          </div>
        ))
      )}
    </div>
  );

  // ── RENDER: pantalla de kiosk activa ────────────────────────────────────────
  const cls =
    result?.status === 'SUCCESS' || result?.status === 'QUEUED'
      ? 'success'
      : result
      ? 'error'
      : '';

  const rInfo = readerStatusLabel[readerStatus] || readerStatusLabel[READER_STATUS.DISCONNECTED];

  return (
    <div className={`kiosk ${cls}`}>
      {feedPanel}
      {/* Pill de red */}
      <div className={`offline-pill ${online ? 'online' : ''}`}>
        {online ? 'En línea' : 'Sin conexión'}
        {queued > 0 ? ` · ${queued} en cola` : ''}
      </div>

      {/* Indicador de estado del lector */}
      {!result && (
        <div style={{
          position: 'fixed', top: 12, left: '50%', transform: 'translateX(-50%)',
          background: 'rgba(0,0,0,.6)', border: `1px solid ${rInfo.color}`,
          borderRadius: 999, padding: '4px 14px', fontSize: 13,
          color: rInfo.color, display: 'flex', alignItems: 'center', gap: 6,
          backdropFilter: 'blur(6px)', zIndex: 10,
        }}>
          <span style={{
            width: 8, height: 8, borderRadius: '50%',
            background: rInfo.color,
            animation: readerStatus === READER_STATUS.CONNECTING ? 'pulse 1s infinite' : 'none'
          }} />
          {rInfo.text}
        </div>
      )}

      {!result && (
        <>
          <div className="title">{session.cateringName?.toUpperCase()}</div>
          <div className="fingerprint-icon" style={{
            opacity: readerStatus === READER_STATUS.READY || readerStatus === READER_STATUS.SIM ? 1 : 0.25
          }}>🖐️</div>
          <div className="waiting">
            {readerStatus === READER_STATUS.CONNECTING && 'Conectando lector…'}
            {readerStatus === READER_STATUS.ERROR && 'No se puede conectar al agente ZKFinger'}
            {readerStatus === READER_STATUS.NO_DEVICE && 'Conecte el lector al USB'}
            {readerStatus === READER_STATUS.DISCONNECTED && 'Reconectando…'}
            {(readerStatus === READER_STATUS.READY || readerStatus === READER_STATUS.SIM) &&
              (scanning ? 'Coloque el dedo…' : 'Esperando huella…')}
          </div>
          {(readerStatus === READER_STATUS.ERROR || readerStatus === READER_STATUS.DISCONNECTED) && (
            <p style={{ color: '#94a3b8', fontSize: 14, maxWidth: 380, textAlign: 'center' }}>
              Verifique que el agente <strong>ZKFinger WebAPI</strong> esté en ejecución en{' '}
              <code style={{ color: '#38bdf8' }}>{getWsUrl()}</code>.
              Se intentará reconectar automáticamente.
            </p>
          )}
          <button
            className="ghost"
            style={{ position: 'fixed', bottom: 16, right: 16 }}
            onClick={disconnect}
          >
            Desconectar
          </button>
        </>
      )}

      {result && (
        <div>
          <div className="big-status">
            {result.status === 'SUCCESS' && '✓ REGISTRO EXITOSO'}
            {result.status === 'QUEUED' && '✓ REGISTRO EN COLA'}
            {!['SUCCESS', 'QUEUED'].includes(result.status) && '✕ ' + result.message}
          </div>
          {result.employeeName && <div className="name">{result.employeeName}</div>}
          {result.mealName && <div className="detail">{result.mealName}</div>}
          {result.time && <div className="detail">{new Date(result.time).toLocaleTimeString()}</div>}
          {result.status === 'QUEUED' && (
            <div className="detail">Se sincronizará al recuperar la conexión.</div>
          )}
        </div>
      )}
    </div>
  );
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
