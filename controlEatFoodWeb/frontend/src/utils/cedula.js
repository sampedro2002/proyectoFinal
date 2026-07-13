/**
 * Validación de la cédula de identidad ecuatoriana (10 dígitos).
 * Mismo algoritmo que el backend (CedulaValidator.java): provincia 01-24 o 30,
 * tercer dígito 0-5 y dígito verificador por módulo 10.
 */

/** ¿Tiene forma de cédula (exactamente 10 dígitos)? */
export function looksLikeCedula(value) {
  return /^\d{10}$/.test(value || '');
}

/** Valida formato, provincia y dígito verificador. */
export function isValidCedulaEC(value) {
  if (!looksLikeCedula(value)) return false;

  const province = Number(value.slice(0, 2));
  if ((province < 1 || province > 24) && province !== 30) return false;

  if (Number(value[2]) > 5) return false;

  let sum = 0;
  for (let i = 0; i < 9; i++) {
    let product = Number(value[i]) * (i % 2 === 0 ? 2 : 1);
    if (product > 9) product -= 9;
    sum += product;
  }
  const expected = (10 - (sum % 10)) % 10;
  return expected === Number(value[9]);
}
