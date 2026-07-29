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

    /**
     * Titular empleado interno. NULL cuando el titular es una persona externa
     * ({@link #externalPerson}): exactamente uno de los dos está presente
     * (CHECK chk_consumo_titular en la BD).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_id")
    private Employee employee;

    /**
     * Titular persona externa (solo consumos con {@code method=EXTERNAL}).
     * NULL cuando el titular es un empleado interno.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_externa_id")
    private ExternalPerson externalPerson;

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

    /**
     * Empleado que retira el plato a nombre del titular (apoderado), opcional.
     * Excluyente con {@link #proxyExternalPerson} (CHECK chk_consumo_apoderado).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_apoderado_id")
    private Employee proxyEmployee;

    /**
     * Persona externa registrada que retira el plato a nombre del titular,
     * opcional. Excluyente con {@link #proxyEmployee}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_externa_apoderada_id")
    private ExternalPerson proxyExternalPerson;

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

    /** Nombre del titular del consumo, sea empleado interno o persona externa. */
    public String titularName() {
        if (employee != null) return employee.getFullName();
        return externalPerson != null ? externalPerson.getFullName() : null;
    }

    /** Cédula/pasaporte del titular del consumo, sea empleado o persona externa. */
    public String titularIdentityCard() {
        if (employee != null) return employee.getIdentityCard();
        return externalPerson != null ? externalPerson.getIdentityCard() : null;
    }

    /** Nombre de quien retira (apoderado), sea empleado o persona externa; null si retira el propio titular. */
    public String proxyName() {
        if (proxyEmployee != null) return proxyEmployee.getFullName();
        return proxyExternalPerson != null ? proxyExternalPerson.getFullName() : null;
    }

    /** ¿Quien retira es una persona externa (no empleada)? */
    public boolean proxyIsExternal() {
        return proxyExternalPerson != null;
    }
}
