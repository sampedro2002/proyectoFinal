package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Regresión de fechas administrativas; usa únicamente la base H2 del perfil test. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManualConsumptionDatesTest {
    @Autowired private ScanService scanService;
    @Autowired private ManualConsumptionService manualService;
    @Autowired private ReportService reportService;
    @Autowired private ConsumptionRepository consumptions;
    @Autowired private EmployeeRepository employees;
    @Autowired private ExternalPersonRepository externals;
    @Autowired private RestaurantRepository restaurants;
    @Autowired private AuditLogRepository audits;

    private Employee titular;
    private Employee proxy;
    private Restaurant restaurant;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ManualConsumptionPolicy.ZONE);
        titular = employee("Titular Prueba");
        proxy = employee("Apoderado Prueba");
        restaurant = restaurants.save(Restaurant.builder().name("Fechas " + UUID.randomUUID()).build());
    }

    private Employee employee(String name) {
        return employees.save(Employee.builder().identityCard(UUID.randomUUID().toString().substring(0, 20))
                .fullName(name).allowsLunch(true).allowsSnack(true).build());
    }

    private ManualScanRequest request(LocalDate date) {
        return new ManualScanRequest(proxy.getId(), null, restaurant.getId(),
                List.of(new ManualScanItem(titular.getId(), null, List.of("BREAKFAST"))),
                date, LocalTime.of(12, 30), true, "Corte de luz");
    }

    private Consumption saved(LocalDate date, String meal, boolean cancelled) {
        return consumptions.save(Consumption.builder().employee(titular).restaurant(restaurant)
                .businessDate(date).consumedAt(date.atTime(12, 30).atZone(ManualConsumptionPolicy.ZONE).toOffsetDateTime())
                .method(Method.MANUAL).mealName(meal).cancelled(cancelled).clientUuid(UUID.randomUUID()).build());
    }

    private UpdateManualConsumptionRequest edit(LocalDate date, String meal) {
        return new UpdateManualConsumptionRequest(null, null, null, null, meal, null,
                date, LocalTime.of(13, 15), true, "Corrección de la hoja de control");
    }

    @Test
    void registersPastConsumptionInItsReportAndAuditsActualEntry() {
        LocalDate date = today.minusDays(2);
        assertThat(scanService.manualScan(request(date)).status()).isEqualTo("SUCCESS");
        var rows = reportService.consumptions(date, date, restaurant.getId(), titular.getId(), null, false);
        assertThat(rows).hasSize(1);
        Consumption c = consumptions.findById(rows.get(0).id()).orElseThrow();
        assertThat(c.getBusinessDate()).isEqualTo(date);
        assertThat(c.getConsumedAt().atZoneSameInstant(ManualConsumptionPolicy.ZONE).toLocalTime())
                .isEqualTo(LocalTime.of(12, 30));
        assertThat(c.getCreatedAt().atZoneSameInstant(ManualConsumptionPolicy.ZONE).toLocalDate()).isEqualTo(today);
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getEntityId()).isEqualTo(c.getId().toString());
            assertThat(a.getAction()).isEqualTo("CREATE");
            assertThat(a.getNewValue()).contains("Corte de luz", "fecha=" + date, "contingencia=true");
        });
        assertThat(reportService.consumptions(today, today, restaurant.getId(), titular.getId(), null, false)).isEmpty();
    }

    @Test
    void futureConsumptionBlocksOnlyThatDate() {
        LocalDate future = today.plusDays(3);
        assertThat(scanService.manualScan(request(future)).status()).isEqualTo("SUCCESS");
        assertThat(scanService.manualScan(request(future)).status()).isEqualTo("DUPLICATE");
        assertThat(scanService.mealAvailability(titular.getId(), future, null).hadAlmuerzo()).isTrue();
        assertThat(scanService.mealAvailability(titular.getId(), today, null).hadAlmuerzo()).isFalse();
    }

    @Test
    void movesPastConsumptionAndPreservesCreationTime() {
        Consumption c = saved(today.minusDays(2), "Almuerzo", false);
        OffsetDateTime created = c.getCreatedAt();
        LocalDate target = today.plusDays(2);
        var result = manualService.update(c.getId(), edit(target, "Almuerzo"));
        assertThat(result.businessDate()).isEqualTo(target.toString());
        assertThat(c.getCreatedAt()).isEqualTo(created);
        assertThat(manualService.listManual(null, restaurant.getId(), false, target, PageRequest.of(0, 20)).getContent())
                .extracting(ConsumptionDetailResponse::id).contains(c.getId());
        assertThat(scanService.mealAvailability(titular.getId(), target, c.getId()).hadAlmuerzo()).isFalse();
        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("UPDATE");
            assertThat(a.getOldValue()).contains("fecha=" + today.minusDays(2));
            assertThat(a.getNewValue()).contains("fecha=" + target, "Corrección de la hoja de control");
        });
    }

    @Test
    void rejectsMovingIntoOccupiedDateEvenWhenMealDoesNotChange() {
        Consumption original = saved(today.minusDays(2), "Almuerzo", false);
        LocalDate target = today.plusDays(2);
        saved(target, "Almuerzo", false);
        assertThatThrownBy(() -> manualService.update(original.getId(), edit(target, "Almuerzo")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya tiene Almuerzo");
    }

    @Test
    void availabilityExcludesOwnRecordAndAllowsKeepingItsMeal() {
        Consumption c = saved(today.minusDays(1), "Almuerzo", false);
        assertThat(scanService.mealAvailability(titular.getId(), c.getBusinessDate(), c.getId()).hadAlmuerzo()).isFalse();
        assertThat(scanService.mealAvailability(titular.getId(), c.getBusinessDate(), null).hadAlmuerzo()).isTrue();
        manualService.update(c.getId(), edit(c.getBusinessDate(), "Almuerzo"));
    }

    @Test
    void cannotReactivateAnOccupiedMeal() {
        LocalDate date = today.minusDays(2);
        Consumption cancelled = saved(date, "Almuerzo", true);
        saved(date, "Almuerzo", false);
        assertThatThrownBy(() -> manualService.uncancel(cancelled.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya tiene Almuerzo");
    }

    @Test
    void externalPastConsumptionAlsoChecksDateAndDuplicates() {
        LocalDate date = today.minusDays(4);
        String card = "EXT-" + UUID.randomUUID().toString().substring(0, 12);
        var req = new ExternalScanRequest(card, true, "Visitante Prueba", "BREAKFAST", restaurant.getId(),
                null, null, null, date, LocalTime.NOON, true, "Caída de internet");
        assertThat(scanService.registerExternal(req).status()).isEqualTo("SUCCESS");
        assertThat(scanService.registerExternal(req).status()).isEqualTo("DUPLICATE");
        ExternalPerson external = externals.findByIdentityCard(card).orElseThrow();
        assertThat(consumptions.findActiveMeals(null, external.getId(), date, null)).containsExactly("Almuerzo");
        assertThat(consumptions.findActiveMeals(null, external.getId(), today, null)).isEmpty();
    }

    @Test
    void fingerprintDetailsRemainReadOnly() {
        Consumption c = saved(today.minusDays(2), "Almuerzo", false);
        c.setMethod(Method.FINGERPRINT);
        assertThatThrownBy(() -> manualService.update(c.getId(), edit(today, "Almuerzo")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Solo se pueden editar");
    }
}
