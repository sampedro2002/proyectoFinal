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

    /**
     * El motor de MATCHING (índice 1:N + {@code ZKFPM_DBIdentify}) está listo. Es puro
     * algoritmo: una vez cargada la DLL y creada la caché con {@code ZKFPM_DBInit}, NO
     * necesita ningún lector conectado a este equipo. Es lo que habilita la validación
     * de huellas desde los kioscos/teléfonos aunque el servidor no tenga un ZK9500
     * enchufado.
     */
    private boolean matchingReady = false;
    /**
     * Hay un lector ZK9500 disponible en ESTE equipo ({@code ZKFPM_Init} tuvo éxito).
     * Solo se necesita para CAPTURAR/ENROLAR desde la web local; las validaciones 1:N
     * no dependen de esto.
     */
    private volatile boolean readerReady = false;
    /** Último conteo de dispositivos observado por el poll (diagnóstico; -1 = aún no consultado). */
    private volatile int lastDeviceCount = -1;
    /** Evita reintentar la recuperación del SDK en cada ciclo del poll (una vez por episodio de bloqueo). */
    private boolean recoveryAttempted = false;
    /** {@code ZKFPM_Init} devolvió OK al menos una vez (para emparejar con {@code ZKFPM_Terminate}). */
    private volatile boolean sdkInitialized = false;
    /** La caché de matching se creó sin lector ({@code ZKFPM_Init} aún no había tenido éxito). */
    private boolean cacheBuiltWithoutReader = false;
    /**
     * Indica si el último intento de inicialización falló. Se usa para
     * emitir el WARN de "lector no disponible" UNA sola vez (transición
     * OK→fallo) y silenciar los reintentos periódicos a DEBUG. Sin esto,
     * un ZK9500 desconectado genera una línea de WARN cada 10 s de forma
     * indefinida y satura el log de producción.
     */
    private boolean lastInitFailed = false;
    private String lastInitErrorMessage = null;

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
                    logInitFailure("No se pudo cargar el SDK nativo ZKFinger desde '" + nativeLibPath
                            + "'. Coloque las DLL/.so del SDK ZK9500 en esa ruta.");
                    scheduleRetry();
                    return;
                }
            }

            // ZKFPM_Init inicializa el ENTORNO/ALGORITMO del SDK (no el hardware). En el SDK
            // estándar devuelve 0 aunque no haya lector: por eso la PRESENCIA del lector NO se
            // deduce de este rc, sino de ZKFPM_GetDeviceCount() (abajo y en el poll del
            // ZkFingerWebSocketHandler). Se llama una sola vez para poder emparejar con Terminate.
            int rc = sdk.ZKFPM_Init();
            if (rc == ZKFP_ERR_OK) sdkInitialized = true;

            // Presencia REAL del lector en este equipo: número de dispositivos USB detectados.
            boolean readerNow = safeDeviceCount() > 0;

            // --- Motor de matching (independiente del lector) ---
            if (!matchingReady) {
                if (dbCache == null) {
                    dbCache = sdk.ZKFPM_DBInit();
                }
                if (dbCache == null) {
                    logInitFailure("ZKFPM_DBInit devolvió null — el motor de matching no pudo inicializarse"
                            + (readerNow ? " (libzkfp corrupta o sensor en uso)."
                                         : ". Este build del SDK podría requerir el ZK9500 conectado para arrancar."));
                    scheduleRetry();
                    return;
                }
                rebuildIndex();
                matchingReady = true;
                cacheBuiltWithoutReader = !readerNow;
                if (readerNow) {
                    log.info("Motor biométrico ZKFinger listo (matching + captura, umbral={}).", threshold);
                } else {
                    log.info("Motor de matching ZKFinger listo SIN lector — validación 1:N activa (umbral={}). " +
                            "Conecte un ZK9500 a este equipo solo si necesita capturar/enrolar desde la web.", threshold);
                }
            }

            // --- Estado del lector físico (solo para captura/enrolamiento web) ---
            // Se resuelve por GetDeviceCount y se mantiene al día en AMBAS direcciones mediante
            // reportReaderPresent(), que invoca el poll periódico del ZkFingerWebSocketHandler.
            applyReaderPresence(readerNow);

            // El motor de matching ya arrancó; los reintentos de BRING-UP del motor se cancelan.
            // La conexión/desconexión del lector se vigila aparte (poll GetDeviceCount), no aquí.
            lastInitFailed = false;
            cancelRetry();
        } catch (UnsatisfiedLinkError e) {
            logInitFailure("No se pudo cargar el SDK nativo ZKFinger desde '" + nativeLibPath
                    + "'. Detalle: " + e.getMessage());
            scheduleRetry();
        } catch (Throwable e) {
            matchingReady = false;
            readerReady = false;
            logInitFailure("Error inesperado al inicializar el motor ZKFinger: " + e.getMessage());
            scheduleRetry();
        }
    }

    /**
     * Libera y recrea la caché nativa de matching y recarga el índice desde BD.
     * Se usa cuando el lector aparece tras un arranque sin él, para partir de un
     * estado del SDK limpio.
     */
    private void refreshCache() {
        try {
            if (dbCache != null) {
                try { sdk.ZKFPM_DBFree(dbCache); } catch (Throwable ignored) {}
            }
            dbCache = sdk.ZKFPM_DBInit();
            if (dbCache == null) {
                log.error("refreshCache: ZKFPM_DBInit devolvió null — matching deshabilitado hasta el próximo reintento.");
                matchingReady = false;
                return;
            }
            rebuildIndex();
        } catch (Throwable t) {
            log.error("refreshCache: error al refrescar la caché de matching: {}", t.getMessage());
        }
    }

    /** Número de lectores ZK9500 conectados por USB; 0 si el SDK aún no cargó o la llamada falla. */
    private int safeDeviceCount() {
        try {
            return sdk != null ? sdk.ZKFPM_GetDeviceCount() : 0;
        } catch (Throwable t) {
            log.debug("ZKFPM_GetDeviceCount falló: {}", t.getMessage());
            return 0;
        }
    }

    /**
     * Comprueba la presencia del lector y actualiza el estado. DEBE ejecutarse en el hilo con
     * afinidad del SDK (lo llama el executor del {@link com.eatfood.control.web.ZkFingerWebSocketHandler}).
     *
     * <p>Maneja los dos comportamientos conocidos de {@code libzkfp}:</p>
     * <ul>
     *   <li>Builds donde {@code ZKFPM_Init()} arranca sin lector: {@code GetDeviceCount()} refleja
     *       directamente la presencia.</li>
     *   <li>Builds donde {@code GetDeviceCount()} solo funciona tras un {@code ZKFPM_Init()} exitoso
     *       y este solo tiene éxito con el lector conectado: si el conteo es 0 y el SDK aún no
     *       inicializó, se reintenta {@code ZKFPM_Init()} y se vuelve a contar. Así la conexión
     *       en caliente se detecta aunque el entorno no se hubiese podido inicializar al arranque.</li>
     * </ul>
     *
     * @return true si hay al menos un lector conectado.
     */
    public synchronized boolean pollReaderPresence() {
        return pollReaderPresence(true);
    }

    /**
     * @param allowRecovery si es {@code false}, solo detecta (GetDeviceCount + probe de apertura)
     *   sin ejecutar la recuperación pesada (Terminate+Init). Lo usa {@code handleOpen} del kiosco,
     *   cuyo cliente tiene un timeout de conexión corto (5 s): la recuperación pesada se deja al
     *   poll de fondo y a la ruta de captura.
     */
    public synchronized boolean pollReaderPresence(boolean allowRecovery) {
        if (sdk == null) { lastDeviceCount = 0; return false; }
        int count = safeDeviceCount();
        if (count <= 0 && !sdkInitialized) {
            try {
                int rc = sdk.ZKFPM_Init();
                if (rc == ZKFP_ERR_OK) {
                    sdkInitialized = true;
                    count = safeDeviceCount();
                    log.info("ZKFPM_Init tardío OK — SDK inicializado; dispositivos detectados: {}.", count);
                }
            } catch (Throwable t) {
                log.debug("pollReaderPresence: ZKFPM_Init falló: {}", t.getMessage());
            }
        }
        lastDeviceCount = count;

        // Detección REAL de conectividad: que GetDeviceCount>0 no basta — un ZK9500 bloqueado por
        // un cierre anterior reporta count>0 pero ZKFPM_OpenDevice devuelve null. Se prueba abrir y
        // cerrar de verdad para confirmar que es USABLE. Si está bloqueado, se intenta UNA
        // recuperación automática del SDK (Terminate+Init) por episodio. Este probe solo corre
        // cuando NO hay captura activa (el poll del WS lo omite si el lector está en uso).
        boolean usable = count > 0 && probeOpen();
        if (allowRecovery && count > 0 && !usable && !recoveryAttempted) {
            log.warn("ZK9500 detectado (GetDeviceCount={}) pero OpenDevice falló — presente pero NO usable. " +
                    "Intentando recuperación automática del SDK…", count);
            recoverStuckReader();
            recoveryAttempted = true;
            usable = probeOpen();
            if (!usable) {
                log.warn("Recuperación no resolvió el bloqueo del ZK9500 — desconecte y reconecte el lector.");
            }
        }
        if (usable) recoveryAttempted = false;
        reportReaderPresent(usable);
        return usable;
    }

    /** Abre y cierra el lector para verificar que es USABLE de verdad (no solo detectado). */
    private boolean probeOpen() {
        Pointer h = null;
        try {
            h = sdk.ZKFPM_OpenDevice(0);
            return h != null;
        } catch (Throwable t) {
            log.debug("probeOpen: ZKFPM_OpenDevice lanzó {}", t.getMessage());
            return false;
        } finally {
            if (h != null) {
                try { sdk.ZKFPM_CloseDevice(h); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Recuperación de un lector "colgado": {@code GetDeviceCount>0} pero {@code OpenDevice}
     * devuelve null de forma persistente (lock del SDK/driver tras un cierre anterior no limpio).
     * Resetea el entorno del SDK ({@code Terminate}+{@code Init}) y recrea la caché de matching
     * (Terminate invalida el handle {@code dbCache}). DEBE ejecutarse en el hilo con afinidad del
     * SDK (lo llaman el probe del poll y la ruta de captura, ambos en el executor del WS handler).
     */
    public synchronized void recoverStuckReader() {
        if (sdk == null) return;
        log.warn("Recuperando lector ZK9500 bloqueado: ZKFPM_Terminate + ZKFPM_Init + reconstrucción de índice…");
        try { sdk.ZKFPM_Terminate(); } catch (Throwable t) { log.debug("recover: Terminate lanzó {}", t.getMessage()); }
        // El handle dbCache queda inválido tras Terminate; NO llamar DBFree sobre él (crashearía).
        dbCache = null;
        matchingReady = false;
        sdkInitialized = false;
        try {
            int rc = sdk.ZKFPM_Init();
            if (rc == ZKFP_ERR_OK) { sdkInitialized = true; log.info("recover: ZKFPM_Init OK."); }
            else log.warn("recover: ZKFPM_Init rc={}.", rc);
        } catch (Throwable t) {
            log.error("recover: ZKFPM_Init lanzó {}", t.getMessage());
        }
        try {
            dbCache = sdk.ZKFPM_DBInit();
            if (dbCache != null) { rebuildIndex(); matchingReady = true; cacheBuiltWithoutReader = false; }
            else log.error("recover: ZKFPM_DBInit devolvió null — matching sigue deshabilitado.");
        } catch (Throwable t) {
            log.error("recover: ZKFPM_DBInit/rebuild lanzó {}", t.getMessage());
        }
    }

    /** Último conteo de dispositivos observado por el poll (diagnóstico). */
    public int getLastDeviceCount() { return lastDeviceCount; }

    /** {@code true} si {@code ZKFPM_Init} tuvo éxito al menos una vez (SDK inicializado). */
    public boolean isSdkInitialized() { return sdkInitialized; }

    /**
     * Notifica un cambio de presencia del lector detectado por {@code ZKFPM_GetDeviceCount()}.
     * Lo invoca el poll periódico del {@link com.eatfood.control.web.ZkFingerWebSocketHandler},
     * que ejecuta GetDeviceCount en el hilo con afinidad del SDK. Es idempotente: solo actúa en
     * las transiciones ausente↔presente, y detecta tanto la conexión como la desconexión.
     */
    public synchronized void reportReaderPresent(boolean present) {
        // Si el lector llegó pero el motor de matching aún no arrancó (p. ej. un build del SDK
        // que exige el lector para inicializar el algoritmo), intentar el bring-up ahora.
        if (present && !matchingReady) {
            init();
            return;
        }
        applyReaderPresence(present);
    }

    /**
     * Aplica la transición de presencia del lector. Al reconectarse, si el índice se había
     * armado sin lector (los {@code ZKFPM_DBAdd} pueden fallar con rc=-13 y dejarlo vacío),
     * lo reconstruye para habilitar la validación 1:N y la captura.
     */
    private synchronized void applyReaderPresence(boolean present) {
        if (present == readerReady) return;
        if (present) {
            if (cacheBuiltWithoutReader) {
                log.info("ZK9500 detectado — refrescando índice de matching (habilita validación/captura).");
                refreshCache();
                cacheBuiltWithoutReader = false;
            } else {
                log.info("ZK9500 detectado — captura/enrolamiento habilitado.");
            }
            readerReady = true;
        } else {
            log.warn("ZK9500 desconectado — captura/enrolamiento deshabilitado " +
                    "(la validación 1:N sigue activa mientras el índice esté cargado).");
            readerReady = false;
        }
    }

    /**
     * Emite el log de fallo de inicialización UNA sola vez como WARN
     * (transición OK→fallo) y como DEBUG en los sucesivos reintentos.
     * Evita la repetición masiva del mismo mensaje cada 10 s cuando el
     * ZK9500 está desconectado. Cualquier error nuevo (cambio de
     * mensaje) vuelve a salir como WARN para mantener visibilidad.
     */
    private void logInitFailure(String msg) {
        if (!lastInitFailed || !msg.equals(lastInitErrorMessage)) {
            log.warn("{} — reintentando cada 10 s hasta que se conecte.", msg);
            lastInitErrorMessage = msg;
        } else {
            log.debug("{} — reintentando...", msg);
        }
        lastInitFailed = true;
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
        if (sdk != null) {
            if (dbCache != null) {
                try { sdk.ZKFPM_DBFree(dbCache); } catch (Throwable ignored) {}
            }
            // ZKFPM_Terminate solo se llama si ZKFPM_Init llegó a tener éxito.
            if (sdkInitialized) {
                try { sdk.ZKFPM_Terminate(); } catch (Throwable ignored) {}
            }
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
        if (!matchingReady) {
            log.warn("enroll: motor de matching no disponible (matchingReady=false) — huella id={} NO fue añadida al índice.", fingerprintId);
            return;
        }
        if (template == null || template.length == 0) {
            log.error("enroll: template nulo/vacío para huella id={} — ignorado.", fingerprintId);
            return;
        }
        log.info("enroll: registrando huella id={}, empleado={}, template={} bytes.",
                fingerprintId, employeeId, template.length);

        // ZKFPM_DBDel seguido de ZKFPM_DBAdd corrompe el índice interno del SDK ZKTeco
        // y hace que ZKFPM_DBIdentify falle para plantillas previamente añadidas.
        // En lugar de tocar la caché nativa, reconstruimos todo el índice desde BD.
        fidToEmployee.put((int) fingerprintId, employeeId);
        rebuildIndex();
    }

    @SuppressWarnings("unused")
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
        if (!matchingReady || sdk == null) return null;
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
    @Override
    public int indexSize() {
        return fidToEmployee.size();
    }

    @Override
    public synchronized void remove(long fingerprintId) {
        if (!matchingReady) return;
        sdk.ZKFPM_DBDel(dbCache, (int) fingerprintId);
        fidToEmployee.remove((int) fingerprintId);
    }

    @Override
    public synchronized Optional<MatchResult> identify(byte[] probeTemplate) {
        if (!matchingReady) {
            log.warn("identify: motor de matching no disponible (matchingReady=false).");
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

    @Override
    public boolean isReady() {
        return matchingReady;
    }

    /**
     * True si hay un lector ZK9500 disponible en ESTE equipo para capturar/enrolar
     * desde la web local. Las validaciones 1:N no dependen de esto.
     */
    public boolean isReaderReady() {
        return readerReady;
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
