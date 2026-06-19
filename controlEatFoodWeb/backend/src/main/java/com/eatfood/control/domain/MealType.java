package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "meal_type")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MealType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;
}
