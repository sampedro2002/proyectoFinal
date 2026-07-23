package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.ConsumptionRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManualConsumptionService {

    private final ConsumptionRepository consumptionRepository;
    private final EmployeeRepository employeeRepository;
    private final RestaurantRepository restaurantRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ConsumptionDetailResponse> listManual(String search, Long restaurantId, Boolean cancelled, Pageable pageable) {
        return consumptionRepository.findManualConsumptions(search, restaurantId, cancelled, pageable)
                .map(this::toDetail);
    }

    @Transactional(readOnly = true)
    public ConsumptionDetailResponse getById(Long id) {
        Consumption c = consumptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consumo no encontrado: " + id));
        return toDetail(c);
    }

    @Transactional
    public ConsumptionDetailResponse update(Long id, UpdateManualConsumptionRequest req) {
        Consumption c = consumptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consumo no encontrado: " + id));

        if (c.getMethod() != Method.MANUAL && c.getMethod() != Method.EXTERNAL) {
            throw new BusinessException("NOT_MANUAL", "Solo se pueden editar consumos manuales o externos.");
        }

        // Validar que el apoderado no sea el mismo que el titular
        if (req.proxyEmployeeId() != null && req.proxyEmployeeId().equals(
                req.employeeId() != null ? req.employeeId() : c.getEmployee().getId())) {
            throw new BusinessException("SAME_PERSON",
                    "El empleado que retira no puede ser el mismo que el titular.");
        }

        String before = snapshot(c);

        if (req.restaurantId() != null) {
            Restaurant r = restaurantRepository.findById(req.restaurantId())
                    .orElseThrow(() -> new NotFoundException("Restaurante no encontrado: " + req.restaurantId()));
            c.setRestaurant(r);
        }

        if (req.proxyEmployeeId() != null) {
            Employee proxy = employeeRepository.findById(req.proxyEmployeeId())
                    .orElseThrow(() -> new NotFoundException("Empleado no encontrado: " + req.proxyEmployeeId()));
            if (proxy.getStatus() != com.eatfood.control.domain.EmployeeStatus.ACTIVE) {
                throw new BusinessException("INACTIVE_EMPLOYEE", "El empleado apoderado seleccionado está inactivo.");
            }
            c.setProxyEmployee(proxy);
        }

        boolean titularChanged = req.employeeId() != null && !req.employeeId().equals(c.getEmployee().getId());
        if (titularChanged) {
            Employee newTitular = employeeRepository.findById(req.employeeId())
                    .orElseThrow(() -> new NotFoundException("Empleado no encontrado: " + req.employeeId()));
            if (newTitular.getStatus() != com.eatfood.control.domain.EmployeeStatus.ACTIVE) {
                throw new BusinessException("INACTIVE_EMPLOYEE", "El nuevo empleado titular seleccionado está inactivo.");
            }

            String mealToCheck = req.mealName() != null ? req.mealName() : c.getMealName();
            boolean allowed = "Merienda".equals(mealToCheck) ? newTitular.effectiveSnack() : newTitular.isAllowsLunch();
            if (!allowed) {
                throw new BusinessException("NOT_ALLOWED",
                        newTitular.getFullName() + " no tiene permitido " + mealToCheck);
            }

            List<String> consumedToday = consumptionRepository
                    .findMealNamesByEmployeeIdAndBusinessDate(newTitular.getId(), c.getBusinessDate());
            if (consumedToday.contains(mealToCheck)) {
                throw new BusinessException("DUPLICATE",
                        newTitular.getFullName() + " ya tiene " + mealToCheck + " registrado hoy");
            }

            c.setEmployee(newTitular);
            String proxyName = c.getProxyEmployee() != null ? c.getProxyEmployee().getFullName() : "Admin";
            c.setObservation(proxyName + " retira de " + newTitular.getFullName());
        }

        if (req.mealName() != null && !req.mealName().equals(c.getMealName())) {
            // Si cambió el titular, el bloque anterior ya validó permiso y duplicado con
            // la comida nueva. Si solo cambia la comida (mismo titular), hay que validar
            // aquí para no crear un plato no permitido ni un duplicado del día.
            if (!titularChanged) {
                Employee titular = c.getEmployee();
                boolean allowed = "Merienda".equals(req.mealName()) ? titular.effectiveSnack() : titular.isAllowsLunch();
                if (!allowed) {
                    throw new BusinessException("NOT_ALLOWED",
                            titular.getFullName() + " no tiene permitido " + req.mealName());
                }
                List<String> consumedToday = consumptionRepository
                        .findMealNamesByEmployeeIdAndBusinessDate(titular.getId(), c.getBusinessDate());
                if (consumedToday.contains(req.mealName())) {
                    throw new BusinessException("DUPLICATE",
                            titular.getFullName() + " ya tiene " + req.mealName() + " registrado hoy");
                }
            }
            c.setMealName(req.mealName());
        }

        if (req.observation() != null) {
            c.setObservation(req.observation());
        }

        c = consumptionRepository.save(c);
        auditService.record("Consumption", String.valueOf(id), "UPDATE", before, snapshot(c));
        log.info("[MANUAL-EDIT] ✓ consumoId={} actualizado", id);
        return toDetail(c);
    }

    @Transactional
    public void cancel(Long id) {
        Consumption c = consumptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consumo no encontrado: " + id));
        c.setCancelled(true);
        consumptionRepository.save(c);
        auditService.record("Consumption", String.valueOf(id), "CANCEL", null, "cancelado=true");
        log.info("[MANUAL-CANCEL] ✓ consumoId={} cancelado", id);
    }

    @Transactional
    public void uncancel(Long id) {
        Consumption c = consumptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consumo no encontrado: " + id));
        c.setCancelled(false);
        consumptionRepository.save(c);
        auditService.record("Consumption", String.valueOf(id), "UNCANCEL", null, "cancelado=false");
        log.info("[MANUAL-UNCANCEL] ✓ consumoId={} reactivado", id);
    }

    private ConsumptionDetailResponse toDetail(Consumption c) {
        Employee e = c.getEmployee();
        Employee p = c.getProxyEmployee();
        return new ConsumptionDetailResponse(
                c.getId(),
                e.getId(), e.getFullName(), e.getIdentityCard(),
                p != null ? p.getId() : null,
                p != null ? p.getFullName() : null,
                c.getRestaurant().getId(), c.getRestaurant().getName(),
                c.getMealName(), c.getObservation(),
                c.getMethod().name(), c.isOffline(), c.isCancelled(),
                c.getBusinessDate() != null ? c.getBusinessDate().toString() : null,
                c.getConsumedAt() != null ? c.getConsumedAt().toString() : null,
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
    }

    private String snapshot(Consumption c) {
        return String.format("emp=%s|proxy=%s|rest=%s|comida=%s|cancel=%s",
                c.getEmployee().getId(), c.getProxyEmployee() != null ? c.getProxyEmployee().getId() : null,
                c.getRestaurant().getId(), c.getMealName(), c.isCancelled());
    }
}
