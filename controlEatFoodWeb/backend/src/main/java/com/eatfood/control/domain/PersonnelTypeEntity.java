package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tipo_personal")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PersonnelTypeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "codigo", nullable = false, unique = true, length = 30) private String code;
    @Column(name = "nombre", nullable = false, length = 80) private String name;
    @Column(name = "orden", nullable = false) private int sortOrder;
    @Column(name = "activo", nullable = false) @Builder.Default private boolean active = true;
}
