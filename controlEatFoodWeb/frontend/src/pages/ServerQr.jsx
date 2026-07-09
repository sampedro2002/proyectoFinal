import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import api from '../api/client.js';

// Estimación inicial: mismo host que el panel, puerto 3000. Suele ser 'localhost'
// en desarrollo, por eso al montar se consulta al backend su IP real de LAN.
function guessServerUrl() {
  const { protocol, hostname } = window.location;
  return `${protocol}//${hostname}:3000`;
}

export default function ServerQr() {
  const initialUrl = guessServerUrl();
  const [url, setUrl]                 = useState(initialUrl);
  const [suggestions, setSuggestions] = useState([]);
  const [dataUrl, setDataUrl]         = useState('');
  const [error, setError]             = useState('');
  const [touched, setTouched]         = useState(false);

  const trimmed = url.trim();
  const isLocalhost = /localhost|127\.0\.0\.1|10\.0\.2\.2/.test(trimmed);

  // Al montar: pedir al backend sus URLs candidatas (configurada, de la petición y LAN),
  // combinarlas por prioridad y prellenar con la mejor dirección alcanzable.
  useEffect(() => {
    api.get('/server-info')
      .then(({ data }) => {
        const configured = data?.configuredUrl || null;      // autoritativa (dominio/proxy)
        const request    = data?.requestUrl || null;          // cómo accede el admin ahora
        const lan        = Array.isArray(data?.lanUrls) ? data.lanUrls : [];
        const isLocal = (u) => !u || /localhost|127\.0\.0\.1|10\.0\.2\.2/.test(u);

        // Lista de opciones sin duplicados ni vacíos, en orden de utilidad.
        const list = [...new Set([configured, request, ...lan].filter(Boolean))];
        setSuggestions(list);

        // Auto-selección: configurada → petición pública → primera LAN → lo que haya.
        const best = configured
          || (!isLocal(request) ? request : null)
          || lan[0]
          || request
          || null;
        // No pisar lo que el admin ya haya escrito a mano mientras la petición estaba en vuelo.
        setUrl((current) => (touched && current !== initialUrl) ? current : (best || current));
      })
      .catch(() => { /* si falla, el admin escribe la URL a mano */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleUrlChange(e) {
    setTouched(true);
    setUrl(e.target.value);
  }

  // Regenerar el QR cada vez que cambia la dirección.
  useEffect(() => {
    if (!trimmed) { setDataUrl(''); setError(''); return; }
    QRCode.toDataURL(trimmed, { width: 320, margin: 2, errorCorrectionLevel: 'M' })
      .then((d) => { setDataUrl(d); setError(''); })
      .catch(() => { setDataUrl(''); setError('No se pudo generar el QR.'); });
  }, [trimmed]);

  function download() {
    if (!dataUrl) return;
    const a = document.createElement('a');
    a.href = dataUrl;
    a.download = 'servidor-qr.png';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  return (
    <div>
      <div className="topbar">
        <h2 style={{ margin: 0 }}>Conexión del dispositivo</h2>
      </div>

      <div className="grid cols-2" style={{ gap: 24, alignItems: 'start' }}>
        <div className="card">
          {suggestions.length > 0 && (
            <div className="field">
              <label>Direcciones detectadas (pública / dominio / LAN)</label>
              <select value={suggestions.includes(trimmed) ? trimmed : ''}
                      onChange={(e) => e.target.value && handleUrlChange(e)}>
                <option value="">— Elegir una dirección detectada —</option>
                {suggestions.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
          )}

          <div className="field">
            <label>Dirección del servidor (backend)</label>
            <input value={url} onChange={handleUrlChange}
                   placeholder="http://192.168.1.50:3000" />
          </div>

          {isLocalhost && (
            <p className="error-text" style={{ marginTop: 4 }}>
              ⚠ Esta dirección apunta al propio equipo (<code>{trimmed}</code>). El teléfono
              NO podrá conectarse. Elija una dirección alcanzable: IP de LAN (192.168./10./172.),
              IP pública o dominio (https://…).
            </p>
          )}

          <p style={{ color: 'var(--muted)', fontSize: 13, marginTop: 4 }}>
            Debe ser una dirección alcanzable desde la misma red Wi-Fi/LAN de los dispositivos.
            Use <code>https://</code> si el servidor tiene certificado.
          </p>

          <h4 style={{ margin: '20px 0 8px' }}>Cómo vincular el dispositivo</h4>
          <ol style={{ margin: 0, paddingLeft: 18, color: 'var(--muted)', lineHeight: 1.7 }}>
            <li>Abra la app móvil.</li>
            <li>Vaya a <strong>Configuración</strong> (⚙ Configurar servidor / lector).</li>
            <li>Pulse <strong>Escanear QR del servidor</strong> y apunte a este código.</li>
            <li>La dirección queda fija; el usuario no puede editarla.</li>
          </ol>

          <p style={{ color: 'var(--muted)', fontSize: 12, marginTop: 16 }}>
            ¿No conecta con la IP correcta? Verifique que el teléfono esté en la misma red y que
            el firewall del servidor permita el puerto {trimmed.match(/:(\d+)/)?.[1] || '3000'} entrante.
          </p>
        </div>

        <div className="card" style={{ textAlign: 'center' }}>
          {error && <p className="error-text">{error}</p>}
          {dataUrl ? (
            <>
              <img src={dataUrl} alt="QR del servidor"
                   style={{ width: 320, maxWidth: '100%', borderRadius: 8, background: '#fff', padding: 8 }} />
              <p style={{ wordBreak: 'break-all', marginTop: 12 }}><code>{trimmed}</code></p>
              <button onClick={download}>Descargar PNG</button>
            </>
          ) : (
            <p style={{ color: 'var(--muted)' }}>Escriba una dirección para generar el QR.</p>
          )}
        </div>
      </div>
    </div>
  );
}
