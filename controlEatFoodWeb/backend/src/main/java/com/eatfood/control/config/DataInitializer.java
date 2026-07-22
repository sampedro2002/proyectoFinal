package com.eatfood.control.config;

import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.Restaurant;
import com.eatfood.control.domain.Role;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.RestaurantRepository;
import com.eatfood.control.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Garantiza credenciales utilizables tras las migraciones Flyway.
 * Fuera de producción usa contraseñas fijas conocidas (admin/Admin123*,
 * restaurantXxx/restaurant123) para no entorpecer el desarrollo local.
 * En producción (perfil {@code prod}) genera una contraseña aleatoria de un
 * solo uso por usuario y la imprime una vez en el log de arranque: así ningún
 * despliegue nuevo queda con una credencial por defecto públicamente conocida
 * (ver StartupSecurityValidator, mismo criterio aplicado a JWT/biometría).
 * Las contraseñas se cifran con el PasswordEncoder real (BCrypt) para evitar hashes inválidos en el seed SQL.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final String DEV_ADMIN_PASSWORD = "Admin123*";
    private static final String DEV_RESTAURANT_PASSWORD = "restaurant123";
    private static final String DEV_RRHH_PASSWORD = "Rrhh123*";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;

    @Value("${app.credentials-file:}")
    private String credentialsFilePath;

    @Override
    public void run(String... args) {
        boolean production = env.acceptsProfiles(Profiles.of("prod"));
        List<String> generated = new ArrayList<>();

        // Asegurar contraseña válida del admin
        userRepository.findByUsername("admin").ifPresent(admin -> {
            if (admin.getPasswordHash() == null || !admin.getPasswordHash().startsWith("$2")) {
                String pwd = production ? generateOneTimePassword() : DEV_ADMIN_PASSWORD;
                admin.setPasswordHash(passwordEncoder.encode(pwd));
                userRepository.save(admin);
                if (production) {
                    generated.add("admin: " + pwd);
                    logOneTimeCredential("admin", null, pwd);
                } else {
                    log.info("Contraseña del usuario 'admin' inicializada ({}).", DEV_ADMIN_PASSWORD);
                }
            }
        });

        // Asegurar contraseña válida del usuario de Recursos Humanos
        userRepository.findByUsername("rrhh").ifPresent(rrhh -> {
            if (rrhh.getPasswordHash() == null || !rrhh.getPasswordHash().startsWith("$2")) {
                String pwd = production ? generateOneTimePassword() : DEV_RRHH_PASSWORD;
                rrhh.setPasswordHash(passwordEncoder.encode(pwd));
                userRepository.save(rrhh);
                if (production) {
                    generated.add("rrhh: " + pwd);
                    logOneTimeCredential("rrhh", null, pwd);
                } else {
                    log.info("Contraseña del usuario 'rrhh' inicializada ({}).", DEV_RRHH_PASSWORD);
                }
            }
        });

        // Crear usuarios de restaurant si no existen. El nombre de usuario se deriva
        // del nombre del restaurant (p. ej. "Restaurant Norte" -> "restaurantNorte").
        Role restaurantRole = roleRepository.findByName("CATERING").orElse(null);
        if (restaurantRole != null) {
            for (Restaurant restaurant : restaurantRepository.findAll()) {
                String username = restaurantUsername(restaurant.getName());
                if (!userRepository.existsByUsername(username)) {
                    String pwd = production ? generateOneTimePassword() : DEV_RESTAURANT_PASSWORD;
                    AppUser user = AppUser.builder()
                            .username(username)
                            .passwordHash(passwordEncoder.encode(pwd))
                            .fullName("Operador " + restaurant.getName())
                            .enabled(true)
                            .restaurant(restaurant)
                            .roles(Set.of(restaurantRole))
                            .build();
                    userRepository.save(user);
                    if (production) {
                        generated.add(username + " (" + restaurant.getName() + "): " + pwd);
                        logOneTimeCredential(username, restaurant.getName(), pwd);
                    } else {
                        log.info("Usuario de restaurant creado: {} (clave: {}) -> {}", username, DEV_RESTAURANT_PASSWORD, restaurant.getName());
                    }
                }
            }
        }

        if (production && !generated.isEmpty() && credentialsFilePath != null && !credentialsFilePath.isBlank()) {
            writeCredentialsFile(generated);
        }
    }

    /**
     * Registra la creación de cada credencial de producción. Si está
     * configurado {@code app.credentials-file} (modo servicio Windows
     * con NSSM), las contraseñas van sólo al {@code credenciales.txt}
     * local con permisos restrictivos — no se imprimen en el log, ya
     * que el log append-puro de stdout podría terminar en un archivo
     * permanente accesible por cualquier operador. Si no hay
     * credentials-file, se mantienen en el log como aviso WARN una sola
     * vez (comportamiento histórico) para no dejar un despliegue nuevo
     * sin credenciales visibles para el operador.
     */
    private void logOneTimeCredential(String username, String restaurantName, String pwd) {
        String display = restaurantName != null
                ? "'" + username + "' (" + restaurantName + ")"
                : "'" + username + "'";
        if (credentialsFilePath != null && !credentialsFilePath.isBlank()) {
            log.warn("[SECURITY] Credencial de producción inicializada para {}. " +
                    "Contraseña escrita en {} — borre ese archivo tras el primer ingreso.",
                    display, credentialsFilePath);
        } else {
            log.warn("[SECURITY] Usuario {} creado (solo se muestra esta vez): {}", display, pwd);
        }
    }

    /** Anota usuario/contraseña recién generados en un txt local, para el primer ingreso. */
    private void writeCredentialsFile(List<String> generated) {
        try {
            Path path = Path.of(credentialsFilePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("=== Credenciales generadas ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" ===\n");
            sb.append("Cambia estas contraseñas desde Gestion de usuarios apenas ingreses, y luego borra este archivo.\n");
            for (String line : generated) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
            Files.writeString(path, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.warn("[SECURITY] Credenciales tambien escritas en: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("No se pudo escribir el archivo de credenciales en {}", credentialsFilePath, e);
        }
    }

    /** Contraseña aleatoria (no persistida en código fuente) para el primer arranque en producción. */
    private static String generateOneTimePassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Genera el nombre de usuario de restaurant a partir del nombre del restaurant.
     * Regla: quitar espacios y poner la primera letra en minúscula.
     * Ej: "Restaurant Norte" -> "restaurantNorte", "Restaurant Centro" -> "restaurantCentro".
     */
    private static String restaurantUsername(String restaurantName) {
        if (restaurantName == null || restaurantName.isBlank()) return "restaurant";
        String noSpaces = restaurantName.replaceAll("\\s+", "");
        if (noSpaces.isEmpty()) return "restaurant";
        return Character.toLowerCase(noSpaces.charAt(0)) + noSpaces.substring(1);
    }
}
