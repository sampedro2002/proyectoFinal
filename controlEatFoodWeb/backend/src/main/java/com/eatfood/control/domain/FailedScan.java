package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "escaneo_fallido")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FailedScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id")
    private Long restaurantId;

    @Column(name = "dispositivo_id")
    private Long deviceId;

    /** NOT_FOUND, OUT_OF_SCHEDULE, DUPLICATE, NOT_ALLOWED */
    @Column(name = "razon", nullable = false, length = 30)
    private String reason;


    @Column(name = "empleado_id")
    private Long employeeId;

    @Column(name = "ocurrido_en", nullable = false)
    @Builder.Default
    private OffsetDateTime occurredAt = OffsetDateTime.now();
}
