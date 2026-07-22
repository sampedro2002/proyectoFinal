package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "empleado")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Employee extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cedula", nullable = false, unique = true, length = 20)
    private String identityCard;

    @Column(name = "nombre_completo", nullable = false, length = 160)
    private String fullName;

    @Column(name = "observacion", length = 500)
    private String observation;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 10)
    @Builder.Default
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "permite_almuerzo", nullable = false)
    @Builder.Default
    private boolean allowsLunch = true;

    @Column(name = "permite_merienda", nullable = false)
    @Builder.Default
    private boolean allowsSnack = false;

    @Column(name = "eliminado", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Transient
    public boolean effectiveSnack() {
        return allowsSnack;
    }
}
