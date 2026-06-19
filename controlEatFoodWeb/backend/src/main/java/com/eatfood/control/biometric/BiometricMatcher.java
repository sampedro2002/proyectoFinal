package com.eatfood.control.biometric;

import java.util.Optional;

/**
 * Abstracción del motor biométrico. Permite intercambiar la implementación
 * real del SDK ZKFinger (1:N sobre libzkfp) por un simulador en pruebas.
 *
 * <p>El índice opera a nivel de plantilla: cada huella (fingerprintId) se
 * registra con el empleado al que pertenece, de modo que la identificación 1:N
 * devuelve directamente el empleado coincidente.</p>
 */
public interface BiometricMatcher {

    /** Registra/actualiza una plantilla en el índice de matching. */
    void enroll(long fingerprintId, long employeeId, byte[] template);

    /** Elimina una plantilla del índice. */
    void remove(long fingerprintId);

    /** Reconstruye el índice completo a partir de las plantillas activas en BD. */
    void rebuildIndex();

    /** Búsqueda 1:N: devuelve el empleado del match con mayor score sobre el umbral. */
    Optional<MatchResult> identify(byte[] probeTemplate);

    /** Devuelve true si el motor biométrico está inicializado y listo para identificar huellas. */
    boolean isReady();

    /** Devuelve el número de plantillas cargadas actualmente en el índice en memoria. */
    int indexSize();

    record MatchResult(long employeeId, long fingerprintId, int score) {}
}
