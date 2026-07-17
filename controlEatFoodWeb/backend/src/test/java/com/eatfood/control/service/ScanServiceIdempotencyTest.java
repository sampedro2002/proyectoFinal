package com.eatfood.control.service;

import com.eatfood.control.biometric.BiometricMatcher;
import com.eatfood.control.domain.Device;
import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.EmployeeStatus;
import com.eatfood.control.domain.Restaurant;
import com.eatfood.control.dto.ScanDtos.ScanRequest;
import com.eatfood.control.dto.ScanDtos.ScanResponse;
import com.eatfood.control.repository.ConsumptionRepository;
import com.eatfood.control.repository.DeviceRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.RestaurantRepository;
import com.eatfood.control.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Cubre la idempotencia de {@link ScanService#scan} respecto a {@code clientUuid}:
 * reenviar el mismo escaneo (reintento de red, sincronización offline) nunca debe
 * crear un segundo registro de consumo.
 *
 * <p>El SDK biométrico y la validación de sesión del dispositivo son fronteras de
 * hardware/infra ajenas a esta lógica de negocio, por eso se mockean; todo lo
 * demás (empleado, restaurante, horario, consumo) es una fila real en H2.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional // aisla cada test: revierte al final para que "count()" no arrastre datos entre metodos
class ScanServiceIdempotencyTest {

    @Autowired private ScanService scanService;
    @Autowired private ConsumptionRepository consumptionRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private ScheduleRepository scheduleRepository;

    @MockBean private BiometricMatcher biometricMatcher;
    @MockBean private DeviceService deviceService;

    private Employee employee;
    private Device device;
    private static final OffsetDateTime FIXED_NOW = OffsetDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.of("-05:00"));

    @BeforeEach
    void setUp() {
        // Horario que cubre cualquier hora, para que el test no dependa del reloj real.
        scheduleRepository.save(com.eatfood.control.domain.Schedule.builder()
                .startTime(LocalTime.MIN)
                .endTime(LocalTime.of(23, 59, 59))
                .active(true)
                .build());

        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .name("Restaurante Test " + UUID.randomUUID())
                .active(true)
                .maxDevices(2)
                .build());

        device = deviceRepository.save(Device.builder()
                .restaurant(restaurant)
                .deviceUid("device-" + UUID.randomUUID())
                .connected(true)
                .build());

        employee = employeeRepository.save(Employee.builder()
                .identityCard("ID-" + UUID.randomUUID().toString().substring(0, 8)) // encaja en VARCHAR(20)
                .fullName("Empleado de prueba")
                .status(EmployeeStatus.ACTIVE)
                .allowsLunch(true)
                .allowsSnack(true)
                .deleted(false)
                .build());

        when(deviceService.validateSession(anyString())).thenReturn(device);
        when(biometricMatcher.identify(any()))
                .thenReturn(Optional.of(new BiometricMatcher.MatchResult(employee.getId(), 1L, 90)));
    }

    private ScanRequest requestWith(UUID clientUuid) {
        String fakeTemplate = Base64.getEncoder().encodeToString("plantilla-simulada".getBytes());
        return new ScanRequest("any-session-token", fakeTemplate, null, clientUuid, false, FIXED_NOW);
    }

    @Test
    void scan_calledTwiceWithSameClientUuid_createsOnlyOneConsumption() {
        UUID clientUuid = UUID.randomUUID();

        ScanResponse first = scanService.scan(requestWith(clientUuid));
        ScanResponse second = scanService.scan(requestWith(clientUuid));

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(second.status()).isEqualTo("SUCCESS");
        assertThat(consumptionRepository.findByClientUuid(clientUuid)).isPresent();
        assertThat(consumptionRepository.count()).isEqualTo(1);
    }

    @Test
    void scan_withDifferentClientUuids_createsSeparateConsumptions() {
        // Primer escaneo: Almuerzo. El empleado ya no tiene mas comidas disponibles hoy
        // salvo Merienda, que tambien se agota en el segundo escaneo -- confirma que dos
        // clientUuid DISTINTOS no se tratan como duplicados entre si.
        ScanResponse first = scanService.scan(requestWith(UUID.randomUUID()));
        ScanResponse second = scanService.scan(requestWith(UUID.randomUUID()));

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(second.status()).isEqualTo("SUCCESS");
        assertThat(consumptionRepository.count()).isEqualTo(2);
    }
}
