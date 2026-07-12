package com.eatfood.control.service;

import com.eatfood.control.config.AppProperties;
import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.LoginSession;
import com.eatfood.control.dto.AuthDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.UnauthorizedException;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.LoginSessionRepository;
import com.eatfood.control.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository userRepository;
    private final LoginSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;
    private final AuditService auditService;
    /**
     * Auto-referencia para invocar {@link #registerFailedAttempt} en una transacción
     * independiente ({@link Propagation#REQUIRES_NEW}) y que el rollback del login
     * NO revierta el registro del intento fallido (de lo contrario el contador de
     * intentos nunca persistiría y la cuenta jamás se bloquearía).
     */
    @Autowired
    @Lazy
    private AuthService self;

    @Transactional
    public AuthResponse login(LoginRequest req) {
        AppUser user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            throw new UnauthorizedException("ACCOUNT_LOCKED",
                    "Cuenta bloqueada temporalmente por intentos fallidos. Intente más tarde.");
        }
        if (!user.isEnabled()) {
            throw new UnauthorizedException("ACCOUNT_DISABLED", "Usuario deshabilitado.");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // Se invoca vía proxy (self) para que abra en una tx nueva y persista aunque
            // el login termine lanzando BadCredentialsException (que revierte la tx padre).
            self.registerFailedAttempt(user);
            throw new BadCredentialsException("Credenciales inválidas");
        }

        // Reset de intentos al autenticar correctamente
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        auditService.record("AppUser", String.valueOf(user.getId()), "LOGIN", null, user.getUsername());
        return issueTokens(user);
    }

    /**
     * Registra un intento fallido de login en una transacción independiente
     * ({@link Propagation#REQUIRES_NEW}) de modo que el rollback producido por el
     * {@code BadCredentialsException} del método llamador NO deshaga este cambio.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedAttempt(AppUser user) {
        int attempts = user.getFailedAttempts() + 1;
        user.setFailedAttempts(attempts);
        if (attempts >= props.getSecurity().getBruteForce().getMaxAttempts()) {
            user.setLockedUntil(OffsetDateTime.now()
                    .plusMinutes(props.getSecurity().getBruteForce().getLockMinutes()));
            user.setFailedAttempts(0);
            auditService.record("AppUser", String.valueOf(user.getId()), "LOCKED", null, "fuerza bruta");
        }
        userRepository.save(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest req) {
        LoginSession session = sessionRepository.findByRefreshTokenAndRevokedFalse(req.refreshToken())
                .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH", "Refresh token inválido."));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            // Revocar en tx nueva para que el revoke persista aunque el lanzamiento de
            // la excepción revierta la tx padre.
            self.revokeSession(session);
            throw new UnauthorizedException("EXPIRED_REFRESH", "Sesión expirada, inicie sesión nuevamente.");
        }
        AppUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH", "Usuario no encontrado."));
        session.setRevoked(true);
        sessionRepository.save(session);
        return issueTokens(user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeSession(LoginSession session) {
        session.setRevoked(true);
        sessionRepository.save(session);
    }

    @Transactional
    public void logout(String refreshToken) {
        sessionRepository.findByRefreshTokenAndRevokedFalse(refreshToken).ifPresent(s -> {
            s.setRevoked(true);
            sessionRepository.save(s);
        });
    }

    private AuthResponse issueTokens(AppUser user) {
        List<String> roles = user.getRoles().stream().map(r -> r.getName()).toList();
        Long restaurantId = user.getRestaurant() != null ? user.getRestaurant().getId() : null;
        String access = jwtService.generateAccessToken(user.getUsername(), roles, restaurantId);
        String refresh = UUID.randomUUID().toString() + UUID.randomUUID();

        LoginSession session = LoginSession.builder()
                .userId(user.getId())
                .refreshToken(refresh)
                .expiresAt(OffsetDateTime.now().plusDays(props.getSecurity().getJwt().getRefreshTokenDays()))
                .build();
        sessionRepository.save(session);

        return new AuthResponse(access, refresh, user.getUsername(), user.getFullName(), roles, restaurantId);
    }
}
