package com.eatfood.control.security;

import com.eatfood.control.config.AppProperties;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Cifra/descifra plantillas biométricas con AES-128/CBC/PKCS5Padding.
 *
 * <p>Formato en BD: {@code [IV (16 bytes)] + [ciphertext]}. El IV se genera
 * aleatoriamente en cada escritura y se antepone al ciphertext. La lectura
 * extrae los primeros 16 bytes como IV y descifra el resto.</p>
 *
 * <p><b>Migración desde ECB</b>: si en la BD ya existen registros cifrados con la
 * versión anterior (ECB, sin IV), la lectura fallará porque los primeros 16 bytes
 * no son un IV válido. En ese caso, ejecute {@code POST /api/fingerprints/clean-all}
 * y re-enrolle todas las huellas.</p>
 */
@Slf4j
@Converter
@Component
public class AesFingerprintConverter implements AttributeConverter<byte[], byte[]> {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;
    private static final int KEY_LENGTH = 16;
    /**
     * Clave por defecto usada sólo si no se define {@code app.biometric.encryption-key}
     * ni la variable de entorno {@code BIOMETRIC_ENCRYPTION_KEY}.
     * <p><b>Importante:</b> en producción SIEMPRE debe definirse la env var para evitar
     * usar esta clave compartida.</p>
     */
    private static final String DEFAULT_KEY = "DefaultSecretKeyForFpEncryption123";

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesFingerprintConverter(AppProperties props) {
        String secretKey = props.getBiometric().getEncryptionKey();
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("[CRYPT] No se configuró 'app.biometric.encryption-key'. " +
                    "Se usará la clave por defecto (NO recomendado para producción).");
            secretKey = DEFAULT_KEY;
        }
        byte[] keyBytes = new byte[KEY_LENGTH];
        byte[] sourceBytes = secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(sourceBytes, 0, keyBytes, 0, Math.min(sourceBytes.length, KEY_LENGTH));
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public byte[] convertToDatabaseColumn(byte[] attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(attribute);
            // Almacenar: IV (16 bytes) + ciphertext
            byte[] result = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, IV_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            log.error("[CRYPT] Error al cifrar plantilla biométrica: {}", e.getMessage());
            throw new IllegalStateException("Error encrypting template", e);
        }
    }

    @Override
    public byte[] convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        if (dbData.length <= IV_LENGTH) {
            log.error("[CRYPT] Datos en BD demasiado cortos ({} bytes) para contener IV+ciphertext. " +
                    "El registro puede estar cifrado con la versión anterior (ECB). Re-enrolle la huella.", dbData.length);
            throw new IllegalStateException("Fingerprint data too short to decrypt (expected IV + ciphertext)");
        }
        try {
            byte[] iv = Arrays.copyOfRange(dbData, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(dbData, IV_LENGTH, dbData.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            log.error("[CRYPT] Error al descifrar plantilla biométrica. " +
                    "Verifique que la clave (app.biometric.encryption-key) sea la misma " +
                    "con la que se cifró originalmente: {}", e.getMessage());
            throw new IllegalStateException("Error decrypting template", e);
        }
    }
}
