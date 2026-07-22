package com.eatfood.control.web;

import com.eatfood.control.biometric.ZkBiometricMatcher;
import com.eatfood.control.biometric.ZkfpSdk;
import com.eatfood.control.repository.FingerprintRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket Handler que emula el protocolo del agente de escritorio ZKFinger WebAPI.
 *
 * TODAS las llamadas al SDK nativo (GetDeviceCount, OpenDevice,
 * AcquireFingerprint, CloseDevice) se ejecutan en el mismo hilo del executor para
 * mantener la afinidad de hilo que exige el SDK nativo de ZKTeco.
 *
 * Soporta múltiples sesiones WebSocket simultáneas (Kiosk + Admin).
 * Solo una sesión puede capturar a la vez. El modo "register" no puede ser
 * interrumpido por solicitudes de modo "continuous" del Kiosk.
 */
@Slf4j
@Component
public class ZkFingerWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FingerprintRepository fingerprintRepository;

    @Autowired(required = false)
    private ZkBiometricMatcher zkBiometricMatcher;

    // Executor de un solo hilo: todas las operaciones nativas ocurren aquí.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> captureTask = null;

    // Seguimiento de sesiones activas para notificaciones cruzadas
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private volatile WebSocketSession activeCapturingSession = null;
    private volatile String activeMode = null;

    public ZkFingerWebSocketHandler(FingerprintRepository fingerprintRepository) {
        this.fingerprintRepository = fingerprintRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Conexión WebSocket establecida: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Mensaje WebSocket recibido: {}", payload);

        Map<String, Object> command;
        try {
            command = mapper.readValue(payload, Map.class);
        } catch (Exception e) {
            log.warn("Payload JSON inválido: {}", payload);
            return;
        }

        String cmd = (String) command.get("cmd");
        if ("open".equalsIgnoreCase(cmd)) {
            handleOpen(session);
        } else if ("capture".equalsIgnoreCase(cmd)) {
            String mode = command.getOrDefault("mode", "scan").toString();
            handleCapture(session, mode);
        } else if ("ping".equalsIgnoreCase(cmd)) {
            Map<String, Object> response = new HashMap<>();
            response.put("ret", "pong");
            session.sendMessage(new TextMessage(mapper.writeValueAsString(response)));
        } else {
            log.warn("Comando desconocido: {}", cmd);
        }
    }

    /**
     * Verifica disponibilidad del lector. La comprobación se ejecuta en el executor
     * para que quede en el mismo hilo donde se abrirá el dispositivo.
     */
    private void handleOpen(WebSocketSession session) throws IOException {
        boolean result;

        if (zkBiometricMatcher != null && zkBiometricMatcher.isReaderReady()) {
            // Si hay una tarea de captura activa, el dispositivo está en uso = disponible.
            // Someter una tarea al executor cuando está ocupado provoca un timeout de 5 s.
            if (captureTask != null && !captureTask.isDone()) {
                log.info("Dispositivo en uso (captura activa) — respondiendo open=true sin bloquear executor.");
                result = true;
            } else {
                Future<Boolean> check = executor.submit(() -> {
                    try {
                        ZkfpSdk sdk = zkBiometricMatcher.getSdk();
                        int count = sdk.ZKFPM_GetDeviceCount();
                        log.info("Dispositivos ZKFinger detectados: {}", count);
                        return count > 0;
                    } catch (Throwable t) {
                        log.error("Error al obtener conteo de dispositivos: {}", t.getMessage(), t);
                        return false;
                    }
                });
                try {
                    result = check.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Timeout o error comprobando dispositivo: {}", e.getMessage());
                    result = false;
                }
            }
        } else {
            log.warn("ZkBiometricMatcher no disponible — lector ZKTeco9500 no conectado.");
            result = false;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("ret", "open");
        response.put("result", result);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(response)));
    }

    /**
     * Inicia la captura en el hilo del executor.
     *
     * Regla de prioridad: el modo "register" (enrolamiento admin) no puede ser
     * interrumpido por solicitudes de modo "continuous" del Kiosk. Si el Kiosk intenta
     * tomar el dispositivo mientras hay un enrolamiento en curso, recibe
     * {@code capture_interrupted} y reintenta en 3 segundos. Cuando el enrolamiento
     * termina, el executor notifica automáticamente a las demás sesiones para que
     * reanuden su captura.
     */
    private synchronized void handleCapture(WebSocketSession session, String mode) {
        boolean isContinuous = "continuous".equalsIgnoreCase(mode);

        // El modo register no puede ser interrumpido por el Kiosk en modo continuous
        if (isContinuous && "register".equals(activeMode) && captureTask != null && !captureTask.isDone()) {
            log.info("Modo register activo — ignorando solicitud continuous de sesión {}. Reintentará.", session.getId());
            sendMsg(session, "capture_interrupted");
            return;
        }

        if (captureTask != null && !captureTask.isDone()) {
            captureTask.cancel(true);
            // Notificar a la sesión desplazada para que sepa que perdió el dispositivo
            if (activeCapturingSession != null
                    && !activeCapturingSession.equals(session)
                    && activeCapturingSession.isOpen()) {
                sendMsg(activeCapturingSession, "capture_interrupted");
            }
        }

        activeCapturingSession = session;
        activeMode = mode;

        captureTask = executor.submit(() -> {
            try {
                boolean registerMode = "register".equalsIgnoreCase(mode);
                if (zkBiometricMatcher != null && zkBiometricMatcher.isReaderReady()) {
                    captureWithDevice(session, mode);
                } else {
                    log.warn("Intento de captura sin lector ZKTeco9500 conectado.");
                    sendCaptureError(session);
                }
            } finally {
                // Al terminar la tarea (enrolamiento o escaneo), liberar el dispositivo y
                // notificar a las demás sesiones para que soliciten captura de nuevo.
                activeMode = null;
                if (session.equals(activeCapturingSession)) activeCapturingSession = null;
                for (WebSocketSession s : sessions) {
                    if (s.isOpen() && !s.equals(session)) {
                        sendMsg(s, "capture_interrupted");
                    }
                }
            }
        });
    }

    /** Envía un mensaje con solo el campo "ret" a una sesión. */
    private void sendMsg(WebSocketSession s, String ret) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("ret", ret);
            s.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IOException ignored) {}
    }

    /** Captura real: abre el dispositivo, enruta por modo, cierra — todo en el mismo hilo. */
    private void captureWithDevice(WebSocketSession session, String mode) {
        boolean registerMode = "register".equalsIgnoreCase(mode);
        boolean continuous = "continuous".equalsIgnoreCase(mode);
        ZkfpSdk sdk = zkBiometricMatcher.getSdk();
        Pointer hDevice = null;
        try {
            byte[] imgBuf = new byte[1024 * 1024];
            hDevice = openAndDrainDevice(sdk, imgBuf);
            log.info("Lector USB ZKFinger abierto (modo={}).", registerMode ? "register" : "scan");

            if (registerMode) {
                captureRegisterMode(session, sdk, hDevice, imgBuf);
            } else {
                captureScanMode(session, sdk, hDevice, imgBuf, continuous);
            }

        } catch (InterruptedException e) {
            log.debug("Hilo de captura interrumpido.");
        } catch (IllegalStateException e) {
            log.error(e.getMessage());
            sendCaptureError(session);
        } catch (Throwable t) {
            log.error("Error nativo en captura de huella: {}", t.getMessage(), t);
            sendCaptureError(session);
        } finally {
            if (hDevice != null) {
                try {
                    sdk.ZKFPM_CloseDevice(hDevice);
                    log.info("Lector USB cerrado.");
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Abre el dispositivo índice 0 y drena la imagen residual del sensor (stale buffer).
     * Compartido por todas las rutas de captura (WebSocket y REST) para que el hilo
     * único del executor sea el único que toca el SDK nativo.
     */
    private Pointer openAndDrainDevice(ZkfpSdk sdk, byte[] imgBuf) throws InterruptedException {
        Pointer hDevice = sdk.ZKFPM_OpenDevice(0);
        if (hDevice == null) {
            throw new IllegalStateException("ZKFPM_OpenDevice retornó null — lector no disponible.");
        }
        byte[] drainTpl = new byte[2048];
        IntByReference drainLen = new IntByReference(2048);
        if (sdk.ZKFPM_AcquireFingerprint(hDevice, imgBuf, imgBuf.length, drainTpl, drainLen) == 0) {
            log.debug("Buffer residual drenado. Esperando 800 ms...");
            Thread.sleep(800);
        }
        return hDevice;
    }

    /** Modo scan: espera y devuelve una única captura sin fusión. */
    private void captureScanMode(WebSocketSession session, ZkfpSdk sdk, Pointer hDevice, byte[] imgBuf, boolean continuous)
            throws InterruptedException {
        byte[] tplBuf = new byte[2048];
        IntByReference tplLen = new IntByReference(2048);
        log.info("Esperando huella (modo {})...", continuous ? "continuous" : "scan");
        long lastPresenceCheck = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted() && session.isOpen()) {
            tplLen.setValue(2048);
            int rc = sdk.ZKFPM_AcquireFingerprint(hDevice, imgBuf, imgBuf.length, tplBuf, tplLen);
            if (rc == 0) {
                byte[] tpl = Arrays.copyOf(tplBuf, tplLen.getValue());
                log.info("Huella capturada (longitud={}).", tpl.length);
                sendCaptureResult(session, Base64.getEncoder().encodeToString(tpl));
                lastPresenceCheck = System.currentTimeMillis();

                if (continuous) {
                    waitForFingerLift(sdk, hDevice, imgBuf);
                } else {
                    return;
                }
            } else if (rc == -28 || rc == -23 || rc == 1) {
                Thread.sleep(100);
            } else {
                log.debug("AcquireFingerprint rc={} (esperando dedo...)", rc);
                Thread.sleep(200);
            }

            // AcquireFingerprint no distingue "sin dedo" de "USB desconectado": ambos
            // devuelven un rc != 0 y el bucle seguiría reintentando para siempre, dejando
            // la sesión "activa" indefinidamente. Cada ~2s se verifica presencia real del
            // dispositivo para poder cortar el bucle y avisar al cliente si se desconectó.
            long now = System.currentTimeMillis();
            if (continuous && now - lastPresenceCheck > 2000) {
                lastPresenceCheck = now;
                if (sdk.ZKFPM_GetDeviceCount() <= 0) {
                    log.warn("Lector ZKTeco9500 ya no responde (0 dispositivos) — cortando captura continua.");
                    sendMsg(session, "capture_interrupted");
                    return;
                }
            }
        }
    }

    /**
     * Modo register: 3 capturas con levantamiento intermedio + ZKFPM_DBMerge.
     * Envía {@code capture_progress} tras cada captura (salvo la última) para que
     * el frontend pida al usuario que levante el dedo antes del siguiente intento.
     */
    private void captureRegisterMode(WebSocketSession session, ZkfpSdk sdk, Pointer hDevice, byte[] imgBuf)
            throws InterruptedException {
        byte[][] temps = acquireRegisterTemplates(session, sdk, hDevice, imgBuf, 3);
        if (temps == null) return; // hilo interrumpido o sesión cerrada
        byte[] finalTpl = mergeOrBestTemplate(temps);
        sendCaptureResult(session, Base64.getEncoder().encodeToString(finalTpl));
    }

    /**
     * Captura {@code total} plantillas con detección de levantamiento entre tomas.
     * Si {@code session} es {@code null} (captura REST desde servidor, sin sesión
     * WebSocket asociada) omite los envíos de progreso y solo revisa la interrupción
     * del hilo. Compartida por la captura vía WebSocket y la captura REST para no
     * duplicar el algoritmo de registro (3 tomas + fusión) en dos sitios.
     */
    private byte[][] acquireRegisterTemplates(WebSocketSession session, ZkfpSdk sdk, Pointer hDevice, byte[] imgBuf, int total)
            throws InterruptedException {
        byte[][] temps = new byte[total][];

        for (int step = 1; step <= total; step++) {
            if (Thread.currentThread().isInterrupted() || (session != null && !session.isOpen())) return null;

            log.info("Registro: esperando captura {}/{}...", step, total);
            byte[] tplBuf = new byte[2048];
            IntByReference tplLen = new IntByReference(2048);

            while (!Thread.currentThread().isInterrupted() && (session == null || session.isOpen())) {
                tplLen.setValue(2048);
                int rc = sdk.ZKFPM_AcquireFingerprint(hDevice, imgBuf, imgBuf.length, tplBuf, tplLen);
                if (rc == 0) {
                    temps[step - 1] = Arrays.copyOf(tplBuf, tplLen.getValue());
                    log.info("Captura {}/{} exitosa (longitud={}).", step, total, temps[step - 1].length);
                    break;
                } else if (rc == -28 || rc == -23 || rc == 1) {
                    Thread.sleep(100);
                } else {
                    Thread.sleep(200);
                }
            }

            if (temps[step - 1] == null) return null; // hilo interrumpido

            if (step < total) {
                if (session != null) sendCaptureProgress(session, step, total);
                waitForFingerLift(sdk, hDevice, imgBuf);
            }
        }

        return temps;
    }

    /** Espera hasta que el sensor deje de detectar el dedo (máx. 6 s de fallback). */
    private void waitForFingerLift(ZkfpSdk sdk, Pointer hDevice, byte[] imgBuf) throws InterruptedException {
        byte[] dummy = new byte[2048];
        IntByReference dummyLen = new IntByReference(2048);
        for (int i = 0; i < 60 && !Thread.currentThread().isInterrupted(); i++) {
            dummyLen.setValue(2048);
            int rc = sdk.ZKFPM_AcquireFingerprint(hDevice, imgBuf, imgBuf.length, dummy, dummyLen);
            if (rc != 0) {
                Thread.sleep(300);
                return;
            }
            Thread.sleep(100);
        }
        Thread.sleep(300);
    }

    /**
     * Fusiona 3 plantillas con ZKFPM_DBMerge, o usa la mejor captura individual como
     * fallback si la fusión falla.
     */
    private byte[] mergeOrBestTemplate(byte[][] temps) {
        if (zkBiometricMatcher != null && zkBiometricMatcher.isReady()) {
            byte[] finalTpl = zkBiometricMatcher.mergeTemplates(temps[0], temps[1], temps[2]);
            if (finalTpl != null) return finalTpl;
            log.warn("ZKFPM_DBMerge falló. Usando plantilla individual como fallback.");
        }
        byte[] best = temps[0];
        for (byte[] t : temps) { if (t != null && t.length > best.length) best = t; }
        log.info("Fallback register: plantilla individual (longitud={}).", best.length);
        return best;
    }

    /**
     * Captura una huella de registro (3 tomas + fusión) para el endpoint REST de
     * enrolamiento desde servidor. Comparte el mismo executor de un solo hilo y el
     * mismo bloqueo de dispositivo ({@code activeMode}/{@code captureTask}) que usan
     * las sesiones WebSocket del Kiosk/Admin, para evitar que dos hilos abran el
     * dispositivo ZK9500 al mismo tiempo (el SDK nativo exige afinidad de hilo — ver
     * cabecera de la clase). Mientras esta captura está en curso, las solicitudes
     * "continuous" del Kiosk se rechazan con {@code capture_interrupted} igual que
     * durante un enrolamiento admin vía WebSocket.
     */
    public synchronized CompletableFuture<byte[]> captureForServerEnroll() {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        if (zkBiometricMatcher == null || !zkBiometricMatcher.isReaderReady()) {
            result.completeExceptionally(new IllegalStateException("Lector ZK9500 no disponible en el servidor"));
            return result;
        }

        if (captureTask != null && !captureTask.isDone()) {
            captureTask.cancel(true);
            if (activeCapturingSession != null && activeCapturingSession.isOpen()) {
                sendMsg(activeCapturingSession, "capture_interrupted");
            }
        }

        activeCapturingSession = null;
        activeMode = "register";

        captureTask = executor.submit(() -> {
            ZkfpSdk sdk = zkBiometricMatcher.getSdk();
            Pointer hDevice = null;
            try {
                byte[] imgBuf = new byte[1024 * 1024];
                hDevice = openAndDrainDevice(sdk, imgBuf);
                log.info("Lector USB ZKFinger abierto para enrolamiento desde servidor.");
                byte[][] temps = acquireRegisterTemplates(null, sdk, hDevice, imgBuf, 3);
                if (temps == null) {
                    result.completeExceptionally(new InterruptedException("Captura de huella interrumpida"));
                    return;
                }
                result.complete(mergeOrBestTemplate(temps));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            } finally {
                if (hDevice != null) {
                    try { sdk.ZKFPM_CloseDevice(hDevice); log.info("Lector USB cerrado tras enrolamiento servidor."); } catch (Throwable ignored) {}
                }
                activeMode = null;
                for (WebSocketSession s : sessions) {
                    if (s.isOpen()) sendMsg(s, "capture_interrupted");
                }
            }
        });
        return result;
    }

    public boolean isReaderReady() {
        return zkBiometricMatcher != null && zkBiometricMatcher.isReaderReady();
    }

    private void sendCaptureProgress(WebSocketSession session, int step, int total) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("ret", "capture_progress");
            msg.put("step", step);
            msg.put("total", total);
            session.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.debug("No se pudo enviar progreso de captura: {}", e.getMessage());
        }
    }

    private void sendCaptureResult(WebSocketSession session, String templateB64) {
        try {
            Map<String, Object> resp = new HashMap<>();
            resp.put("ret", "capture");
            resp.put("result", true);
            resp.put("template", templateB64);
            session.sendMessage(new TextMessage(mapper.writeValueAsString(resp)));
        } catch (IOException e) {
            log.debug("No se pudo enviar resultado de captura: {}", e.getMessage());
        }
    }

    private void sendCaptureError(WebSocketSession session) {
        try {
            Map<String, Object> resp = new HashMap<>();
            resp.put("ret", "capture");
            resp.put("result", false);
            session.sendMessage(new TextMessage(mapper.writeValueAsString(resp)));
        } catch (IOException e) {
            log.error("No se pudo enviar error de captura: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Conexión WebSocket cerrada: {} ({})", session.getId(), status);

        // Solo cancelar la tarea si la sesión que cierra es la propietaria.
        // Si el Kiosk cierra su WS mientras el Admin está enrolando, no interrumpir el enrolamiento.
        if (session.equals(activeCapturingSession)) {
            if (captureTask != null && !captureTask.isDone()) {
                captureTask.cancel(true);
            }
            activeCapturingSession = null;
            activeMode = null;
            // Notificar a las sesiones restantes para que soliciten captura de nuevo
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    sendMsg(s, "capture_interrupted");
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }
}
