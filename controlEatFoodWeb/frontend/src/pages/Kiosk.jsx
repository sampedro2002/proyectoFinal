import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { ZkFingerClient, getWsUrl, setWsUrl } from '../biometric/zkfinger.js';
import { enqueue, pending, remove, newUuid } from '../offline/queue.js';
import { playSuccess, playError } from '../offline/sound.js';
import ReaderStatusPill, { READER_STATUS } from '../components/ReaderStatusPill.jsx';
import logger from '../utils/logger.js';

const SUCCESS_SECONDS = 1;
const scanApi = axios.create({ baseURL: '/api' });

export default function Kiosk() {
  const [session, setSession] = useState(() => {
    try {
      const raw = localStorage.getItem('kioskSession');
      return raw ? JSON.parse(raw) : null;
    } catch (_) {
      localStorage.removeItem('kioskSession');
      return null;
    }
  });
  const [online, setOnline] = useState(navigator.onLine);
  const [queued, setQueued] = useState(0);
  const [result, setResult] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [readerStatus, setReaderStatus] = useState(READER_STATUS.DISCONNECTED);
  const [connectError, setConnectError] = useState('');
  const [feed, setFeed] = useState([]);
  const [feedMethod, setFeedMethod] = useState('ALL'); // ALL, MANUAL, FINGERPRINT
  const [showTable, setShowTable] = useState(true);
  const [reportFormat, setReportFormat] = useState('pdf');
  const [downloading, setDownloading] = useState(false);

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
      // El backend ahora devuelve { restaurantName, entries }
      const entries = Array.isArray(data) ? data : (data.entries || []);
      const newName = Array.isArray(data) ? null : data.restaurantName;
      setFeed(entries);
      // Actualizar el nombre del restaurante si cambió
      if (newName && newName !== session.restaurantName) {
        const updated = { ...session, restaurantName: newName };
        localStorage.setItem('kioskSession', JSON.stringify(updated));
        setSession(updated);
      }
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
      const results = Array.isArray(data?.results) ? data.results : [];
      for (const r of results) {
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
          try {
            await processCapture(templateB64);
            if (ctx.active) {
              await sleep(SUCCESS_SECONDS * 1000);
              setResult(null);
            }
          } catch (err) {
            logger.error('[Kiosk] Error procesando captura:', err);
          } finally {
            // Volver a habilitar el escaneo aunque processCapture haya fallado,
            // para no dejar el kiosk "congelado" con scanning=false.
            if (ctx.active) setScanning(true);
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

    logger.debug('[SCAN] processCapture llamado', { clientUuid, consumedAt, online: navigator.onLine });

    if (navigator.onLine) {
      try {
        const { data } = await scanApi.post('/scan', {
          sessionToken: session.sessionToken,
          templateB64,
          clientUuid,
          offline: false,
          consumedAt,
        });
        // No loguear `data` en producción: incluye employeeName/mealName del empleado
        // escaneado, y el Kiosk es una pantalla de acceso físico público (F12 lo expondría).
        logger.debug('[SCAN] Respuesta del servidor:', JSON.stringify(data));
        showResult(data);
        if (data.status === 'SUCCESS') fetchFeed();
        return;
      } catch (err) {
        logger.debug('[SCAN] Error de red en /scan:', err.response?.status, err.response?.data || err.message);
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
        restaurantUsername: f.get('username'),
        restaurantPassword: f.get('password'),
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
        .catch(() => { });
    }
    localStorage.removeItem('kioskSession');
    setSession(null);
    setReaderStatus(READER_STATUS.DISCONNECTED);
    setResult(null);
  }

  async function downloadReport() {
    if (!session || downloading) return;
    setDownloading(true);
    try {
      const response = await scanApi.get('/scan/export-today', {
        params: { sessionToken: session.sessionToken, format: reportFormat },
        responseType: 'blob',
      });
      const disposition = response.headers['content-disposition'] || '';
      const match = disposition.match(/filename="?(.+?)"?$/);
      const filename = match ? match[1] : `reporte-diario.${reportFormat}`;
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      logger.error('[Kiosk] Error descargando reporte:', err);
    } finally {
      setDownloading(false);
    }
  }

  // ── RENDER: formulario de conexión ──────────────────────────────────────────
  if (!session) {
    return (
      <div className="login-wrap">
        <form className="card login-card" onSubmit={connect} style={{ width: 400 }}>
          <div className="login-brand">
            <img src="/logo.png" alt="Club Castillo Amaguaña" className="login-logo" />
            <h1 style={{ fontSize: 22, letterSpacing: 2 }}>RESTAURANTE</h1>
          </div>
          <p style={{ textAlign: 'center', color: '#94a3b8', marginTop: 0 }}>
            Conecte este dispositivo al sistema
          </p>

          <div className="field">
            <label>Usuario de restaurante</label>
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
            <input name="deviceName" placeholder={`PC-${navigator.platform || 'Restaurante'}`} />
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
                caso ingresa su IP, por ejemplo: <code>ws://192.168.1.50:3000/zkfinger-ws</code>
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

  // ── RENDER: pantalla de kiosk activa ────────────────────────────────────────
  const cls =
    result?.status === 'SUCCESS' || result?.status === 'QUEUED'
      ? 'success'
      : result
        ? 'error'
        : '';

  const totalAlmuerzos = feed.filter(e => e.mealName?.toLowerCase().includes('almuerzo')).length;
  const totalMeriendas = feed.filter(e => e.mealName?.toLowerCase().includes('merienda')).length;

  return (
    <div className={`kiosk ${cls}`}>
      {/* Pill de red */}
      <div className={`offline-pill ${online ? 'online' : ''}`}>
        {online ? 'En línea' : 'Sin conexión'}
        {queued > 0 ? ` · ${queued} en cola` : ''}
      </div>

      {/* Indicador de estado del lector (compartido con el panel Admin) */}
      <ReaderStatusPill
        status={readerStatus}
        style={{ position: 'fixed', top: 12, left: '50%', transform: 'translateX(-50%)', zIndex: 10 }}
      />

      {/* Elementos Estáticos Principales */}
      <div className="title" style={{ marginTop: '20px' }}>{session.restaurantName?.toUpperCase()}</div>
      
      <img src="/logo.png" alt="Club Castillo Amaguaña" className="fingerprint-icon" style={{
        height: '105px', // 25% smaller than original 140px
        opacity: readerStatus === READER_STATUS.READY ? 1 : 0.25,
        margin: '20px 0'
      }} />
      
      <div className="waiting" style={{ fontSize: '24px', margin: '0 0 24px 0', color: '#38bdf8' }}>
        Coloque su dedo en el lector...
      </div>



      {/* Notificación Flotante de Resultado */}
      {result && (
        <div style={{
          position: 'fixed', top: '70px', left: '50%', transform: 'translateX(-50%)',
          background: result.status === 'SUCCESS' || result.status === 'QUEUED' ? 'rgba(22, 163, 74, 0.9)' : 'rgba(220, 38, 38, 0.9)',
          color: 'white', padding: '16px 24px', borderRadius: '12px', zIndex: 50,
          boxShadow: '0 10px 25px rgba(0,0,0,0.5)', backdropFilter: 'blur(10px)',
          display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: '300px'
        }}>
          <div style={{ fontSize: '24px', fontWeight: 'bold' }}>
            {result.status === 'SUCCESS' && '✓ REGISTRO EXITOSO'}
            {result.status === 'QUEUED' && '✓ REGISTRO EN COLA'}
            {!['SUCCESS', 'QUEUED'].includes(result.status) && '✕ ' + result.message}
          </div>
          {result.employeeName && <div style={{ fontSize: '20px', marginTop: '8px' }}>{result.employeeName}</div>}
          {result.mealName && <div style={{ fontSize: '16px', opacity: 0.9 }}>{result.mealName}</div>}
        </div>
      )}

      {/* Barra de Controles (Descarga y Filtros) */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        width: '100%', maxWidth: '800px', margin: '0 auto 16px auto', padding: '0 20px'
      }}>
        {/* Descarga */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <button
            onClick={downloadReport}
            disabled={downloading}
            style={{
              background: 'var(--panel-2)', border: '1px solid var(--border)',
              borderRadius: '20px 0 0 20px', padding: '6px 16px', color: 'var(--text)',
              cursor: downloading ? 'not-allowed' : 'pointer', fontSize: 14,
              display: 'flex', alignItems: 'center', height: '36px'
            }}
            title="Descargar Reporte"
          >
            {downloading ? '⏳' : '📥'}
          </button>
          <select
            value={reportFormat}
            onChange={(e) => setReportFormat(e.target.value)}
            style={{
              background: 'var(--panel-2)', border: '1px solid var(--border)',
              borderLeft: 'none', borderRadius: '0 20px 20px 0', padding: '6px 12px',
              color: 'var(--text)', cursor: 'pointer', fontSize: 14,
              height: '36px', outline: 'none'
            }}
          >
            <option value="pdf">PDF</option>
            <option value="excel">EXCEL</option>
            <option value="csv">CSV</option>
          </select>
        </div>

        {/* Filtros Tipo */}
        <div style={{
          display: 'flex', background: 'var(--panel-2)',
          border: '1px solid var(--border)', borderRadius: '20px',
          overflow: 'hidden', height: '36px'
        }}>
          <button
            onClick={() => setFeedMethod(feedMethod === 'MANUAL' ? 'ALL' : 'MANUAL')}
            style={{
              background: feedMethod === 'MANUAL' ? 'var(--text)' : 'transparent',
              color: feedMethod === 'MANUAL' ? 'var(--bg)' : 'var(--text)',
              border: 'none', padding: '0 16px', cursor: 'pointer',
              fontSize: 13, fontWeight: 'bold', transition: 'all 0.2s'
            }}
          >
            MANUAL
          </button>
          <button
            onClick={() => setFeedMethod(feedMethod === 'FINGERPRINT' ? 'ALL' : 'FINGERPRINT')}
            style={{
              background: feedMethod === 'FINGERPRINT' ? 'var(--text)' : 'transparent',
              color: feedMethod === 'FINGERPRINT' ? 'var(--bg)' : 'var(--text)',
              border: 'none', padding: '0 16px', cursor: 'pointer',
              fontSize: 13, fontWeight: 'bold', transition: 'all 0.2s'
            }}
          >
            HUELLAS
          </button>
        </div>
      </div>

      {/* Tabla Central de Consumos */}
      <div className="kiosk-feed-container">
        <div 
          className="kiosk-feed-divider" 
          onClick={() => setShowTable(!showTable)}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { setShowTable(!showTable); e.preventDefault(); } }}
          tabIndex={0}
          role="button"
          aria-expanded={showTable}
        >
          REGISTROS DE HOY {showTable ? '▲' : '▼'}
        </div>
        
        {showTable && (() => {
          const filteredFeed = feedMethod === 'ALL' ? feed : feed.filter(e => e.method === feedMethod);
          const totalAlmuerzos = filteredFeed.filter(e => e.mealName?.toLowerCase().includes('almuerzo')).length;
          const totalMeriendas = filteredFeed.filter(e => e.mealName?.toLowerCase().includes('merienda')).length;

          return (
            <div className="kiosk-feed-table-wrapper">
              <table className="kiosk-feed-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>NOMBRE</th>
                    <th>HORA</th>
                    <th>TIPO</th>
                    <th>RETIRA</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredFeed.length === 0 ? (
                    <tr>
                      <td colSpan="5" className="kiosk-feed-empty">Sin registros aún</td>
                    </tr>
                  ) : (
                    filteredFeed.map((e, i) => (
                      <tr key={i}>
                        <td>{filteredFeed.length - i}</td>
                        <td>{e.employeeName}</td>
                        <td>{e.time}</td>
                        <td>{e.mealName}</td>
                        <td>{e.proxyEmployeeName || '—'}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
              <div className="kiosk-feed-summary">
                <div>Almuerzos: <strong>{totalAlmuerzos}</strong></div>
                <div>Meriendas: <strong>{totalMeriendas}</strong></div>
                <div>Total: <strong>{filteredFeed.length}</strong></div>
              </div>
            </div>
          );
        })()}
      </div>

      <button
        className="ghost"
        style={{ position: 'fixed', bottom: 16, right: 16, zIndex: 10 }}
        onClick={disconnect}
      >
        Desconectar
      </button>
    </div>
  );
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
