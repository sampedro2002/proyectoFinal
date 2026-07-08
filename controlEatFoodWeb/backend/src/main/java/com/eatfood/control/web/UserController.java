package com.eatfood.control.web;

import com.eatfood.control.dto.UserDtos.*;
import com.eatfood.control.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Usuarios")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Lista todos los usuarios del sistema")
    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    @Operation(summary = "Lista los roles disponibles")
    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return userService.listRoles();
    }

    @Operation(summary = "Crea un nuevo usuario")
    @PostMapping
    public UserResponse create(@Valid @RequestBody UserRequest req) {
        return userService.create(req);
    }

    @Operation(summary = "Edita un usuario (nombre, email, rol, restaurante, estado y opcionalmente contraseña)")
    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserRequest req) {
        return userService.update(id, req);
    }

    @Operation(summary = "Restablece la contraseña de un usuario")
    @PostMapping("/{id}/password")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordResetRequest req) {
        userService.resetPassword(id, req.password());
    }

    @Operation(summary = "Activa o desactiva un usuario")
    @PatchMapping("/{id}/enabled")
    public UserResponse setEnabled(@PathVariable Long id, @RequestBody EnabledRequest req) {
        return userService.setEnabled(id, req.enabled());
    }
}
