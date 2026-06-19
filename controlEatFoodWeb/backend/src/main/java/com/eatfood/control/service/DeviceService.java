package com.eatfood.control.service;

import com.eatfood.control.domain.AppUser;
import com.eatfood.control.domain.Catering;
import com.eatfood.control.domain.Device;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    /** Dispositivos sin actividad por más de estos minutos se desconectan automáticamente. */
    private static final int STALE_MINUTES = 5;

    private final DeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional
    public DeviceConnectResponse connect(DeviceConnectRequest req) {
        AppUser user = userRepository.findByUsername(req.cateringUsername())
                .orElseThrow(() -> new BusinessException("INVALID_CREDENTIALS", "Credenciales de catering inválidas."));
        if (!passwordEncoder.matches(req.cateringPassword(), user.getPasswordHash())) {
            throw new BusinessException("INVALID_CREDENTIALS", "Credenciales de catering inválidas.");
        }
        Catering catering = user.getCatering();
        if (catering == null || !catering.isActive()) {
            throw new BusinessException("NO_CATERING", "El usuario no está asociado a un catering activo.");
        }

        // ── Limpiar dispositivos fantasma (sin actividad reciente) ────────────
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(STALE_MINUTES);
        List<Device> staleDevices = deviceRepository
                .findByCateringIdAndConnectedTrueAndLastSeenBefore(catering.getId(), cutoff);
        for (Device stale : staleDevices) {
            log.info("[DEVICE] Auto-desconectando dispositivo stale: id={}, uid='{}', lastSeen={}",
                    stale.getId(), stale.getDeviceUid(), stale.getLastSeen());
            stale.setConnected(false);
            stale.setSessionToken(null);
            deviceRepository.save(stale);
            auditService.record("Device", String.valueOf(stale.getId()), "AUTO_DISCONNECT", null,
                    "stale >" + STALE_MINUTES + "min");
        }

        Device device = deviceRepository.findByCateringIdAndDeviceUid(catering.getId(), req.deviceUid())
                .orElseGet(() -> Device.builder()
                        .catering(catering)
                        .deviceUid(req.deviceUid())
                        .name(req.deviceName())
                        .build());

        // Control de máximo de dispositivos simultáneos (no cuenta el propio si reconecta)
        long connected = deviceRepository.countByCateringIdAndConnectedTrue(catering.getId());
        boolean alreadyConnected = device.getId() != null && device.isConnected();
        if (!alreadyConnected && connected >= catering.getMaxDevices()) {
            log.warn("[DEVICE] Límite alcanzado: cateringId={}, conectados={}, máx={}",
                    catering.getId(), connected, catering.getMaxDevices());
            throw new BusinessException("DEVICE_LIMIT",
                    "Se alcanzó el límite máximo de dispositivos permitidos.");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        device.setSessionToken(token);
        device.setConnected(true);
        device.setLastSeen(OffsetDateTime.now());
        if (req.deviceName() != null) device.setName(req.deviceName());
        device = deviceRepository.save(device);

        log.info("[DEVICE] Conectado: id={}, uid='{}', catering='{}'",
                device.getId(), req.deviceUid(), catering.getName());

        auditService.record("Device", String.valueOf(device.getId()), "CONNECT", null,
                "catering=" + catering.getId() + ", uid=" + req.deviceUid());

        return new DeviceConnectResponse(catering.getId(), catering.getName(), device.getId(), token);
    }

    @Transactional
    public void disconnect(String sessionToken) {
        deviceRepository.findBySessionToken(sessionToken).ifPresent(d -> {
            d.setConnected(false);
            d.setSessionToken(null);
            deviceRepository.save(d);
            auditService.record("Device", String.valueOf(d.getId()), "DISCONNECT", null, null);
        });
    }

    @Transactional
    public Device validateSession(String sessionToken) {
        Device device = deviceRepository.findBySessionToken(sessionToken)
                .orElseThrow(() -> new BusinessException("INVALID_SESSION", "Sesión de dispositivo inválida."));
        if (!device.isConnected()) {
            throw new BusinessException("INVALID_SESSION", "El dispositivo no está conectado.");
        }
        device.setLastSeen(OffsetDateTime.now());
        return deviceRepository.save(device);
    }
}
