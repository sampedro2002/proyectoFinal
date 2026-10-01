package com.eatfood.control.domain;

public enum PersonnelType {
    NOMINA("Empleado de nómina"),
    SERVICIOS_PROFESIONALES("Servicios profesionales");

    private final String label;

    PersonnelType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
