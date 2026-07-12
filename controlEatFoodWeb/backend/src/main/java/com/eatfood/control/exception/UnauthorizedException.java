package com.eatfood.control.exception;

import lombok.Getter;

/**
 * Excepcion semantica para fallos de autenticacion (refresh invalido/expirado,
 * cuenta bloqueada/deshabilitada, sesion invalida).
 *
 * A diferencia de {@link BusinessException} (que se mapea a 409 CONFLICT), esta
 * excepcion se traduce en 401 UNAUTHORIZED en {@link GlobalExceptionHandler},
 * que es el status HTTP correcto para "no autorizado" y ademas evita ruido en
 * consola del navegador cuando el AuthContext intenta un refresh silencioso al
 * recargar la SPA y no hay cookie valida (caso normal, no un conflicto).
 */
@Getter
public class UnauthorizedException extends RuntimeException {
    private final String code;

    public UnauthorizedException(String code, String message) {
        super(message);
        this.code = code;
    }
}