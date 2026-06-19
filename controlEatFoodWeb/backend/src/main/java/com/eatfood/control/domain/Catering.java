package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "catering")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Catering extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 160)
    private String location;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "max_devices", nullable = false)
    @Builder.Default
    private int maxDevices = 2;
}
