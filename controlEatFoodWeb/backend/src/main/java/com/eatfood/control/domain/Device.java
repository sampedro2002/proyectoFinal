package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catering_id")
    private Catering catering;

    @Column(name = "device_uid", nullable = false, length = 80)
    private String deviceUid;

    @Column(length = 120)
    private String name;

    @Column(name = "last_seen")
    private OffsetDateTime lastSeen;

    @Column(name = "session_token", length = 120)
    private String sessionToken;

    @Column(nullable = false)
    @Builder.Default
    private boolean connected = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
