package com.eatfood.control.mobile.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.eatfood.control.mobile.util.ECUADOR_ZONE
import com.eatfood.control.mobile.util.ecuadorToday
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

internal const val EMERGENCY_HELP = "Registro de emergencia: permite guardar fuera del horario de comidas o cargar el control en papel de otra fecha. El motivo es obligatorio y queda auditado."

internal data class ConsumptionDateValue(
    val businessDate: String = ecuadorToday().toString(),
    val consumptionTime: String = "",
    val contingency: Boolean = false,
    val reason: String = ""
) {
    fun validationError(editing: Boolean = false): String? = when {
        businessDate.isBlank() -> "Seleccione la fecha del consumo."
        (editing || businessDate != ecuadorToday().toString()) && consumptionTime.isBlank() ->
            "Indique la hora del consumo para la fecha seleccionada."
        reason.isBlank() -> if (editing) "Indique el motivo de la corrección." else "Indique el motivo del registro."
        reason.length > 500 -> "El motivo no puede superar los 500 caracteres."
        else -> null
    }
}

@Composable
internal fun ConsumptionDateFields(
    value: ConsumptionDateValue,
    onChange: (ConsumptionDateValue) -> Unit,
    editing: Boolean = false,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val date = LocalDate.parse(value.businessDate)
    OutlinedButton(enabled = enabled, onClick = {
        DatePickerDialog(context, { _, year, month, day ->
            onChange(value.copy(businessDate = LocalDate.of(year, month + 1, day).toString()))
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth()) { Text("Fecha del consumo (Ecuador): ${value.businessDate}") }
    OutlinedButton(enabled = enabled, onClick = {
        val time = value.consumptionTime.takeIf { it.isNotBlank() }?.let(LocalTime::parse)
            ?: LocalTime.now(ECUADOR_ZONE)
        TimePickerDialog(context, { _, hour, minute ->
            onChange(value.copy(consumptionTime = String.format(Locale.ROOT, "%02d:%02d", hour, minute)))
        }, time.hour, time.minute, true).show()
    }, modifier = Modifier.fillMaxWidth()) {
        Text("Hora del consumo (Ecuador): ${value.consumptionTime.ifBlank { "Actual" }}")
    }
    if (!editing && value.businessDate == ecuadorToday().toString()) {
        Text("Deje la hora vacía para usar la hora actual.")
        TextButton(enabled = enabled, onClick = { onChange(value.copy(consumptionTime = "")) }) { Text("Usar hora actual") }
    }
    if (date > ecuadorToday()) Text("Se contabilizará como consumo de la fecha futura y no permitirá repetir esa comida ese día.")
    if (editing) {
        Row {
            Checkbox(value.contingency, { onChange(value.copy(contingency = it)) }, enabled = enabled)
            Text("Registro por contingencia (permite guardar fuera del horario de comidas)")
        }
    }
    OutlinedTextField(value.reason, { onChange(value.copy(reason = it.take(500))) },
        enabled = enabled, label = { Text("Motivo (obligatorio)") },
        placeholder = { Text("Ej.: corte de luz, caída de internet o corrección de fecha") },
        modifier = Modifier.fillMaxWidth())
}
