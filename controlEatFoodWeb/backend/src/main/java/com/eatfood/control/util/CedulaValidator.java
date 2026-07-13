package com.eatfood.control.util;

/**
 * Validación de la cédula de identidad ecuatoriana (10 dígitos):
 * <ul>
 *   <li>Provincia (2 primeros dígitos): 01–24, o 30 (ecuatorianos registrados en el exterior).</li>
 *   <li>Tercer dígito: 0–5 (personas naturales).</li>
 *   <li>Décimo dígito: verificador por módulo 10 con coeficientes 2,1,2,1,2,1,2,1,2
 *       (si el producto es mayor a 9 se le resta 9).</li>
 * </ul>
 */
public final class CedulaValidator {

    private CedulaValidator() {}

    /** ¿El valor tiene exactamente 10 dígitos numéricos? (forma de cédula, aún sin verificar). */
    public static boolean looksLikeCedula(String value) {
        return value != null && value.matches("\\d{10}");
    }

    /** Valida una cédula ecuatoriana completa (formato + provincia + dígito verificador). */
    public static boolean isValid(String value) {
        if (!looksLikeCedula(value)) return false;

        int province = Integer.parseInt(value.substring(0, 2));
        if ((province < 1 || province > 24) && province != 30) return false;

        int thirdDigit = value.charAt(2) - '0';
        if (thirdDigit > 5) return false;

        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = value.charAt(i) - '0';
            int product = (i % 2 == 0) ? digit * 2 : digit;
            if (product > 9) product -= 9;
            sum += product;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == (value.charAt(9) - '0');
    }
}
