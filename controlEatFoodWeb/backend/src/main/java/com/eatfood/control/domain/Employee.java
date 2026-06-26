package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "employee")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Employee extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_card", nullable = false, unique = true, length = 20)
    private String identityCard;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id")
    private Position position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "allowed_plates")
    private Integer allowedPlates;

    @Column(name = "allows_lunch", nullable = false)
    @Builder.Default
    private boolean allowsLunch = true;

    @Column(name = "allows_snack", nullable = false)
    @Builder.Default
    private boolean allowsSnack = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Transient
    public boolean effectiveSnack() {
        return allowsSnack || (position != null && position.isAllowsSnack());
    }
}
