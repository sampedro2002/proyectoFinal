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
    @JoinColumn(name = "catering_id")
    private Catering catering;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "meal_type_id")
    private MealType mealType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @Column(name = "consumed_at", nullable = false)
    @Builder.Default
    private OffsetDateTime consumedAt = OffsetDateTime.now();

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    @Builder.Default
    private boolean offline = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 12)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.SYNCED;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.VARCHAR)
    @Column(name = "client_uuid", nullable = false, unique = true)
    private UUID clientUuid;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
