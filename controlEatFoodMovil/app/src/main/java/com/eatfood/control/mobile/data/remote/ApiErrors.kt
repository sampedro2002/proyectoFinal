package com.eatfood.control.mobile.data.remote

import com.eatfood.control.mobile.data.model.ApiError
import com.google.gson.Gson
import retrofit2.HttpException

/** Extrae el mensaje legible del error del backend ({code,message}). */
fun Throwable.apiMessage(default: String = "Ocurrió un error"): String {
    return when (this) {
        is HttpException -> {
            val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
            val parsed = raw?.let { runCatching { Gson().fromJson(it, ApiError::class.java) }.getOrNull() }
            parsed?.message ?: "Error ${code()}"
        }
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "Sin conexión con el servidor"
        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
        else -> message ?: default
    }
}
