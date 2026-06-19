package com.eatfood.control.config;

import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.Catering;
import com.eatfood.control.domain.Role;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.CateringRepository;
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
 *  - un usuario CATERING por cada catering (usuario = slug del nombre, clave = catering123)
 * Las contraseñas se cifran con el PasswordEncoder real (BCrypt) para evitar hashes inválidos en el seed SQL.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AppUserRepository userRepository;
    private final CateringRepository cateringRepository;
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

        // Crear usuarios de catering si no existen
        Role cateringRole = roleRepository.findByName("CATERING").orElse(null);
        if (cateringRole == null) return;

        for (Catering catering : cateringRepository.findAll()) {
            String username = "catering" + catering.getId();
            if (!userRepository.existsByUsername(username)) {
                AppUser user = AppUser.builder()
                        .username(username)
                        .passwordHash(passwordEncoder.encode("catering123"))
                        .fullName("Operador " + catering.getName())
                        .enabled(true)
                        .catering(catering)
                        .roles(Set.of(cateringRole))
                        .build();
                userRepository.save(user);
                log.info("Usuario de catering creado: {} (clave: catering123) -> {}", username, catering.getName());
            }
        }
    }
}
