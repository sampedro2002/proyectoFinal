package com.eatfood.control.repository;

import com.eatfood.control.domain.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoginSessionRepository extends JpaRepository<LoginSession, Long> {
    Optional<LoginSession> findByRefreshTokenAndRevokedFalse(String refreshToken);
    List<LoginSession> findByUserIdAndRevokedFalse(Long userId);
}
