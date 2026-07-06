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

    @Column(name = "public_code", unique = true, length = 12)
    private String publicCode;

    @Column(name = "position_title", length = 120)
    private String positionTitle;

    @Column(name = "observation", length = 500)
    private String observation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

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
        return allowsSnack;
    }
}
