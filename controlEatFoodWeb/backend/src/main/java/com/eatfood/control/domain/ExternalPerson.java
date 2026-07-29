package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Persona externa (visitante, contratista, invitado) que consume en el
 * restaurante sin ser empleada. Vive SEPARADA de {@link Employee}: nunca
 * aparece en la gestión ni en la exportación de empleados. Sus consumos
 * ({@code method=EXTERNAL}) referencian esta tabla vía
 * {@code consumo.persona_externa_id}.
 */
@Entity
@Table(name = "persona_externa")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExternalPerson extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cedula", nullable = false, unique = true, length = 20)
    private String identityCard;

    @Column(name = "nombre_completo", nullable = false, length = 160)
    private String fullName;

    @Column(name = "observacion", length = 500)
    private String observation;
}
