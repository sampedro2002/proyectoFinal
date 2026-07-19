package com.eatfood.control.security;

import com.eatfood.control.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre el round-trip de cifrado de plantillas biométricas (AES-256-GCM) y su
 * propiedad de seguridad clave frente al esquema CBC anterior: detección de
 * manipulación del ciphertext (autenticación GCM). Ante manipulación o clave
 * incorrecta, el converter degrada devolviendo {@code null} (huella ilegible)
 * en vez de lanzar, para no tumbar el listado del admin ni abortar el índice.
 */
class AesFingerprintConverterTest {

    private AesFingerprintConverter converterWithKey(String key) {
        AppProperties props = new AppProperties();
        props.getBiometric().setEncryptionKey(key);
        return new AesFingerprintConverter(props);
    }

    @Test
    void roundTrip_encryptsAndDecryptsToOriginalBytes() {
        AesFingerprintConverter converter = converterWithKey("una-clave-cualquiera-de-prueba");
        byte[] plaintext = "plantilla-biometrica-simulada-1234567890".getBytes();

        byte[] stored = converter.convertToDatabaseColumn(plaintext);
        byte[] recovered = converter.convertToEntityAttribute(stored);

        assertThat(recovered).isEqualTo(plaintext);
        // IV (12) + ciphertext + tag GCM (16), nunca igual al plaintext ni vacío.
        assertThat(stored).hasSize(12 + plaintext.length + 16);
    }

    @Test
    void sameInput_producesDifferentCiphertext_becauseIvIsRandomPerCall() {
        AesFingerprintConverter converter = converterWithKey("otra-clave-de-prueba");
        byte[] plaintext = "misma-plantilla-siempre-igual".getBytes();

        byte[] first = converter.convertToDatabaseColumn(plaintext);
        byte[] second = converter.convertToDatabaseColumn(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(plaintext);
    }

    @Test
    void tamperedCiphertext_isRejectedByGcmAuthentication() {
        AesFingerprintConverter converter = converterWithKey("clave-de-integridad");
        byte[] stored = converter.convertToDatabaseColumn("dato-original".getBytes());
        stored[stored.length - 1] ^= 0x01; // corrompe el último byte del tag/ciphertext

        // La autenticación GCM detecta la manipulación: el converter devuelve null
        // (dato ilegible) en vez de entregarlo como válido. Lo esencial de seguridad
        // se mantiene: el dato manipulado NUNCA se acepta.
        assertThat(converter.convertToEntityAttribute(stored)).isNull();
    }

    @Test
    void differentKeys_cannotDecryptEachOthersData() {
        byte[] stored = converterWithKey("clave-A").convertToDatabaseColumn("secreto".getBytes());

        // Con otra clave, GCM falla la autenticación: se devuelve null (dato ilegible),
        // nunca el plaintext cifrado con la clave original.
        assertThat(converterWithKey("clave-B").convertToEntityAttribute(stored)).isNull();
    }

    @Test
    void blankKey_fallsBackToDefault_withoutThrowing() {
        // StartupSecurityValidator es quien bloquea el arranque en prod si la clave esta
        // vacia; el converter en si mismo no debe romperse (se usa tambien en dev/tests).
        AesFingerprintConverter converter = converterWithKey("");
        byte[] plaintext = "x".getBytes();

        byte[] recovered = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(plaintext));

        assertThat(recovered).isEqualTo(plaintext);
    }
}
