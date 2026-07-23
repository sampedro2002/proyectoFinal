package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "restaurante")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Restaurant extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, unique = true, length = 120)
    private String name;

    @Column(name = "ubicacion", length = 160)
    private String location;

    @Column(name = "representante", length = 120)
    private String representative;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "max_dispositivos", nullable = false)
    @Builder.Default
    private int maxDevices = 2;
}
