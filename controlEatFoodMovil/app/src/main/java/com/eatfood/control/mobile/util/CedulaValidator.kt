package com.eatfood.control.mobile.util

/**
 * Validación de la cédula de identidad ecuatoriana (10 dígitos).
 * Mismo algoritmo que el backend (CedulaValidator.java) y la web (utils/cedula.js):
 * provincia 01-24 o 30, tercer dígito 0-5 y dígito verificador por módulo 10.
 */
object CedulaValidator {

    /** ¿Tiene forma de cédula (exactamente 10 dígitos)? */
    fun looksLikeCedula(value: String?): Boolean =
        value != null && Regex("\\d{10}").matches(value)

    /** Valida formato, provincia y dígito verificador. */
    fun isValid(value: String?): Boolean {
        if (!looksLikeCedula(value)) return false
        val v = value!!

        val province = v.substring(0, 2).toInt()
        if (province !in 1..24 && province != 30) return false

        if (v[2] - '0' > 5) return false

        var sum = 0
        for (i in 0 until 9) {
            var product = (v[i] - '0') * if (i % 2 == 0) 2 else 1
            if (product > 9) product -= 9
            sum += product
        }
        val expected = (10 - (sum % 10)) % 10
        return expected == v[9] - '0'
    }
}
