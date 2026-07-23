package com.eatfood.control.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "usuario")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_usuario", nullable = false, unique = true, length = 60)
    private String username;

    @Column(name = "contrasena_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(name = "nombre_completo", nullable = false, length = 120)
    private String fullName;

    @Column(name = "correo", length = 120)
    private String email;

    @Column(name = "habilitado", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "intentos_fallidos", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "bloqueado_hasta")
    private OffsetDateTime lockedUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id")
    private Restaurant restaurant;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "usuario_rol",
            joinColumns = @JoinColumn(name = "usuario_id"),
            inverseJoinColumns = @JoinColumn(name = "rol_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();
}
