package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "consumption")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Consumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @Column(name = "consumed_at", nullable = false)
    @Builder.Default
    private OffsetDateTime consumedAt = OffsetDateTime.now();

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Observación opcional; se captura en el registro manual. */
    @Column(length = 500)
    private String observation;

    @Column(nullable = false)
    @Builder.Default
    private boolean offline = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 12)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.SYNCED;

    @Column(name = "meal_name", length = 30)
    private String mealName;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.VARCHAR)
    @Column(name = "client_uuid", nullable = false, unique = true)
    private UUID clientUuid;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
