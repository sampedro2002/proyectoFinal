package com.eatfood.control.domain;

/**
 * Origen del registro de consumo.
 *
 * <ul>
 *   <li>{@link #FINGERPRINT} — escaneo de huella del propio empleado en el
 *       dispositivo del restaurante (endpoint {@code /api/scan}).</li>
 *   <li>{@link #MANUAL} — registro administrativo de "retira por otro": un
 *       empleado retira comidas a nombre de uno o varios titulares; la columna
 *       {@code empleado_apoderado_id} referencia al empleado que retira.</li>
 *   <li>{@link #EXTERNAL} — persona externa con cédula/pasaporte creada al
 *       vuelo (endpoint {@code /api/manual-consumptions/external}).</li>
 * </ul>
 */
public enum Method {
    FINGERPRINT,
    MANUAL,
    EXTERNAL
}