package com.eatfood.control.repository;

import com.eatfood.control.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByCateringIdAndDeviceUid(Long cateringId, String deviceUid);
    long countByCateringIdAndConnectedTrue(Long cateringId);
    List<Device> findByCateringId(Long cateringId);
    Optional<Device> findBySessionToken(String sessionToken);
    List<Device> findByCateringIdAndConnectedTrueAndLastSeenBefore(Long cateringId, OffsetDateTime cutoff);
}
