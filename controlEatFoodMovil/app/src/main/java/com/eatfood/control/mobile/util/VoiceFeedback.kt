package com.eatfood.control.mobile.util

import android.content.Context
import android.speech.tts.TextToSpeech
import com.eatfood.control.mobile.data.model.ScanResponse
import java.util.Locale

/**
 * Avisos por voz del kiosco: confirma el registro o explica por qué se rechazó la huella.
 * Las frases se encolan (QUEUE_ADD) para que varias personas seguidas se anuncien una tras otra.
 */
object VoiceFeedback {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val res = engine.setLanguage(Locale("es", "EC")).takeIf {
                    it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                } ?: engine.setLanguage(Locale("es"))
                ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun speak(text: String) {
        if (!ready) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null) }
    }

    /** Traduce el resultado del escaneo por huella a una frase corta (2 palabras). */
    fun announce(r: ScanResponse) {
        speak(
            when (r.status) {
                "SUCCESS" -> "Registro exitoso"
                "QUEUED" -> "Registro guardado"
                "NOT_FOUND" -> "Huella inválida"
                "DUPLICATE" -> "Ya registrado"
                "LIMIT_REACHED" -> "Comidas completas"
                "OUT_OF_SCHEDULE" -> "Horario cerrado"
                "NOT_ALLOWED" -> "No autorizado"
                else -> "Error, reintente"
            }
        )
    }
}
