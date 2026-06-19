package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "fingerprint")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Fingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    /** Índice del dedo registrado (0-9). */
    @Column(name = "finger_index", nullable = false)
    private short fingerIndex;

    @Column(nullable = false)
    @Convert(converter = com.eatfood.control.security.AesFingerprintConverter.class)
    private byte[] template;

    @Column(name = "enrolled_by")
    private Long enrolledBy;

    @Column(name = "enrolled_at", nullable = false)
    @Builder.Default
    private OffsetDateTime enrolledAt = OffsetDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
