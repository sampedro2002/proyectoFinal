package com.eatfood.control.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Valida, al arrancar, que los secretos críticos de seguridad estén definidos
 * de forma segura cuando la aplicación corre en producción (perfil {@code prod}).
 *
 * <p>En producción la aplicación <b>no arranca</b> si:</p>
 * <ul>
 *   <li>{@code JWT_SECRET} está vacío o conserva el valor por defecto del repositorio.</li>
 *   <li>{@code BIOMETRIC_ENCRYPTION_KEY} está vacío (las huellas se cifrarían con la
 *       clave por defecto compartida, lo que equivale a no cifrarlas de forma segura).</li>
 * </ul>
 *
 * <p>Fuera de producción sólo se emite una advertencia, para no entorpecer el
 * desarrollo local. Active el modo producción con
 * {@code SPRING_PROFILES_ACTIVE=prod} (o {@code -Dspring.profiles.active=prod}).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSecurityValidator implements ApplicationRunner {

    /** Valor por defecto del JWT en application.yml; jamás debe usarse en producción. */
    private static final String INSECURE_JWT_DEFAULT =
            "Y29udHJvbC1lYXQtZm9vZC1zZWNyZXQta2V5LWNoYW5nZS1pbi1wcm9kdWN0aW9uLTEyMzQ1Ng==";

    /** Placeholder no funcional de application.yml; jamás debe usarse en producción. */
    private static final String INSECURE_DB_PASSWORD_DEFAULT = "changeme-set-DB_PASSWORD-env-var";

    private final AppProperties props;
    private final Environment env;

    @Override
    public void run(ApplicationArguments args) {
        boolean production = env.acceptsProfiles(Profiles.of("prod"));

        String jwtSecret = props.getSecurity().getJwt().getSecret();
        boolean jwtInsecure = jwtSecret == null || jwtSecret.isBlank()
                || INSECURE_JWT_DEFAULT.equals(jwtSecret.trim());

        String bioKey = props.getBiometric().getEncryptionKey();
        boolean bioInsecure = bioKey == null || bioKey.isBlank();

        String dbPassword = env.getProperty("spring.datasource.password");
        boolean dbPasswordInsecure = dbPassword == null || dbPassword.isBlank()
                || INSECURE_DB_PASSWORD_DEFAULT.equals(dbPassword.trim());

        if (production) {
            if (jwtInsecure) {
                throw new IllegalStateException(
                        "JWT_SECRET no está definido o usa el valor por defecto inseguro. " +
                        "Defina la variable de entorno JWT_SECRET con una clave Base64 aleatoria de al menos 256 bits.");
            }
            if (bioInsecure) {
                throw new IllegalStateException(
                        "BIOMETRIC_ENCRYPTION_KEY no está definida. En producción es obligatoria para " +
                        "cifrar las plantillas biométricas. Defina la variable de entorno con un valor aleatorio (mín. 16 bytes).");
            }
            if (dbPasswordInsecure) {
                throw new IllegalStateException(
                        "DB_PASSWORD no está definida o usa el placeholder por defecto. " +
                        "Defina la variable de entorno DB_PASSWORD con la contraseña real de la base de datos.");
            }
            log.info("[SECURITY] Validación de secretos de producción superada.");
        } else {
            if (jwtInsecure) {
                log.warn("[SECURITY] JWT_SECRET usa el valor por defecto. NO usar así en producción.");
            }
            if (bioInsecure) {
                log.warn("[SECURITY] BIOMETRIC_ENCRYPTION_KEY vacía: se usará la clave por defecto. NO usar así en producción.");
            }
            if (dbPasswordInsecure) {
                log.warn("[SECURITY] DB_PASSWORD usa el placeholder por defecto. NO usar así en producción.");
            }
        }
    }
}
