package com.eatfood.control.biometric;

import com.eatfood.control.domain.Fingerprint;
import com.eatfood.control.repository.FingerprintRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación simulada del motor biométrico para entornos sin hardware ZK9500.
 * No usa el SDK nativo: identifica por igualdad/similitud de bytes de la plantilla.
 * Activa con {@code app.biometric.provider=sim}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.biometric.provider", havingValue = "sim")
public class SimBiometricMatcher implements BiometricMatcher {

    private final FingerprintRepository fingerprintRepository;
    private final Map<Long, Entry> index = new ConcurrentHashMap<>();

    private record Entry(long employeeId, byte[] template) {}

    public SimBiometricMatcher(FingerprintRepository fingerprintRepository) {
        this.fingerprintRepository = fingerprintRepository;
    }

    @PostConstruct
    public void init() {
        rebuildIndex();
        log.warn("Motor biométrico en modo SIMULADO (sin SDK ZK9500).");
    }

    @Override
    public void rebuildIndex() {
        index.clear();
        for (Fingerprint fp : fingerprintRepository.findByActiveTrue()) {
            index.put(fp.getId(), new Entry(fp.getEmployee().getId(), fp.getTemplate()));
        }
    }

    @Override
    public void enroll(long fingerprintId, long employeeId, byte[] template) {
        index.put(fingerprintId, new Entry(employeeId, template));
    }

    @Override
    public void remove(long fingerprintId) {
        index.remove(fingerprintId);
    }

    @Override
    public Optional<MatchResult> identify(byte[] probeTemplate) {
        return index.entrySet().stream()
                .filter(e -> Arrays.equals(e.getValue().template(), probeTemplate))
                .map(e -> new MatchResult(e.getValue().employeeId(), e.getKey(), 100))
                .findFirst();
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public int indexSize() {
        return index.size();
    }
}
