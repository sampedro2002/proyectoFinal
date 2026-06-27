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
    private Catering catering = new Catering();
    private Biometric biometric = new Biometric();
    private Cors cors = new Cors();

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
    public static class Catering {
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
}
