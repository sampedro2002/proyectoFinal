package com.eatfood.control.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private Restaurant restaurant = new Restaurant();
    private Biometric biometric = new Biometric();
    private Cors cors = new Cors();
    private RateLimit rateLimit = new RateLimit();

    /**
     * URL pública canónica del servidor (p. ej. https://restaurant.midominio.com), sin '/api'.
     * Si se define (env var PUBLIC_URL), es la dirección autoritativa que ofrece el generador
     * de QR. Útil en despliegues detrás de proxy inverso / dominio, donde la IP de red no sirve.
     */
    private String publicUrl = "";

    @Getter @Setter
    public static class Security {
        private Jwt jwt = new Jwt();
        private BruteForce bruteForce = new BruteForce();
    }

    @Getter @Setter
    public static class Jwt {
        private String secret;
        private int accessTokenMinutes = 30;
        private int refreshTokenDays = 7;
    }

    @Getter @Setter
    public static class BruteForce {
        private int maxAttempts = 5;
        private int lockMinutes = 15;
    }

    @Getter @Setter
    public static class Restaurant {
        private int maxDevices = 2;
    }

    @Getter @Setter
    public static class Biometric {
        private int matchThreshold = 70;
        private String nativeLibPath = "./native";
        /** Clave AES para cifrar plantillas biométricas en BD. Definir vía env var en producción. */
        private String encryptionKey;
    }

    @Getter @Setter
    public static class Cors {
        private String allowedOrigins = "http://localhost:5173";
    }

    /**
     * Límite de peticiones (ventana fija, por IP) para endpoints sensibles al abuso:
     * autenticación y escaneo/conexión de dispositivos. Defensa best-effort por
     * instancia; en despliegues con réplicas conviene además limitar en el proxy.
     */
    @Getter @Setter
    public static class RateLimit {
        private boolean enabled = true;
        /** Máx. peticiones por minuto y por IP a /api/auth/login y /api/auth/refresh. */
        private int authPerMinute = 10;
        /** Máx. peticiones por minuto y por IP a POST /api/scan y /api/scan/connect. */
        private int scanPerMinute = 60;
    }
}
