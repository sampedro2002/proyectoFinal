package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "registro_auditoria")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_usuario", length = 60)
    private String username;

    @Column(name = "nombre_entidad", nullable = false, length = 60)
    private String entityName;

    @Column(name = "id_entidad", length = 40)
    private String entityId;

    @Column(name = "accion", nullable = false, length = 20)
    private String action;

    @Column(name = "valor_anterior", columnDefinition = "text")
    private String oldValue;

    @Column(name = "valor_nuevo", columnDefinition = "text")
    private String newValue;

    @Column(name = "direccion_ip", length = 60)
    private String ipAddress;

    @Column(name = "info_dispositivo", length = 200)
    private String deviceInfo;

    @Column(name = "creado_en", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
