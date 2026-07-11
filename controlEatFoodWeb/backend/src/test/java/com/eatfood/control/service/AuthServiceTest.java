package com.eatfood.control.service;

import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.LoginSession;
import com.eatfood.control.domain.Role;
import com.eatfood.control.dto.AuthDtos.AuthResponse;
import com.eatfood.control.dto.AuthDtos.LoginRequest;
import com.eatfood.control.dto.AuthDtos.RefreshRequest;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.LoginSessionRepository;
import com.eatfood.control.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cubre el flujo de autenticación real (login, bloqueo por fuerza bruta, refresh
 * de tokens) contra una base H2 en memoria — sin mocks para AuthService, porque
 * su lógica de bloqueo depende de que {@code self.registerFailedAttempt} corra en
 * una transacción REQUIRES_NEW real (necesita un proxy de Spring de verdad).
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthServiceTest {

    @Autowired private AuthService authService;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LoginSessionRepository sessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "Secret123";

    private AppUser createUser(String username) {
        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").description("Admin").build()));
        AppUser user = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Usuario de prueba")
                .enabled(true)
                .roles(Set.of(adminRole))
                .build();
        return userRepository.save(user);
    }

    @Test
    void login_withCorrectPassword_issuesTokensAndResetsFailedAttempts() {
        AppUser user = createUser("auth-ok-" + UUID.randomUUID());
        user.setFailedAttempts(2);
        userRepository.save(user);

        AuthResponse response = authService.login(new LoginRequest(user.getUsername(), PASSWORD));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        AppUser reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isZero();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void login_withWrongPassword_incrementsFailedAttempts_evenThoughLoginThrows() {
        AppUser user = createUser("auth-badpass-" + UUID.randomUUID());

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getUsername(), "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);

        // El intento fallido debe persistir pese a que login() lanzo y su propia
        // transaccion revirtio: registerFailedAttempt corre en REQUIRES_NEW.
        AppUser reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void login_afterMaxFailedAttempts_locksAccount_evenWithCorrectPassword() {
        AppUser user = createUser("auth-lockout-" + UUID.randomUUID());

        // app.security.brute-force.max-attempts=3 en application-test.yml
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(user.getUsername(), "wrong")))
                    .isInstanceOf(BadCredentialsException.class);
        }

        AppUser locked = userRepository.findById(user.getId()).orElseThrow();
        assertThat(locked.getLockedUntil()).isNotNull().isAfter(OffsetDateTime.now());

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getUsername(), PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCOUNT_LOCKED"));
    }

    @Test
    void refresh_withValidToken_issuesNewTokensAndRevokesOldSession() {
        AppUser user = createUser("auth-refresh-" + UUID.randomUUID());
        AuthResponse loginResponse = authService.login(new LoginRequest(user.getUsername(), PASSWORD));
        String oldRefreshToken = loginResponse.refreshToken();

        AuthResponse refreshed = authService.refresh(new RefreshRequest(oldRefreshToken));

        assertThat(refreshed.refreshToken()).isNotEqualTo(oldRefreshToken);
        LoginSession oldSession = sessionRepository.findByRefreshTokenAndRevokedFalse(oldRefreshToken).orElse(null);
        assertThat(oldSession).as("la sesion vieja debe quedar revocada").isNull();

        // Reusar el refresh token ya revocado debe fallar.
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(oldRefreshToken)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REFRESH"));
    }

    @Test
    void refresh_withExpiredToken_throwsAndRevokesSession() {
        AppUser user = createUser("auth-expired-" + UUID.randomUUID());
        LoginSession expired = sessionRepository.save(LoginSession.builder()
                .userId(user.getId())
                .refreshToken("expired-token-" + UUID.randomUUID())
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(expired.getRefreshToken())))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("EXPIRED_REFRESH"));

        LoginSession reloaded = sessionRepository.findById(expired.getId()).orElseThrow();
        assertThat(reloaded.isRevoked()).isTrue();
    }
}
