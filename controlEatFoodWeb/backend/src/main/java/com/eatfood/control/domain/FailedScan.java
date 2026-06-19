package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "failed_scan")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FailedScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catering_id")
    private Long cateringId;

    @Column(name = "device_id")
    private Long deviceId;

    /** NOT_FOUND, OUT_OF_SCHEDULE, DUPLICATE, NOT_ALLOWED */
    @Column(nullable = false, length = 30)
    private String reason;

    @Column(name = "meal_type_id")
    private Long mealTypeId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private OffsetDateTime occurredAt = OffsetDateTime.now();
}
