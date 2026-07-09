/**
 * Cliente del lector biométrico ZK9500.
 *
 * El AGENTE BIOMÉTRICO ESTÁ EMBEBIDO EN EL BACKEND de este mismo proyecto
 * (ZkFingerWebSocketHandler, expuesto en /zkfinger-ws). El backend controla el lector
 * USB ZK9500 directamente vía JNA -> libzkfp, así que NO se requiere instalar ningún
 * programa externo de ZKTeco (ZKFinger WebAPI / WebSDK).
 *
 * Este módulo encapsula la captura de la PLANTILLA (template, base64) que luego se
 * envía al backend para la identificación 1:N.
 *
 * Protocolo (WebSocket JSON, mismo que el agente embebido):
 *   -> { cmd: "open" }                       abre el dispositivo
 *   <- { ret: "open", result: true }
 *   -> { cmd: "capture", fakeData: false }   solicita una captura
 *   <- { ret: "capture", result: true, template: "<base64>", image: "..." }
 *
 * Resolución de URL (de mayor a menor prioridad):
 *   1. localStorage('zkfingerWsUrl') — override manual desde el Kiosk (p. ej. lector en otro PC).
 *   2. VITE_ZKFINGER_WS — override por entorno (normalmente vacío).
 *   3. Agente embebido del backend, derivado del origen de la página: funciona igual en
 *      desarrollo (proxy de Vite hacia :3000) y en producción (mismo host / reverse proxy).
 */

/**
 * URL del agente biométrico EMBEBIDO en el backend, derivada del origen actual.
 * Evita depender de cualquier programa externo y funciona en dev y producción.
 */
function embeddedAgentUrl() {
  if (typeof window === 'undefined' || !window.location) {
    return 'ws://localhost:3000/zkfinger-ws';
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/zkfinger-ws`;
}

/** Devuelve la URL WebSocket a usar (override manual > env > agente embebido). */
export function getWsUrl() {
  return (
    localStorage.getItem('zkfingerWsUrl') ||
    import.meta.env.VITE_ZKFINGER_WS ||
    embeddedAgentUrl()
  );
}

/** Guarda una URL personalizada del agente WebSocket. */
export function setWsUrl(url) {
  if (url && url.trim()) {
    localStorage.setItem('zkfingerWsUrl', url.trim());
  } else {
    localStorage.removeItem('zkfingerWsUrl');
  }
}

export class ZkFingerClient {
  constructor({ onStatus, onProgress, wsUrl, getAccessToken } = {}) {
    this.ws = null;
    this.ready = false;
    this.onStatus = onStatus || (() => {});
    this.onProgress = onProgress || (() => {});
    this.onCapture = null;
    this._pendingCapture = null;
    // El accessToken ya no vive en localStorage (ver api/client.js): quien necesite
    // autenticar el WebSocket como admin debe pasar un getter explícito. Sin él, se
    // conecta sin JWT (ruta del Kiosk, que se autentica con deviceToken).
    this.getAccessToken = getAccessToken || (() => '');
    // Permite sobrescribir la URL por instancia; si no, usa la guardada/default
    this.wsUrl = wsUrl || getWsUrl();
    this.heartbeatTimer = null;
    // Cola FIFO para serializar las capturas en modo continuo: evita que dos
    // plantillas lleguen casi simultáneas y se procesen en paralelo (lo que
    // causaría registros dobles / parpadeos en el Kiosk).
    this._captureQueue = [];
    this._captureProcessing = false;
  }

  async connect() {
    return new Promise((resolve, reject) => {
      try {
        const token = this.getAccessToken() || '';
        let devToken = '';
        try {
          const session = JSON.parse(localStorage.getItem('kioskSession'));
          if (session && session.sessionToken) {
            devToken = session.sessionToken;
          }
        } catch (_) {}

        let url = this.wsUrl;
        const separator = url.includes('?') ? '&' : '?';
        url = `${url}${separator}token=${encodeURIComponent(token)}`;
        if (devToken) {
          url = `${url}&deviceToken=${encodeURIComponent(devToken)}`;
        }

        this.ws = new WebSocket(url);
      } catch (e) { return reject(e); }

      const timeout = setTimeout(() => {
        if (!this.ready) {
          reject(new Error('No se pudo conectar al agente ZKFinger en ' + this.wsUrl));
          // Cerrar el ws para no dejar la conexión colgada tras el timeout.
          try { this.ws && this.ws.close(); } catch (_) {}
        }
      }, 5000);

      this.ws.onopen = () => {
        this.send({ cmd: 'open' });
        // El heartbeat arranca cuando el dispositivo responde 'open' exitosamente
        // (ver parseMessage), no aquí, para evitar latidos sobre una sesión aún no abierta.
      };
      this.ws.onerror = () => {
        clearTimeout(timeout);
        this.onStatus('error');
        reject(new Error('Error de WebSocket: ¿Está el agente ZKFinger ejecutándose en ' + this.wsUrl + '?'));
      };
      this.ws.onclose = () => {
        this.ready = false;
        this.onStatus('disconnected');
        this.stopHeartbeat();
      };
      this.ws.onmessage = (ev) => this.parseMessage(ev.data, resolve, () => clearTimeout(timeout));
    });
  }

  parseMessage(data, resolveConnect, onResolved) {
    let msg;
    try { msg = JSON.parse(data); } catch { console.warn('[ZkFingerClient] Mensaje no-JSON ignorado:', data); return; }

    if (msg.ret === 'open') {
      this.ready = !!msg.result;
      this.onStatus(this.ready ? 'ready' : 'no-device');
      if (this.ready) this.startHeartbeat();
      if (onResolved) onResolved();
      if (resolveConnect) resolveConnect();
    }
    if (msg.ret === 'capture_interrupted') {
      // El backend tomó el dispositivo para otra sesión (p.ej. enrolamiento admin)
      // o lo liberó. Marcar como no listo para que el runLoop del Kiosk reconecte.
      this.ready = false;
      this.onStatus('disconnected');
      if (this._pendingCapture) {
        const { reject } = this._pendingCapture;
        this._pendingCapture = null;
        reject(new Error('Captura interrumpida por otra sesión'));
      }
      return;
    }
    if (msg.ret === 'capture_progress') {
      this.onProgress(msg.step, msg.total);
      return;
    }
    if (msg.ret === 'capture' && msg.template) {
      const template = msg.template;
      console.log('[ZkFingerClient] Mensaje capture recibido, template present:', !!template);
      if (this.onCapture) {
        // Encolar y procesar de a una para evitar capturas paralelas en el Kiosk.
        this._captureQueue.push(template);
        this._drainCaptureQueue();
      } else if (this._pendingCapture) {
        const { resolve, timer } = this._pendingCapture;
        this._pendingCapture = null;
        if (timer) clearTimeout(timer);
        console.log('[ZkFingerClient] Resolviendo promesa pendiente...');
        resolve(template);
      } else {
        console.warn('[ZkFingerClient] Se recibió captura pero no había promesa pendiente (posible timeout previo).');
      }
    }
  }

  /**
   * Procesa la cola de capturas en modo continuo, una a la vez, invocando
   * `onCapture` y esperando a que termine antes de despachar la siguiente.
   */
  async _drainCaptureQueue() {
    if (this._captureProcessing) return;
    if (!this.onCapture) return;
    const next = this._captureQueue.shift();
    if (!next) return;
    this._captureProcessing = true;
    try {
      await this.onCapture(next);
    } catch (e) {
      console.error('[ZkFingerClient] Error en onCapture:', e);
    } finally {
      this._captureProcessing = false;
      // Si quedan más capturas encoladas, seguir drenando.
      if (this._captureQueue.length > 0) this._drainCaptureQueue();
    }
  }

  send(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }

  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send({ cmd: 'ping' });
      }
    }, 30000); // 30 segundos
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * Espera una captura del lector y devuelve la plantilla en base64.
   * @param {number} timeoutMs  Máximo de espera en ms (default 15000).
   * @param {'scan'|'register'} mode  'register' activa post-drain en el backend.
   */
  capture(timeoutMs = 15000, mode = 'scan') {
    return new Promise((resolve, reject) => {
      if (!this.ready) return reject(new Error('Lector no disponible'));
      const timer = setTimeout(() => {
        if (this._pendingCapture) {
          this._pendingCapture = null;
          reject(new Error('Tiempo de espera agotado'));
        }
      }, timeoutMs);
      this._pendingCapture = { resolve, reject, timer };
      this.send({ cmd: 'capture', mode, fakeData: false });
    });
  }

  close() {
    this.stopHeartbeat();
    if (this.ws) {
      // Nular handlers antes de cerrar para que eventos residuales (onclose/onerror)
      // no disparen callbacks sobre un cliente ya cerrado.
      this.ws.onopen = null;
      this.ws.onerror = null;
      this.ws.onclose = null;
      this.ws.onmessage = null;
      try { this.ws.close(); } catch (_) {}
      this.ws = null;
    }
    this.ready = false;
    this._pendingCapture = null;
    this._captureQueue = [];
    this._captureProcessing = false;
  }
}
