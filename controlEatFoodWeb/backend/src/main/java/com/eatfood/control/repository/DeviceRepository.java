package com.eatfood.control.repository;

import com.eatfood.control.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByRestaurantIdAndDeviceUid(Long restaurantId, String deviceUid);
    long countByRestaurantIdAndConnectedTrue(Long restaurantId);
    List<Device> findByRestaurantId(Long restaurantId);
    Optional<Device> findBySessionToken(String sessionToken);
    List<Device> findByRestaurantIdAndConnectedTrueAndLastSeenBefore(Long restaurantId, OffsetDateTime cutoff);
}
