package com.eatfood.control.security;

import com.eatfood.control.config.AppProperties;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Cifra/descifra plantillas biométricas con AES-256/GCM/NoPadding.
 *
 * <p>Formato en BD: {@code [IV (12 bytes)] + [ciphertext + tag de autenticacion (16 bytes)]}.
 * El IV se genera aleatoriamente en cada escritura y se antepone al ciphertext. GCM valida
 * la integridad del dato automaticamente al descifrar (rechaza ciphertext manipulado), a
 * diferencia del esquema CBC anterior, que era maleable.</p>
 *
 * <p><b>Derivacion de clave</b>: {@code app.biometric.encryption-key} puede ser cualquier
 * texto (Base64, passphrase, etc.) — se normaliza siempre a una clave de 32 bytes con
 * SHA-256, para no perder entropia por truncamiento cuando el valor configurado es mas
 * largo o mas corto que 32 bytes.</p>
 *
 * <p><b>Incompatible con el esquema anterior (CBC)</b>: no hay dato existente en
 * produccion, por lo que no se implementa compatibilidad hacia atras. Si se encuentran
 * registros cifrados con la version anterior, hay que re-enrolar las huellas.</p>
 */
@Slf4j
@Converter
@Component
public class AesFingerprintConverter implements AttributeConverter<byte[], byte[]> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH = 32;
    /**
     * Clave por defecto usada sólo si no se define {@code app.biometric.encryption-key}
     * ni la variable de entorno {@code BIOMETRIC_ENCRYPTION_KEY}.
     * <p><b>Importante:</b> en producción SIEMPRE debe definirse la env var para evitar
     * usar esta clave compartida; {@code StartupSecurityValidator} ya rechaza el arranque
     * en el perfil {@code prod} si la clave está vacía.</p>
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
        this.keySpec = new SecretKeySpec(deriveKey(secretKey), "AES");
    }

    /** Deriva una clave AES-256 de 32 bytes a partir de cualquier texto configurado. */
    private static byte[] deriveKey(String secretKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    @Override
    public byte[] convertToDatabaseColumn(byte[] attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute);
            // Almacenar: IV (12 bytes) + ciphertext+tag
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
                    "El registro puede estar cifrado con la versión anterior (CBC). Re-enrole la huella.", dbData.length);
            throw new IllegalStateException("Fingerprint data too short to decrypt (expected IV + ciphertext)");
        }
        try {
            byte[] iv = Arrays.copyOfRange(dbData, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(dbData, IV_LENGTH, dbData.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            log.error("[CRYPT] Verificación de integridad fallida al descifrar plantilla biométrica " +
                    "(dato manipulado o clave incorrecta).");
            throw new IllegalStateException("Fingerprint data failed authentication check (tampered or wrong key)", e);
        } catch (Exception e) {
            log.error("[CRYPT] Error al descifrar plantilla biométrica. " +
                    "Verifique que la clave (app.biometric.encryption-key) sea la misma " +
                    "con la que se cifró originalmente: {}", e.getMessage());
            throw new IllegalStateException("Error decrypting template", e);
        }
    }
}
