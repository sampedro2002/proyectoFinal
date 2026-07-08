package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "restaurant")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Restaurant extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 160)
    private String location;

    /** Nombre del responsable / representante que maneja el restaurante. */
    @Column(length = 120)
    private String representative;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "max_devices", nullable = false)
    @Builder.Default
    private int maxDevices = 2;
}
