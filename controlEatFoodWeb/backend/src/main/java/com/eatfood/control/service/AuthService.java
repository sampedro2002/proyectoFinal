package com.eatfood.control.service;

import com.eatfood.control.config.AppProperties;
import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.LoginSession;
import com.eatfood.control.dto.AuthDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.LoginSessionRepository;
import com.eatfood.control.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
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

    @Transactional
    public AuthResponse login(LoginRequest req) {
        AppUser user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            throw new BusinessException("ACCOUNT_LOCKED",
                    "Cuenta bloqueada temporalmente por intentos fallidos. Intente más tarde.");
        }
        if (!user.isEnabled()) {
            throw new BusinessException("ACCOUNT_DISABLED", "Usuario deshabilitado.");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw new BadCredentialsException("Credenciales inválidas");
        }

        // Reset de intentos al autenticar correctamente
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        auditService.record("AppUser", String.valueOf(user.getId()), "LOGIN", null, user.getUsername());
        return issueTokens(user);
    }

    private void registerFailedAttempt(AppUser user) {
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
                .orElseThrow(() -> new BusinessException("INVALID_REFRESH", "Refresh token inválido."));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            session.setRevoked(true);
            sessionRepository.save(session);
            throw new BusinessException("EXPIRED_REFRESH", "Sesión expirada, inicie sesión nuevamente.");
        }
        AppUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new BusinessException("INVALID_REFRESH", "Usuario no encontrado."));
        session.setRevoked(true);
        sessionRepository.save(session);
        return issueTokens(user);
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
        Long cateringId = user.getCatering() != null ? user.getCatering().getId() : null;
        String access = jwtService.generateAccessToken(user.getUsername(), roles, cateringId);
        String refresh = UUID.randomUUID().toString() + UUID.randomUUID();

        LoginSession session = LoginSession.builder()
                .userId(user.getId())
                .refreshToken(refresh)
                .expiresAt(OffsetDateTime.now().plusDays(props.getSecurity().getJwt().getRefreshTokenDays()))
                .build();
        sessionRepository.save(session);

        return new AuthResponse(access, refresh, user.getUsername(), user.getFullName(), roles, cateringId);
    }
}
