package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.repository.ConsumptionRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre el flujo de "retira por otro": un empleado (Pepe) registra consumos a
 * nombre de varios titulares (Juan, Luis). Verifica que se crea una fila de
 * {@code consumption} por titular/comida, con {@code method='MANUAL'},
 * {@code proxy_employee_id=Pepe} y {@code observation="<Pepe> retira de <Titular>"}
 * autogenerada.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManualProxyScanServiceTest {

    @Autowired private ScanService scanService;
    @Autowired private ConsumptionRepository consumptionRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RestaurantRepository restaurantRepository;

    private Employee pepe;
    private Employee juan;
    private Employee luis;
    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        restaurant = restaurantRepository.save(Restaurant.builder()
                .name("Comedor Test " + UUID.randomUUID())
                .active(true)
                .maxDevices(2)
                .build());

        pepe = employeeRepository.save(Employee.builder()
                .identityCard("P-" + UUID.randomUUID().toString().substring(0, 8))
                .fullName("Pepe")
                .status(EmployeeStatus.ACTIVE)
                .allowsLunch(true)
                .allowsSnack(true)
                .deleted(false)
                .build());

        juan = employeeRepository.save(Employee.builder()
                .identityCard("J-" + UUID.randomUUID().toString().substring(0, 8))
                .fullName("Juan")
                .status(EmployeeStatus.ACTIVE)
                .allowsLunch(true)
                .allowsSnack(true)
                .deleted(false)
                .build());

        luis = employeeRepository.save(Employee.builder()
                .identityCard("L-" + UUID.randomUUID().toString().substring(0, 8))
                .fullName("Luis")
                .status(EmployeeStatus.ACTIVE)
                .allowsLunch(true)
                .allowsSnack(true)
                .deleted(false)
                .build());
    }

    @Test
    void manualScan_pepeRetiraDeJuanYMaria_creaUnaFilaPorTitularConObservacionAutogenerada() {
        ManualScanRequest req = new ManualScanRequest(
                pepe.getId(),
                restaurant.getId(),
                List.of(
                        new ManualScanItem(juan.getId(), List.of("BREAKFAST", "LUNCH")), // Almuerzo + Merienda
                        new ManualScanItem(luis.getId(), List.of("BREAKFAST"))         // solo Almuerzo
                ));

        ManualScanResponse res = scanService.manualScan(req);

        assertThat(res.status()).isEqualTo("SUCCESS");
        assertThat(res.created()).isEqualTo(3);
        assertThat(res.employeeName()).isEqualTo("Pepe");

        var consumptions = consumptionRepository.findAll().stream().toList();
        assertThat(consumptions).hasSize(3);
        assertThat(consumptions).allSatisfy(c -> {
            assertThat(c.getMethod()).isEqualTo(Method.MANUAL);
            assertThat(c.getProxyEmployee()).isNotNull();
            assertThat(c.getProxyEmployee().getId()).isEqualTo(pepe.getId());
            assertThat(c.getObservation()).startsWith("Pepe retira de ");
        });

        long juanRows = consumptions.stream()
                .filter(c -> c.getEmployee().getId().equals(juan.getId())).count();
        long luisRows = consumptions.stream()
                .filter(c -> c.getEmployee().getId().equals(luis.getId())).count();
        assertThat(juanRows).isEqualTo(2);
        assertThat(luisRows).isEqualTo(1);

        // La observacion del titular Juan referencia a Pepe, no al titular mismo
        consumptions.stream()
                .filter(c -> c.getEmployee().getId().equals(juan.getId()))
                .forEach(c -> assertThat(c.getObservation()).isEqualTo("Pepe retira de Juan"));
    }

    @Test
    void manualScan_sinTitulares_retornaError() {
        ManualScanRequest req = new ManualScanRequest(
                pepe.getId(), restaurant.getId(), List.of());
        ManualScanResponse res = scanService.manualScan(req);
        assertThat(res.status()).isEqualTo("ERROR");
        assertThat(res.created()).isEqualTo(0);
    }
}