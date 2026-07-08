package com.eatfood.control.biometric;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Enlace JNA con la librería nativa del SDK ZKFinger (libzkfp).
 *
 * <p>Mapea las funciones reales del motor biométrico de ZKTeco usado por el lector ZK9500.
 * La captura de la huella se realiza en el dispositivo de restaurant a través del
 * ZKFinger WebAPI (agente local sobre WebSocket); el servidor recibe la <b>plantilla</b>
 * (template) y resuelve la identificación 1:N usando este motor.</p>
 *
 * <p>Requiere que las librerías nativas (libzkfp.dll y dependencias en Windows,
 * o libzkfp.so en Linux) estén accesibles en {@code jna.library.path}
 * (configurado vía {@code app.biometric.native-lib-path}).</p>
 *
 * <p>Referencia de cabecera: libzkfp.h (ZKFinger SDK v10).</p>
 */
public interface ZkfpSdk extends Library {

    /** Inicializa el entorno del SDK. Retorna 0 (ZKFP_ERR_OK) si tiene éxito. */
    int ZKFPM_Init();

    /** Libera el entorno del SDK. */
    int ZKFPM_Terminate();

    /** Obtiene el número de lectores de huellas conectados por USB. */
    int ZKFPM_GetDeviceCount();

    /** Abre el lector de huellas en el índice dado. Retorna el handle (Pointer) o null en caso de error. */
    Pointer ZKFPM_OpenDevice(int index);

    /** Cierra el lector abierto con el handle proporcionado. */
    int ZKFPM_CloseDevice(Pointer hDevice);


    /**
     * Captura la huella dactilar del lector y extrae la plantilla biométrica.
     * @param hDevice      handle del lector abierto
     * @param fpImage      buffer de salida para la imagen de la huella (puede ser de tamaño ancho*alto)
     * @param cbFPImage    tamaño del buffer de imagen
     * @param fpTemplate   buffer de salida para la plantilla extraída
     * @param cbTemplate   entrada/salida: tamaño máximo del buffer de plantilla en entrada, tamaño real en salida
     * @return 0 si la captura es exitosa, de lo contrario un código de error
     */
    int ZKFPM_AcquireFingerprint(Pointer hDevice, byte[] fpImage, int cbFPImage,
                                 byte[] fpTemplate, IntByReference cbTemplate);

    /** Crea una caché de base de datos de plantillas en memoria para matching. Retorna el handle. */
    Pointer ZKFPM_DBInit();

    /** Libera la caché creada con {@link #ZKFPM_DBInit()}. */
    int ZKFPM_DBFree(Pointer hDBCache);

    /** Vacía todas las plantillas de la caché. */
    int ZKFPM_DBClear(Pointer hDBCache);

    /** @deprecated Use ZKFPM_DBClear(hDBCache) */
    @Deprecated
    int ZKFPM_ClearDBCache(Pointer hDBCache);

    /** Cantidad de plantillas registradas en la caché. */
    int ZKFPM_DBCount(Pointer hDBCache, IntByReference fpCount);

    /**
     * Agrega una plantilla a la caché asociada a un identificador (fid).
     * @param fid identificador lógico (aquí: id del empleado)
     */
    int ZKFPM_DBAdd(Pointer hDBCache, int fid, byte[] fpTemplate, int cbFPTemplate);

    /** Elimina una plantilla por su identificador. */
    int ZKFPM_DBDel(Pointer hDBCache, int fid);

    /**
     * Identificación 1:N. Busca la plantilla recibida en toda la caché.
     * @param fid   salida: identificador encontrado
     * @param score salida: puntaje de coincidencia
     * @return 0 si encontró coincidencia
     */
    int ZKFPM_DBIdentify(Pointer hDBCache, byte[] fpTemplate, int cbFPTemplate,
                          IntByReference fid, IntByReference score);

    /**
     * Verificación 1:1 entre dos plantillas.
     * @return puntaje de coincidencia (mayor es mejor); &lt;= 0 si no coinciden.
     */
    int ZKFPM_DBMatch(Pointer hDBCache, byte[] template1, int cbTemplate1,
                      byte[] template2, int cbTemplate2);

    /** Fusiona hasta 3 muestras del mismo dedo en una plantilla de registro (enrolamiento). */
    int ZKFPM_DBMerge(Pointer hDBCache, byte[] temp1, byte[] temp2, byte[] temp3,
                      byte[] regTemp, IntByReference cbRegTemp);
}

