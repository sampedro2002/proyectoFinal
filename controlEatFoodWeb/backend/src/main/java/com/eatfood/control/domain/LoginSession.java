package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "login_session")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "refresh_token", nullable = false, length = 200)
    private String refreshToken;

    @Column(name = "ip_address", length = 60)
    private String ipAddress;

    @Column(name = "device_info", length = 200)
    private String deviceInfo;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private OffsetDateTime issuedAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;
}
