package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "consumo")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Consumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empleado_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurante_id")
    private Restaurant restaurant;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispositivo_id")
    private Device device;

    @Column(name = "consumido_en", nullable = false)
    @Builder.Default
    private OffsetDateTime consumedAt = OffsetDateTime.now();

    @Column(name = "fecha_negocio", nullable = false)
    private LocalDate businessDate;

    @Column(name = "observacion", length = 500)
    private String observation;

    @Enumerated(EnumType.STRING)
    @Column(name = "metodo", nullable = false, length = 12)
    @Builder.Default
    private Method method = Method.FINGERPRINT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_apoderado_id")
    private Employee proxyEmployee;

    @Column(name = "sin_conexion", nullable = false)
    @Builder.Default
    private boolean offline = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_sincronizacion", nullable = false, length = 12)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.SYNCED;

    @Column(name = "nombre_comida", length = 30)
    private String mealName;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.VARCHAR)
    @Column(name = "uuid_cliente", nullable = false, unique = true)
    private UUID clientUuid;

    @Column(name = "cancelado", nullable = false)
    @Builder.Default
    private boolean cancelled = false;

    @Column(name = "creado_en", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
