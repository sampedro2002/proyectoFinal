package com.eatfood.control.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    // Apagado por defecto: borrar el esquema en cada arranque es destructivo.
    // Solo se activa a proposito con FLYWAY_CLEAN_ON_START=true (y
    // spring.flyway.clean-disabled=false) para forzar una recreacion limpia,
    // por ejemplo cuando el schema remoto quedo sin flyway_schema_history.
    @Value("${app.dev.flyway-clean-on-start:false}")
    private boolean cleanOnStart;

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            if (cleanOnStart) {
                flyway.clean();
            }
            flyway.migrate();
        };
    }
}
