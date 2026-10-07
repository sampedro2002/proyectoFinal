package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.repository.ConsumptionRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.RestaurantRepository;
import com.eatfood.control.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
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
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private com.eatfood.control.repository.ExternalPersonRepository externalPersonRepository;

    private Employee pepe;
    private Employee juan;
    private Employee luis;
    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        // Horario que cubre cualquier hora, para que manualScan() no dependa del reloj real.
        // En el contexto de test Flyway esta deshabilitado y no se siembra ningun Schedule,
        // asi que sin esto scheduleRepository.findFirstByOrderByIdAsc() devuelve vacio y
        // manualScan corta con OUT_OF_SCHEDULE.
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
                null,
                restaurant.getId(),
                List.of(
                        new ManualScanItem(juan.getId(), null, List.of("BREAKFAST", "LUNCH")), // Almuerzo + Merienda
                        new ManualScanItem(luis.getId(), null, List.of("BREAKFAST"))         // solo Almuerzo
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
                pepe.getId(), null, restaurant.getId(), List.of());
        ManualScanResponse res = scanService.manualScan(req);
        assertThat(res.status()).isEqualTo("ERROR");
        assertThat(res.created()).isEqualTo(0);
    }

    @Test
    void manualScan_pepeRetiraParaSiMismo_creaConsumoSinApoderado() {
        ManualScanResponse res = scanService.manualScan(new ManualScanRequest(
                pepe.getId(), null, restaurant.getId(),
                List.of(new ManualScanItem(pepe.getId(), null, List.of("BREAKFAST")))));

        assertThat(res.status()).isEqualTo("SUCCESS");
        assertThat(res.created()).isEqualTo(1);
        var consumptions = consumptionRepository.findAll();
        assertThat(consumptions).hasSize(1);
        Consumption c = consumptions.get(0);
        assertThat(c.getEmployee().getId()).isEqualTo(pepe.getId());
        assertThat(c.getMethod()).isEqualTo(Method.MANUAL);
        assertThat(c.getProxyEmployee()).isNull();
        assertThat(c.getProxyExternalPerson()).isNull();
        assertThat(c.getObservation()).isEqualTo("Retira personalmente");
    }

    @Test
    void manualScan_pepeRetiraParaSiYParaJuan_soloElDeJuanLlevaApoderado() {
        ManualScanResponse res = scanService.manualScan(new ManualScanRequest(
                pepe.getId(), null, restaurant.getId(),
                List.of(
                        new ManualScanItem(pepe.getId(), null, List.of("BREAKFAST")),
                        new ManualScanItem(juan.getId(), null, List.of("BREAKFAST")))));

        assertThat(res.created()).isEqualTo(2);
        var consumptions = consumptionRepository.findAll();
        Consumption propio = consumptions.stream()
                .filter(c -> c.getEmployee().getId().equals(pepe.getId())).findFirst().orElseThrow();
        Consumption deJuan = consumptions.stream()
                .filter(c -> c.getEmployee().getId().equals(juan.getId())).findFirst().orElseThrow();
        assertThat(propio.getProxyEmployee()).isNull();
        assertThat(propio.getObservation()).isEqualTo("Retira personalmente");
        assertThat(deJuan.getProxyEmployee().getId()).isEqualTo(pepe.getId());
        assertThat(deJuan.getObservation()).isEqualTo("Pepe retira de Juan");
    }

    @Test
    void manualScan_comidaPropiaYaRegistrada_seOmiteComoDuplicado() {
        var item = List.of(new ManualScanItem(pepe.getId(), null, List.of("BREAKFAST")));
        scanService.manualScan(new ManualScanRequest(pepe.getId(), null, restaurant.getId(), item));

        ManualScanResponse res = scanService.manualScan(
                new ManualScanRequest(pepe.getId(), null, restaurant.getId(), item));

        assertThat(res.status()).isEqualTo("DUPLICATE");
        assertThat(res.created()).isEqualTo(0);
        assertThat(consumptionRepository.findAll()).hasSize(1);
    }

    @Test
    void apoderadoExternoCuentaTitularesDeAmbosTiposYExcluyeSuComida() {
        ExternalPerson proxy = externalPersonRepository.save(ExternalPerson.builder()
                .identityCard("PX-" + UUID.randomUUID().toString().substring(0, 8)).fullName("Apoderado externo").build());
        ExternalPerson titular = externalPersonRepository.save(ExternalPerson.builder()
                .identityCard("TX-" + UUID.randomUUID().toString().substring(0, 8)).fullName("Titular externo").build());
        var items = new java.util.ArrayList<>(titulares(9));
        items.add(new ManualScanItem(null, titular.getId(), List.of("BREAKFAST", "LUNCH")));
        items.add(new ManualScanItem(null, proxy.getId(), List.of("BREAKFAST")));
        var response = scanService.manualScan(new ManualScanRequest(null, proxy.getId(), restaurant.getId(), items));
        assertThat(response.created()).isEqualTo(12);
        assertThat(scanService.proxyUsage(null, proxy.getId(), null))
                .extracting(ProxyUsageResponse::used, ProxyUsageResponse::remaining).containsExactly(10, 0);
        assertThat(scanService.proxyUsage(pepe.getId(), null, null).used()).isZero();
        assertThat(scanService.manualScan(new ManualScanRequest(null, proxy.getId(), restaurant.getId(), titulares(1))).status())
                .isEqualTo("LIMIT_REACHED");
    }

    @Test
    void anularUnaComidaNoLiberaCupoSiQuedaOtraActiva() {
        registrar(List.of(new ManualScanItem(juan.getId(), null, List.of("BREAKFAST", "LUNCH"))));
        Consumption cancelled = consumptionRepository.findAll().get(0);
        cancelled.setCancelled(true);
        consumptionRepository.saveAndFlush(cancelled);
        assertThat(scanService.proxyUsage(pepe.getId(), null, null))
                .extracting(ProxyUsageResponse::used, ProxyUsageResponse::remaining).containsExactly(1, 9);
    }

    private List<ManualScanItem> titulares(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            Employee employee = employeeRepository.save(Employee.builder()
                    .identityCard("T-" + UUID.randomUUID().toString().substring(0, 8)).fullName("Titular " + i)
                    .status(EmployeeStatus.ACTIVE).allowsLunch(true).allowsSnack(true).deleted(false).build());
            return new ManualScanItem(employee.getId(), null, List.of("BREAKFAST"));
        }).toList();
    }

    private ManualScanResponse registrar(List<ManualScanItem> items) {
        return scanService.manualScan(new ManualScanRequest(pepe.getId(), null, restaurant.getId(), items));
    }

    @Test
    void diezTitularesYUndecimoEnOtroRegistro() {
        assertThat(registrar(titulares(10)).created()).isEqualTo(10);
        var response = registrar(titulares(1));
        assertThat(response.status()).isEqualTo("LIMIT_REACHED");
        assertThat(response.created()).isZero();
        assertThat(response.message()).contains("10 personas", "Pepe");
        assertThat(scanService.proxyUsage(pepe.getId(), null, null))
                .extracting(ProxyUsageResponse::used, ProxyUsageResponse::remaining).containsExactly(10, 0);
    }

    @Test
    void undecimoDentroDelMismoRequestSeOmite() {
        var response = registrar(titulares(11));
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.created()).isEqualTo(10);
        assertThat(response.message()).contains("Omitidos:", "10 personas");
    }

    @Test
    void meriendaDeTitularYaContadoNoConsumeOtroCupo() {
        var items = titulares(10);
        registrar(items);
        assertThat(registrar(List.of(new ManualScanItem(items.get(0).employeeId(), null, List.of("LUNCH")))).created()).isEqualTo(1);
        var usage = scanService.proxyUsage(pepe.getId(), null, null);
        assertThat(usage.used()).isEqualTo(10);
        assertThat(usage.titularKeys()).hasSize(10).contains("E:" + items.get(0).employeeId());
    }

    @Test
    void anuladosNoCuentanYLiberanCupo() {
        registrar(titulares(10));
        Consumption cancelled = consumptionRepository.findAll().get(0);
        cancelled.setCancelled(true);
        consumptionRepository.saveAndFlush(cancelled);
        assertThat(scanService.proxyUsage(pepe.getId(), null, null))
                .extracting(ProxyUsageResponse::used, ProxyUsageResponse::remaining).containsExactly(9, 1);
        assertThat(registrar(titulares(1)).created()).isEqualTo(1);
    }

    @Test
    void comidaPropiaNoCuentaInclusoConCupoCompleto() {
        registrar(titulares(10));
        assertThat(registrar(List.of(new ManualScanItem(pepe.getId(), null, List.of("BREAKFAST", "LUNCH")))).created()).isEqualTo(2);
        assertThat(scanService.proxyUsage(pepe.getId(), null, null).used()).isEqualTo(10);
    }

    @Test
    void contingenciaEnOtraFechaTieneSuContador() {
        registrar(titulares(10));
        var date = java.time.LocalDate.now(java.time.ZoneId.of("America/Guayaquil")).minusDays(1);
        var response = scanService.manualScan(new ManualScanRequest(pepe.getId(), null, restaurant.getId(),
                titulares(1), date, LocalTime.NOON, true, "Control en papel"));
        assertThat(response.created()).isEqualTo(1);
        assertThat(scanService.proxyUsage(pepe.getId(), null, date))
                .extracting(ProxyUsageResponse::used, ProxyUsageResponse::remaining).containsExactly(1, 9);
        assertThat(scanService.proxyUsage(pepe.getId(), null, null).used()).isEqualTo(10);
    }

    @Test
    void contadorVacioYValidacionDeApoderadoUnico() {
        assertThat(scanService.proxyUsage(pepe.getId(), null, null))
                .extracting(ProxyUsageResponse::used, ProxyUsageResponse::remaining).containsExactly(0, 10);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scanService.proxyUsage(null, null, null))
                .isInstanceOf(com.eatfood.control.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scanService.proxyUsage(pepe.getId(), 1L, null))
                .isInstanceOf(com.eatfood.control.exception.BusinessException.class);
    }
}
