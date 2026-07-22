package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "sesion_inicio")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long userId;

    @Column(name = "token_refresco", nullable = false, length = 200)
    private String refreshToken;

    @Column(name = "direccion_ip", length = 60)
    private String ipAddress;

    @Column(name = "info_dispositivo", length = 200)
    private String deviceInfo;

    @Column(name = "emitido_en", nullable = false)
    @Builder.Default
    private OffsetDateTime issuedAt = OffsetDateTime.now();

    @Column(name = "expira_en", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revocado", nullable = false)
    @Builder.Default
    private boolean revoked = false;
}
