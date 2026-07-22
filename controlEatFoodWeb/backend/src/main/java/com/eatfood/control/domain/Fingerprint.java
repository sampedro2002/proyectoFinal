package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "huella_digital")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Fingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empleado_id")
    private Employee employee;

    @Column(name = "indice_dedo", nullable = false)
    private short fingerIndex;

    @Column(name = "plantilla", nullable = false)
    @Convert(converter = com.eatfood.control.security.AesFingerprintConverter.class)
    private byte[] template;

    @Column(name = "registrado_por")
    private Long enrolledBy;

    @Column(name = "registrado_en", nullable = false)
    @Builder.Default
    private OffsetDateTime enrolledAt = OffsetDateTime.now();

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private boolean active = true;
}
