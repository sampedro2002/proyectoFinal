package com.eatfood.control.service;

import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.Restaurant;
import com.eatfood.control.domain.Role;
import com.eatfood.control.dto.UserDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.RestaurantRepository;
import com.eatfood.control.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gestión de usuarios del sistema (solo ADMIN): alta, edición, cambio de
 * contraseña, activación/desactivación, asignación de rol y de restaurante.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RestaurantRepository restaurantRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll().stream()
                .map(r -> new RoleResponse(r.getId(), r.getName(), r.getDescription()))
                .toList();
    }

    @Transactional
    public UserResponse create(UserRequest req) {
        String username = req.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException("DUPLICATE_USER", "Ya existe un usuario con ese nombre.");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw new BusinessException("PASSWORD_REQUIRED", "La contraseña es obligatoria al crear un usuario.");
        }
        validatePasswordStrength(req.password());
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setFullName(req.fullName());
        user.setEmail(blankToNull(req.email()));
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setEnabled(req.enabled() == null || req.enabled());
        user.setRoles(resolveRoles(req.roles()));
        user.setRestaurant(resolveRestaurant(req.restaurantId()));
        user = userRepository.save(user);
        auditService.record("AppUser", String.valueOf(user.getId()), "CREATE", null, user.getUsername());
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UserRequest req) {
        AppUser user = find(id);
        String before = snapshot(user);
        
        String newUsername = req.username().trim();
        if (!user.getUsername().equals(newUsername)) {
            if (userRepository.existsByUsername(newUsername)) {
                throw new BusinessException("DUPLICATE_USER", "Ya existe un usuario con ese nombre.");
            }
            user.setUsername(newUsername);
        }
        
        user.setFullName(req.fullName());
        user.setEmail(blankToNull(req.email()));
        if (req.enabled() != null) {
            if (!req.enabled() && isCurrentUser(user)) {
                throw new BusinessException("SELF_DISABLE", "No puede desactivar su propia cuenta.");
            }
            user.setEnabled(req.enabled());
        }
        if (req.roles() != null) {
            user.setRoles(resolveRoles(req.roles()));
        }
        user.setRestaurant(resolveRestaurant(req.restaurantId()));
        // Si viene una contraseña no vacía, se actualiza; si viene vacía, no se cambia.
        if (req.password() != null && !req.password().isBlank()) {
            validatePasswordStrength(req.password());
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        user = userRepository.save(user);
        auditService.record("AppUser", String.valueOf(id), "UPDATE", before, snapshot(user));
        return toResponse(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = find(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        auditService.record("AppUser", String.valueOf(id), "PASSWORD_RESET", null, user.getUsername());
    }

    @Transactional
    public UserResponse setEnabled(Long id, boolean enabled) {
        AppUser user = find(id);
        if (!enabled && isCurrentUser(user)) {
            throw new BusinessException("SELF_DISABLE", "No puede desactivar su propia cuenta.");
        }
        user.setEnabled(enabled);
        user = userRepository.save(user);
        auditService.record("AppUser", String.valueOf(id), enabled ? "ENABLE" : "DISABLE", null, user.getUsername());
        return toResponse(user);
    }

    // ------------------------------------------------------------------------

    private AppUser find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado: " + id));
    }

    private Set<Role> resolveRoles(List<String> names) {
        Set<Role> roles = new HashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) continue;
                roleRepository.findByName(name.trim().toUpperCase())
                        .ifPresent(roles::add);
            }
        }
        if (roles.isEmpty()) {
            throw new BusinessException("ROLE_REQUIRED", "Debe asignar al menos un rol válido.");
        }
        return roles;
    }

    private Restaurant resolveRestaurant(Long restaurantId) {
        if (restaurantId == null) return null;
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurante no encontrado: " + restaurantId));
    }

    private boolean isCurrentUser(AppUser user) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && user.getUsername().equals(auth.getName());
    }

    /** Misma longitud mínima que {@code PasswordResetRequest} (UserDtos), aplicada aquí porque
     *  en creación/edición la contraseña es opcional (vacía = no cambiar) y no puede validarse
     *  con una anotación simple en el DTO. */
    private static void validatePasswordStrength(String password) {
        if (password.length() < 6) {
            throw new BusinessException("WEAK_PASSWORD", "La contraseña debe tener al menos 6 caracteres.");
        }
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private String snapshot(AppUser u) {
        return "%s|%s|enabled=%s|roles=%s|restaurantId=%s".formatted(
                u.getUsername(), u.getFullName(), u.isEnabled(),
                u.getRoles().stream().map(Role::getName).sorted().collect(Collectors.joining(",")),
                u.getRestaurant() != null ? u.getRestaurant().getId() : null);
    }

    private UserResponse toResponse(AppUser u) {
        List<String> roles = u.getRoles().stream().map(Role::getName).sorted().toList();
        return new UserResponse(
                u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.isEnabled(),
                roles,
                u.getRestaurant() != null ? u.getRestaurant().getId() : null,
                u.getRestaurant() != null ? u.getRestaurant().getName() : null);
    }
}
