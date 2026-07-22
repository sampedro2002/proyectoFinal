package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "dispositivo")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurante_id")
    private Restaurant restaurant;

    @Column(name = "uid_dispositivo", nullable = false, length = 80)
    private String deviceUid;

    @Column(length = 120)
    private String name;

    @Column(name = "ultima_conexion")
    private OffsetDateTime lastSeen;

    @Column(name = "token_sesion", length = 120)
    private String sessionToken;

    @Column(name = "conectado", nullable = false)
    @Builder.Default
    private boolean connected = false;

    @Column(name = "creado_en", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
