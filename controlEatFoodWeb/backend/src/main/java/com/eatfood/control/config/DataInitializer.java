package com.eatfood.control.config;

import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.Restaurant;
import com.eatfood.control.domain.Role;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.RestaurantRepository;
import com.eatfood.control.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Garantiza credenciales utilizables tras las migraciones Flyway:
 *  - admin / Admin123*
 *  - un usuario CATERING por cada restaurant (usuario = slug del nombre, clave = restaurant123)
 * Las contraseñas se cifran con el PasswordEncoder real (BCrypt) para evitar hashes inválidos en el seed SQL.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AppUserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // Asegurar contraseña válida del admin
        userRepository.findByUsername("admin").ifPresent(admin -> {
            if (admin.getPasswordHash() == null || !admin.getPasswordHash().startsWith("$2")) {
                admin.setPasswordHash(passwordEncoder.encode("Admin123*"));
                userRepository.save(admin);
                log.info("Contraseña del usuario 'admin' inicializada (Admin123*).");
            }
        });

        // Crear usuarios de restaurant si no existen. El nombre de usuario se deriva
        // del nombre del restaurant (p. ej. "Restaurant Norte" -> "restaurantNorte").
        Role restaurantRole = roleRepository.findByName("CATERING").orElse(null);
        if (restaurantRole == null) return;

        for (Restaurant restaurant : restaurantRepository.findAll()) {
            String username = restaurantUsername(restaurant.getName());
            if (!userRepository.existsByUsername(username)) {
                AppUser user = AppUser.builder()
                        .username(username)
                        .passwordHash(passwordEncoder.encode("restaurant123"))
                        .fullName("Operador " + restaurant.getName())
                        .enabled(true)
                        .restaurant(restaurant)
                        .roles(Set.of(restaurantRole))
                        .build();
                userRepository.save(user);
                log.info("Usuario de restaurant creado: {} (clave: restaurant123) -> {}", username, restaurant.getName());
            }
        }
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
