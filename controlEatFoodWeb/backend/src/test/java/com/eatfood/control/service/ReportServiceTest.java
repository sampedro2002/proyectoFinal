package com.eatfood.control.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceTest {

    @Test
    void surnameFirst_placesTheTwoSurnamesBeforeTheGivenNames() {
        assertThat(ReportService.surnameFirst("María José Pérez López"))
                .isEqualTo("Pérez López, María José");
    }

    @Test
    void surnameFirst_preservesSingleWordNames() {
        assertThat(ReportService.surnameFirst("Madonna")).isEqualTo("Madonna");
    }
}
