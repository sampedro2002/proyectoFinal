package com.eatfood.control.service;

import com.eatfood.control.biometric.ZkBiometricMatcher;
import com.eatfood.control.biometric.ZkfpSdk;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.*;

@Slf4j
@Service
public class FingerprintCaptureService {

    private final ZkBiometricMatcher zkBiometricMatcher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FingerprintCaptureService(@Autowired(required = false) ZkBiometricMatcher zkBiometricMatcher) {
        this.zkBiometricMatcher = zkBiometricMatcher;
    }

    public String captureForEnroll(long timeoutMs) {
        if (zkBiometricMatcher == null || !zkBiometricMatcher.isReaderReady()) {
            throw new IllegalStateException("Lector ZK9500 no disponible en el servidor");
        }

        Future<String> future = executor.submit(this::doCaptureForEnroll);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
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

    private String doCaptureForEnroll() throws InterruptedException {
        ZkfpSdk sdk = zkBiometricMatcher.getSdk();
        Pointer hDevice = null;
        try {
            hDevice = sdk.ZKFPM_OpenDevice(0);
            if (hDevice == null) {
                throw new RuntimeException("ZKFPM_OpenDevice retornó null — lector no disponible");
            }
            log.info("Lector USB ZKFinger abierto para enrolamiento desde servidor.");

            byte[] imgBuf = new byte[1024 * 1024];

            {
                byte[] drainTpl = new byte[2048];
                IntByReference drainLen = new IntByReference(2048);
                if (sdk.ZKFPM_AcquireFingerprint(hDevice, imgBuf, imgBuf.length, drainTpl, drainLen) == 0) {
                    log.debug("Buffer residual drenado del sensor.");
                    Thread.sleep(800);
                }
            }

            final int TOTAL = 3;
            byte[][] temps = new byte[TOTAL][];

            for (int step = 1; step <= TOTAL; step++) {
                if (Thread.currentThread().isInterrupted()) return null;

                log.info("Registro: esperando captura {}/{} desde servidor...", step, TOTAL);
                byte[] tplBuf = new byte[2048];
                IntByReference tplLen = new IntByReference(2048);

                while (!Thread.currentThread().isInterrupted()) {
                    tplLen.setValue(2048);
                    int rc = sdk.ZKFPM_AcquireFingerprint(hDevice, imgBuf, imgBuf.length, tplBuf, tplLen);
                    if (rc == 0) {
                        temps[step - 1] = Arrays.copyOf(tplBuf, tplLen.getValue());
                        log.info("Captura {}/{} exitosa (longitud={}).", step, TOTAL, temps[step - 1].length);
                        break;
                    } else if (rc == -28 || rc == -23 || rc == 1) {
                        Thread.sleep(100);
                    } else {
                        Thread.sleep(200);
                    }
                }

                if (temps[step - 1] == null) return null;

                if (step < TOTAL) {
                    waitForFingerLift(sdk, hDevice, imgBuf);
                }
            }

            byte[] finalTpl = zkBiometricMatcher.mergeTemplates(temps[0], temps[1], temps[2]);
            if (finalTpl == null) {
                log.warn("ZKFPM_DBMerge falló en enrolamiento servidor. Usando mejor captura individual.");
                byte[] best = temps[0];
                for (byte[] t : temps) { if (t != null && t.length > best.length) best = t; }
                finalTpl = best;
            }

            String b64 = Base64.getEncoder().encodeToString(finalTpl);
            log.info("Enrolamiento servidor completado — template generado ({} bytes, base64={} chars).", finalTpl.length, b64.length());
            return b64;
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            log.error("Error nativo en captura desde servidor: {}", t.getMessage(), t);
            throw new RuntimeException("Error nativo en captura: " + t.getMessage(), t);
        } finally {
            if (hDevice != null) {
                try { sdk.ZKFPM_CloseDevice(hDevice); log.info("Lector USB cerrado tras enrolamiento servidor."); } catch (Throwable ignored) {}
            }
        }
    }

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

    public boolean isReaderReady() {
        return zkBiometricMatcher != null && zkBiometricMatcher.isReaderReady();
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }
}
