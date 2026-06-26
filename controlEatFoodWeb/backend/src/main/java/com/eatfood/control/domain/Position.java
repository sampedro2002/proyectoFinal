package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "`position`")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Position extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(name = "default_plates", nullable = false)
    @Builder.Default
    private int defaultPlates = 1;

    @Column(name = "allows_snack", nullable = false)
    @Builder.Default
    private boolean allowsSnack = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
