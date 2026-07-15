package com.eatfood.control.mobile.data.remote

import com.eatfood.control.mobile.data.model.ApiError
import com.google.gson.Gson
import retrofit2.HttpException

/** Extrae el mensaje legible del error del backend ({code,message}). */
fun Throwable.apiMessage(default: String = "Ocurrió un error"): String =
    apiError()?.message ?: when (this) {
        is HttpException -> "Error ${code()}"
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "Sin conexión con el servidor"
        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
        else -> message ?: default
    }

/**
 * True para fallos en los que nunca hubo respuesta del backend (servidor apagado,
 * IP/URL mal configurada, firewall que descarta el puerto). Sirve para que las
 * pantallas de login muestren a qué servidor se intentó llegar, en vez de un
 * "Tiempo de espera agotado" sin contexto.
 */
fun Throwable.isConnectivityError(): Boolean =
    this is java.net.UnknownHostException ||
    this is java.net.ConnectException ||
    this is java.net.SocketTimeoutException

/**
 * Devuelve el cuerpo de error parseado del backend ({timestamp,status,code,message})
 * cuando el Throwable es un HttpException; null en cualquier otro caso.
 *
 * Se consume el errorBody una sola vez y se cachea el resultado en el ApiError,
 * porque OkHttp sólo permite leer el buffer una vez.
 */
fun Throwable.apiError(): ApiError? = when (this) {
    is HttpException -> runCatching {
        response()?.errorBody()?.string()?.let { Gson().fromJson(it, ApiError::class.java) }
    }.getOrNull()
    else -> null
}
