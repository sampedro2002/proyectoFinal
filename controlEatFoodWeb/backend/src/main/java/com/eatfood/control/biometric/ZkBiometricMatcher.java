package com.eatfood.control.biometric;

import com.eatfood.control.config.AppProperties;
import com.eatfood.control.domain.Fingerprint;
import com.eatfood.control.repository.FingerprintRepository;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implementación real del motor biométrico usando el SDK nativo ZKFinger (libzkfp)
 * a través de JNA. Mantiene una caché en memoria con todas las plantillas activas y
 * resuelve la identificación 1:N con {@code ZKFPM_DBIdentify}.
 *
 * <p>Activa con {@code app.biometric.provider=zk} (valor por defecto).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.biometric.provider", havingValue = "zk", matchIfMissing = true)
public class ZkBiometricMatcher implements BiometricMatcher {

    private static final int ZKFP_ERR_OK = 0;

    private final FingerprintRepository fingerprintRepository;
    private final int threshold;
    private final String nativeLibPath;

    private ZkfpSdk sdk;
    private Pointer dbCache;
    private boolean ready = false;

    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> retryTask = null;

    /** fid del SDK (= fingerprintId) -> employeeId */
    private final Map<Integer, Long> fidToEmployee = new ConcurrentHashMap<>();

    public ZkBiometricMatcher(FingerprintRepository fingerprintRepository, AppProperties props) {
        this.fingerprintRepository = fingerprintRepository;
        this.threshold = props.getBiometric().getMatchThreshold();
        this.nativeLibPath = props.getBiometric().getNativeLibPath();
    }

    @PostConstruct
    public synchronized void init() {
        try {
            if (sdk == null) {
                sdk = loadSdk();
                if (sdk == null) {
                    scheduleRetry();
                    return;
                }
            }

            int rc = sdk.ZKFPM_Init();
            if (rc != ZKFP_ERR_OK) {
                String hint = rc == -1 ? " (sin dispositivo o driver no instalado)" :
                              rc == -2 ? " (driver USB no instalado o ZK9500 no conectado)" : "";
                log.warn("ZKFPM_Init falló (código {}){} — reintentando en 10 segundos...", rc, hint);
                scheduleRetry();
                return;
            }
            dbCache = sdk.ZKFPM_DBInit();
            if (dbCache == null) {
                log.error("ZKFPM_DBInit devolvió null. Reintentando en 10 segundos...");
                scheduleRetry();
                return;
            }
            rebuildIndex();
            ready = true;
            cancelRetry();
            log.info("Motor biométrico ZKFinger inicializado correctamente (umbral={}).", threshold);
        } catch (UnsatisfiedLinkError e) {
            log.error("No se pudo cargar el SDK nativo ZKFinger desde '{}'. " +
                    "Coloque las DLL/.so del SDK ZK9500 en esa ruta. Detalle: {}", nativeLibPath, e.getMessage());
            scheduleRetry();
        } catch (Throwable e) {
            ready = false;
            log.error("Error inesperado al inicializar el motor ZKFinger: {}. Reintentando en 10 segundos...", e.getMessage());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (retryTask != null && !retryTask.isDone()) return;
        retryTask = retryScheduler.scheduleWithFixedDelay(this::init, 10, 10, TimeUnit.SECONDS);
        log.info("Programado reintento de inicialización del ZKTeco9500 cada 10 segundos.");
    }

    private void cancelRetry() {
        if (retryTask != null) {
            retryTask.cancel(false);
            retryTask = null;
            log.info("Reintentos de inicialización cancelados — motor listo.");
        }
    }

    private ZkfpSdk loadSdk() {
        // Prioridad 1: libzkfp.dll instalada en System32 por el driver ZKTeco.
        // ZKFPCap.dll localiza sus plugins de sensor (ZKFPSensors/) relativo a System32 → funciona.
        try {
            ZkfpSdk systemSdk = Native.load("libzkfp", ZkfpSdk.class);
            log.info("SDK ZKFinger cargado desde el sistema (libzkfp.dll en java.library.path/System32).");
            return systemSdk;
        } catch (UnsatisfiedLinkError e1) {
            log.debug("libzkfp.dll no encontrada en java.library.path: {}. Intentando desde native-lib-path.", e1.getMessage());
        }

        // Prioridad 2: libzkfp.dll desde native-lib-path con SetDllDirectory para resolver deps.
        String resolvedPath = resolvePlatformPath(nativeLibPath);
        setWindowsDllDirectory(resolvedPath);
        preloadWindowsDlls(resolvedPath);
        System.setProperty("jna.library.path", resolvedPath);
        try {
            ZkfpSdk localSdk = Native.load("libzkfp", ZkfpSdk.class);
            log.info("SDK ZKFinger cargado desde '{}' (libzkfp.dll).", resolvedPath);
            return localSdk;
        } catch (UnsatisfiedLinkError e2) {
            log.error("No se pudo cargar libzkfp.dll desde '{}'. Detalle: {}", resolvedPath, e2.getMessage());
            return null;
        }
    }

    private void setWindowsDllDirectory(String path) {
        if (path == null) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return;
        try {
            com.sun.jna.NativeLibrary k32 = com.sun.jna.NativeLibrary.getInstance("kernel32");
            com.sun.jna.Function fn = k32.getFunction("SetDllDirectoryW");
            boolean ok = Boolean.TRUE.equals(fn.invoke(Boolean.class, new Object[]{path}));
            log.info("SetDllDirectory('{}') → {}", path, ok ? "OK" : "invocado");
        } catch (Throwable t) {
            log.debug("SetDllDirectory no pudo ejecutarse: {}", t.getMessage());
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        cancelRetry();
        retryScheduler.shutdownNow();
        if (sdk != null && ready) {
            if (dbCache != null) sdk.ZKFPM_DBFree(dbCache);
            sdk.ZKFPM_Terminate();
        }
    }

    @Override
    public synchronized void rebuildIndex() {
        if (sdk == null || dbCache == null) return;
        sdk.ZKFPM_DBClear(dbCache);
        fidToEmployee.clear();

        java.util.List<Fingerprint> all = fingerprintRepository.findByActiveTrue();
        log.info("rebuildIndex: {} huellas activas encontradas en BD.", all.size());

        int ok = 0, fail = 0;
        for (Fingerprint fp : all) {
            byte[] tpl = fp.getTemplate();
            if (tpl == null || tpl.length == 0) {
                log.warn("rebuildIndex: huella id={} tiene template nulo/vacío — omitida.", fp.getId());
                fail++;
                continue;
            }
            log.debug("rebuildIndex: cargando huella id={}, empleado={}, dedo={}, template={} bytes.",
                    fp.getId(), fp.getEmployee().getId(), fp.getFingerIndex(), tpl.length);
            try {
                int rc = sdk.ZKFPM_DBAdd(dbCache, fp.getId().intValue(), tpl, tpl.length);
                if (rc == ZKFP_ERR_OK) {
                    fidToEmployee.put(fp.getId().intValue(), fp.getEmployee().getId());
                    ok++;
                } else {
                    log.warn("rebuildIndex: ZKFPM_DBAdd falló para huella id={} (rc={}). " +
                            "Posible template corrupto o incompatible.", fp.getId(), rc);
                    fail++;
                }
            } catch (Throwable t) {
                log.error("rebuildIndex: excepción nativa al cargar huella id={}: {}. Saltando.", fp.getId(), t.getMessage());
                fail++;
            }
        }
        log.info("rebuildIndex completado — OK={}, FALLIDOS={}, total en índice={}.",
                ok, fail, fidToEmployee.size());
        if (fail > 0) {
            log.warn("rebuildIndex: {} plantilla(s) no pudieron cargarse. " +
                    "Ejecute GET /api/fingerprints/biometric-status para más detalles.", fail);
        }
    }

    @Override
    public synchronized void enroll(long fingerprintId, long employeeId, byte[] template) {
        if (!ready) {
            log.warn("enroll: motor no disponible (ready=false) — huella id={} NO fue añadida al índice.", fingerprintId);
            return;
        }
        if (template == null || template.length == 0) {
            log.error("enroll: template nulo/vacío para huella id={} — ignorado.", fingerprintId);
            return;
        }
        log.info("enroll: registrando huella id={}, empleado={}, template={} bytes.",
                fingerprintId, employeeId, template.length);
        sdk.ZKFPM_DBDel(dbCache, (int) fingerprintId);
        addInternal(fingerprintId, employeeId, template);
    }

    private void addInternal(long fingerprintId, long employeeId, byte[] template) {
        try {
            int rc = sdk.ZKFPM_DBAdd(dbCache, (int) fingerprintId, template, template.length);
            if (rc == ZKFP_ERR_OK) {
                fidToEmployee.put((int) fingerprintId, employeeId);
                log.info("enroll: ZKFPM_DBAdd OK — id={}, empleado={}, índice={} plantillas.",
                        fingerprintId, employeeId, fidToEmployee.size());
            } else {
                log.error("enroll: ZKFPM_DBAdd FALLÓ para id={} (rc={}). " +
                        "La plantilla NO fue añadida al índice en memoria. " +
                        "rc=-6 indica template corrupto; rc=-7 indica fid duplicado.", fingerprintId, rc);
            }
        } catch (Throwable t) {
            log.error("enroll: excepción nativa en ZKFPM_DBAdd id={}: {}. Reiniciando caché.",
                    fingerprintId, t.getMessage());
            try { sdk.ZKFPM_DBFree(dbCache); } catch (Throwable ignored) {}
            dbCache = sdk.ZKFPM_DBInit();
            if (dbCache == null) {
                log.error("enroll: no se pudo reinicializar caché del SDK — matching deshabilitado.");
                throw new IllegalStateException("SDK cache recovery failed");
            }
            fidToEmployee.clear();
            log.warn("enroll: caché SDK reiniciada — índice vacío. Ejecute biometric-rebuild para restaurar.");
        }
    }

    /**
     * Fusiona 3 capturas en una plantilla de registro.
     *
     * <p><b>IMPORTANTE</b>: usa un handle temporal dedicado (nunca el {@code dbCache}
     * principal). {@code ZKFPM_DBMerge} modifica el estado interno del handle que recibe;
     * usar {@code dbCache} corrompería el índice 1:N y haría que {@code ZKFPM_DBIdentify}
     * no encontrara coincidencias en las identificaciones posteriores.</p>
     *
     * @return plantilla fusionada, o {@code null} si falla
     */
    public synchronized byte[] mergeTemplates(byte[] t1, byte[] t2, byte[] t3) {
        if (!ready || sdk == null) return null;
        log.info("ZKFPM_DBMerge: tamaños entrada t1={} t2={} t3={} bytes.",
                t1 != null ? t1.length : -1,
                t2 != null ? t2.length : -1,
                t3 != null ? t3.length : -1);
        Pointer tempDb = sdk.ZKFPM_DBInit();
        if (tempDb == null) {
            log.error("ZKFPM_DBMerge: no se pudo crear handle temporal (ZKFPM_DBInit devolvió null).");
            return null;
        }
        try {
            byte[] merged = new byte[2048];
            IntByReference mergedLen = new IntByReference(2048);
            int rc = sdk.ZKFPM_DBMerge(tempDb, t1, t2, t3, merged, mergedLen);
            if (rc == 0) {
                int len = mergedLen.getValue();
                log.info("ZKFPM_DBMerge exitoso — plantilla de registro generada ({} bytes).", len);
                return java.util.Arrays.copyOf(merged, len);
            }
            log.warn("ZKFPM_DBMerge falló (rc={}). El enrolamiento usará la mejor captura individual.", rc);
        } catch (Throwable t) {
            log.warn("Excepción en ZKFPM_DBMerge: {}", t.getMessage());
        } finally {
            try { sdk.ZKFPM_DBFree(tempDb); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Devuelve el número de plantillas actualmente cargadas en el índice en memoria. */
    public int indexSize() {
        return fidToEmployee.size();
    }

    @Override
    public synchronized void remove(long fingerprintId) {
        if (!ready) return;
        sdk.ZKFPM_DBDel(dbCache, (int) fingerprintId);
        fidToEmployee.remove((int) fingerprintId);
    }

    @Override
    public synchronized Optional<MatchResult> identify(byte[] probeTemplate) {
        if (!ready) {
            log.warn("identify: motor biométrico no disponible (ready=false).");
            return Optional.empty();
        }
        if (fidToEmployee.isEmpty()) {
            log.warn("identify: índice en memoria vacío (0 plantillas). " +
                    "Ejecute POST /api/fingerprints/biometric-rebuild para reconstruir.");
            return Optional.empty();
        }
        if (probeTemplate == null || probeTemplate.length == 0) {
            log.warn("identify: template probe nulo/vacío — ignorado.");
            return Optional.empty();
        }
        log.debug("identify: probe={} bytes, índice={} plantillas, umbral={}.",
                probeTemplate.length, fidToEmployee.size(), threshold);

        IntByReference fid = new IntByReference();
        IntByReference score = new IntByReference();
        long t0 = System.currentTimeMillis();
        try {
            int rc = sdk.ZKFPM_DBIdentify(dbCache, probeTemplate, probeTemplate.length, fid, score);
            long elapsed = System.currentTimeMillis() - t0;
            if (rc != ZKFP_ERR_OK) {
                log.info("identify: ZKFPM_DBIdentify rc={} (sin coincidencia) en {} ms.", rc, elapsed);
                return Optional.empty();
            }
            log.info("identify: ZKFPM_DBIdentify OK — fid={}, score={}, tiempo={} ms.",
                    fid.getValue(), score.getValue(), elapsed);
        } catch (Throwable t) {
            log.warn("identify: excepción nativa en ZKFPM_DBIdentify: {}", t.getMessage());
            return Optional.empty();
        }

        if (score.getValue() < threshold) {
            log.info("identify: score={} es MENOR que el umbral={}. No se considera coincidencia.",
                    score.getValue(), threshold);
            return Optional.empty();
        }
        Long employeeId = fidToEmployee.get(fid.getValue());
        if (employeeId == null) {
            log.warn("identify: fid={} no está en fidToEmployee (posible inconsistencia). " +
                    "Reconstruya el índice.", fid.getValue());
            return Optional.empty();
        }
        log.info("identify: empleado={} identificado — fid={}, score={} (umbral={}).",
                employeeId, fid.getValue(), score.getValue(), threshold);
        return Optional.of(new MatchResult(employeeId, fid.getValue(), score.getValue()));
    }

    public boolean isReady() {
        return ready;
    }

    public ZkfpSdk getSdk() {
        return sdk;
    }

    /**
     * Devuelve la ruta absoluta del subdirectorio de plataforma dentro de {@code basePath}.
     * En Windows 64-bit usa {@code win-x64/}, en Windows 32-bit usa {@code win-x86/},
     * en Linux 64-bit usa {@code linux-x64/}, etc. Si el subdirectorio no existe,
     * devuelve la ruta base tal como está.
     */
    private static String resolvePlatformPath(String basePath) {
        if (basePath == null || basePath.isBlank()) return basePath;
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64 = arch.contains("64");
        String subDir;
        if (os.contains("win")) {
            subDir = is64 ? "win-x64" : "win-x86";
        } else {
            subDir = is64 ? "linux-x64" : "linux-x86";
        }
        File platformDir = new File(new File(basePath).getAbsoluteFile(), subDir);
        if (platformDir.isDirectory()) {
            return platformDir.getAbsolutePath();
        }
        return new File(basePath).getAbsolutePath();
    }

    /**
     * Pre-carga todas las DLLs del directorio en Windows usando {@code System.load()} para
     * que el cargador de Windows las tenga en caché cuando {@code libzkfp.dll} las busque
     * como dependencias transitivas. Se realizan múltiples pasadas hasta que todas las DLLs
     * de la carpeta hayan sido cargadas o no haya más progreso.
     */
    private void preloadWindowsDlls(String dirPath) {
        if (dirPath == null) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return;

        File dir = new File(dirPath);
        File[] dlls = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".dll")
                && !n.equalsIgnoreCase("libzkfp.dll"));
        if (dlls == null || dlls.length == 0) return;

        // Múltiples pasadas para resolver dependencias en cadena
        java.util.Set<String> pending = new java.util.LinkedHashSet<>();
        for (File f : dlls) pending.add(f.getAbsolutePath());

        int maxPasses = dlls.length;
        for (int pass = 0; pass < maxPasses && !pending.isEmpty(); pass++) {
            java.util.Set<String> stillPending = new java.util.LinkedHashSet<>();
            for (String path : pending) {
                try {
                    System.load(path);
                    log.debug("DLL pre-cargada: {}", new File(path).getName());
                } catch (Throwable t) {
                    stillPending.add(path);
                }
            }
            if (stillPending.size() == pending.size()) break; // sin progreso
            pending = stillPending;
        }
        if (!pending.isEmpty()) {
            log.debug("DLLs no pre-cargadas (pueden no ser necesarias): {}",
                    pending.stream().map(p -> new File(p).getName()).toList());
        }
    }
}
