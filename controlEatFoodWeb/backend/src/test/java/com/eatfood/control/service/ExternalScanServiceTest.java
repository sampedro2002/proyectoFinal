package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre el flujo de registro de personas externas: el titular del consumo vive en
 * la tabla persona_externa (SEPARADA de empleado), se reutiliza por cédula, se
 * rechaza la cédula de un empleado y los consumos externos no alteran la lista de
 * "empleados pendientes del día".
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalScanServiceTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guayaquil");

    @Autowired private ScanService scanService;
    @Autowired private ConsumptionRepository consumptionRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ExternalPersonRepository externalPersonRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private ScheduleRepository scheduleRepository;

    private Restaurant restaurant;
    private Employee empleado;

    @BeforeEach
    void setUp() {
        // Horario que cubre cualquier hora, igual que en ManualProxyScanServiceTest:
        // en el contexto de test no hay Schedule sembrado y registerExternal cortaría
        // con OUT_OF_SCHEDULE.
        scheduleRepository.save(Schedule.builder()
                .startTime(LocalTime.MIN)
                .endTime(LocalTime.of(23, 59, 59))
                .active(true)
                .build());

        restaurant = restaurantRepository.save(Restaurant.builder()
                .name("Comedor Test " + UUID.randomUUID())
                .active(true)
                .maxDevices(2)
                .build());

        empleado = employeeRepository.save(Employee.builder()
                .identityCard("E-" + UUID.randomUUID().toString().substring(0, 8))
                .fullName("Empleado Interno")
                .status(EmployeeStatus.ACTIVE)
                .allowsLunch(true)
                .allowsSnack(true)
                .deleted(false)
                .build());
    }

    private ExternalScanRequest externalReq(String card, String name, String mealCode) {
        // isPassport=true: omite la validación de cédula ecuatoriana y permite
        // documentos de prueba arbitrarios.
        return new ExternalScanRequest(card, true, name, mealCode, restaurant.getId(), null, null, null);
    }

    @Test
    void registerExternal_creaPersonaExternaYConsumoSinEmpleado() {
        ManualScanResponse res = scanService.registerExternal(
                externalReq("EXT-001", "Visitante Uno", "BREAKFAST"));

        assertThat(res.status()).isEqualTo("SUCCESS");

        // La persona externa se creó en SU tabla, no en empleado.
        ExternalPerson person = externalPersonRepository.findByIdentityCard("EXT-001").orElseThrow();
        assertThat(person.getFullName()).isEqualTo("Visitante Uno");
        assertThat(employeeRepository.findByIdentityCard("EXT-001")).isEmpty();

        List<Consumption> consumos = consumptionRepository.findAll();
        assertThat(consumos).hasSize(1);
        Consumption c = consumos.get(0);
        assertThat(c.getMethod()).isEqualTo(Method.EXTERNAL);
        assertThat(c.getEmployee()).isNull();                    // ← nunca empleado
        assertThat(c.getExternalPerson().getId()).isEqualTo(person.getId());
        assertThat(c.getMealName()).isEqualTo("Almuerzo");
        assertThat(c.titularName()).isEqualTo("Visitante Uno");
        assertThat(c.titularIdentityCard()).isEqualTo("EXT-001");
    }

    @Test
    void registerExternal_mismaCedula_reutilizaLaMismaPersonaExterna() {
        assertThat(scanService.registerExternal(externalReq("EXT-002", "Visitante Dos", "BREAKFAST")).status())
                .isEqualTo("SUCCESS");
        assertThat(scanService.registerExternal(externalReq("EXT-002", "Visitante Dos", "LUNCH")).status())
                .isEqualTo("SUCCESS");

        assertThat(externalPersonRepository.findAll()).hasSize(1);
        assertThat(consumptionRepository.findAll()).hasSize(2);
    }

    @Test
    void registerExternal_mismoPlatoElMismoDia_rechazaDuplicado() {
        assertThat(scanService.registerExternal(externalReq("EXT-003", "Visitante Tres", "BREAKFAST")).status())
                .isEqualTo("SUCCESS");

        ManualScanResponse dup = scanService.registerExternal(externalReq("EXT-003", "Visitante Tres", "BREAKFAST"));

        assertThat(dup.status()).isEqualTo("DUPLICATE");
        assertThat(consumptionRepository.findAll()).hasSize(1);
    }

    @Test
    void registerExternal_cedulaDeEmpleado_rechazaSinCrearNada() {
        ManualScanResponse res = scanService.registerExternal(
                externalReq(empleado.getIdentityCard(), "Cualquier Nombre", "BREAKFAST"));

        assertThat(res.status()).isEqualTo("IS_EMPLOYEE");
        assertThat(externalPersonRepository.findAll()).isEmpty();
        assertThat(consumptionRepository.findAll()).isEmpty();
    }

    @Test
    void registerExternal_consumoExternoNoAfectaPendientesDelDia() {
        // Un consumo externo (empleado_id NULL) no debe sacar a nadie de la lista de
        // pendientes ni romper el NOT IN de findActiveNotConsumed.
        assertThat(scanService.registerExternal(externalReq("EXT-004", "Visitante Cuatro", "BREAKFAST")).status())
                .isEqualTo("SUCCESS");

        LocalDate hoy = LocalDate.now(BUSINESS_ZONE);
        List<Employee> pendientes = employeeRepository.findActiveNotConsumed(EmployeeStatus.ACTIVE, hoy);

        assertThat(pendientes).extracting(Employee::getId).contains(empleado.getId());
    }

    // ── Persona externa como APODERADA (quien retira) ────────────────────────

    private ExternalPerson registrarExterna(String card, String name) {
        return externalPersonRepository.save(ExternalPerson.builder()
                .identityCard(card).fullName(name).build());
    }

    @Test
    void registerExternal_retiradoPorOtraPersonaExterna_registraConApoderadaExterna() {
        ExternalPerson ana = registrarExterna("EXT-100", "Ana Externa");

        ManualScanResponse res = scanService.registerExternal(new ExternalScanRequest(
                "EXT-101", true, "Bruno Externo", "BREAKFAST", restaurant.getId(),
                null, null, ana.getId()));

        assertThat(res.status()).isEqualTo("SUCCESS");
        Consumption c = consumptionRepository.findAll().get(0);
        assertThat(c.getProxyExternalPerson()).isNotNull();
        assertThat(c.getProxyExternalPerson().getId()).isEqualTo(ana.getId());
        assertThat(c.getProxyEmployee()).isNull();
        assertThat(c.getObservation()).isEqualTo("Ana Externa retira de Bruno Externo");
        assertThat(c.proxyName()).isEqualTo("Ana Externa");
        assertThat(c.proxyIsExternal()).isTrue();
    }

    @Test
    void registerExternal_apoderadaExternaNoRegistrada_rechaza() {
        ManualScanResponse res = scanService.registerExternal(new ExternalScanRequest(
                "EXT-102", true, "Bruno Externo", "BREAKFAST", restaurant.getId(),
                null, null, 999999L));

        assertThat(res.status()).isEqualTo("NOT_FOUND");
        assertThat(consumptionRepository.findAll()).isEmpty();
    }

    @Test
    void registerExternal_apoderadaExternaIgualAlTitular_rechaza() {
        ExternalPerson ana = registrarExterna("EXT-103", "Ana Externa");

        ManualScanResponse res = scanService.registerExternal(new ExternalScanRequest(
                "EXT-103", true, "Ana Externa", "BREAKFAST", restaurant.getId(),
                null, null, ana.getId()));

        assertThat(res.status()).isEqualTo("ERROR");
        assertThat(consumptionRepository.findAll()).isEmpty();
    }

    @Test
    void manualScan_personaExternaRetiraPorEmpleados_creaFilasConApoderadaExterna() {
        ExternalPerson ana = registrarExterna("EXT-104", "Ana Externa");

        ManualScanRequest req = new ManualScanRequest(
                null, ana.getId(), restaurant.getId(),
                List.of(new ManualScanItem(empleado.getId(), List.of("BREAKFAST"))));

        ManualScanResponse res = scanService.manualScan(req);

        assertThat(res.status()).isEqualTo("SUCCESS");
        assertThat(res.created()).isEqualTo(1);
        Consumption c = consumptionRepository.findAll().get(0);
        assertThat(c.getMethod()).isEqualTo(Method.MANUAL);
        assertThat(c.getEmployee().getId()).isEqualTo(empleado.getId());
        assertThat(c.getProxyExternalPerson().getId()).isEqualTo(ana.getId());
        assertThat(c.getProxyEmployee()).isNull();
        assertThat(c.getObservation()).isEqualTo("Ana Externa retira de " + empleado.getFullName());
    }

    @Test
    void manualScan_personaExternaNoPuedeRetirarDeSiMisma() {
        // Misma cédula en empleado y persona externa (caso borde: externa registrada
        // antes de ser contratada). Se detecta por cédula y se omite al titular.
        ExternalPerson misma = registrarExterna(empleado.getIdentityCard(), empleado.getFullName());

        ManualScanRequest req = new ManualScanRequest(
                null, misma.getId(), restaurant.getId(),
                List.of(new ManualScanItem(empleado.getId(), List.of("BREAKFAST"))));

        ManualScanResponse res = scanService.manualScan(req);

        assertThat(res.status()).isEqualTo("ERROR");
        assertThat(res.created()).isEqualTo(0);
        assertThat(consumptionRepository.findAll()).isEmpty();
    }

    @Test
    void manualScan_conAmbosApoderados_rechaza() {
        ExternalPerson ana = registrarExterna("EXT-105", "Ana Externa");

        ManualScanRequest req = new ManualScanRequest(
                empleado.getId(), ana.getId(), restaurant.getId(),
                List.of(new ManualScanItem(empleado.getId(), List.of("BREAKFAST"))));

        ManualScanResponse res = scanService.manualScan(req);

        assertThat(res.status()).isEqualTo("ERROR");
        assertThat(consumptionRepository.findAll()).isEmpty();
    }
}
