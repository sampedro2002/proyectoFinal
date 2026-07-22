package com.eatfood.control.service;

import com.eatfood.control.web.ZkFingerWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class FingerprintCaptureService {

    private final ZkFingerWebSocketHandler zkFingerWebSocketHandler;

    public FingerprintCaptureService(ZkFingerWebSocketHandler zkFingerWebSocketHandler) {
        this.zkFingerWebSocketHandler = zkFingerWebSocketHandler;
    }

    /**
     * Delegada en {@link ZkFingerWebSocketHandler#captureForServerEnroll()} para compartir
     * el mismo executor de un solo hilo y el mismo bloqueo de dispositivo que usan las
     * sesiones WebSocket del Kiosk/Admin — el SDK nativo del ZK9500 no tolera dos hilos
     * abriendo el dispositivo a la vez.
     */
    public String captureForEnroll(long timeoutMs) {
        if (!isReaderReady()) {
            throw new IllegalStateException("Lector ZK9500 no disponible en el servidor");
        }

        CompletableFuture<byte[]> future = zkFingerWebSocketHandler.captureForServerEnroll();
        try {
            byte[] template = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            String b64 = Base64.getEncoder().encodeToString(template);
            log.info("Enrolamiento servidor completado — template generado ({} bytes, base64={} chars).", template.length, b64.length());
            return b64;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Tiempo de espera agotado esperando la captura de huella");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new RuntimeException("Error en captura de huella: " + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Captura de huella interrumpida");
        }
    }

    public boolean isReaderReady() {
        return zkFingerWebSocketHandler.isReaderReady();
    }
}
