package com.eatfood.control.repository;

import com.eatfood.control.domain.Fingerprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FingerprintRepository extends JpaRepository<Fingerprint, Long> {
    List<Fingerprint> findByEmployeeIdAndActiveTrue(Long employeeId);
    long countByEmployeeIdAndActiveTrue(Long employeeId);
    List<Fingerprint> findByActiveTrue();
    Optional<Fingerprint> findByEmployeeIdAndFingerIndexAndActiveFalse(Long employeeId, Short fingerIndex);
}
