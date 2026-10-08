package com.eatfood.control.mobile.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.eatfood.control.mobile.R
import com.eatfood.control.mobile.data.model.ScanResponse
import java.util.Locale

/**
 * Avisos por voz del kiosco: confirma el registro o explica por qué se rechazó la huella.
 * Reproduce audios grabados (res/raw) con SoundPool; si uno no cargó, usa el TTS del sistema.
 * Los avisos se encolan para que varias personas seguidas se anuncien una tras otra.
 */
object VoiceFeedback {
    private class Clip(val phrase: String, val resId: Int) {
        var soundId = 0
        var loaded = false
        var durationMs = DEFAULT_DURATION_MS
    }

    private const val DEFAULT_DURATION_MS = 1500L
    private const val GAP_MS = 150L

    private val clips = mapOf(
        "SUCCESS" to Clip("Registro exitoso", R.raw.voz_exito),
        "QUEUED" to Clip("Registro guardado", R.raw.voz_guardado),
        "NOT_FOUND" to Clip("Huella inválida", R.raw.voz_huella_invalida),
        "DUPLICATE" to Clip("Ya registrado", R.raw.voz_ya_registrado),
        "LIMIT_REACHED" to Clip("Comidas completas", R.raw.voz_comidas_completas),
        "OUT_OF_SCHEDULE" to Clip("Horario cerrado", R.raw.voz_horario_cerrado),
        "NOT_ALLOWED" to Clip("No autorizado", R.raw.voz_no_autorizado),
    )
    private val fallbackClip = Clip("Error, reintente", R.raw.voz_reintentar)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Clip>()
    private var playing = false

    private var pool: SoundPool? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        val sp = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) (clips.values + fallbackClip).firstOrNull { it.soundId == sampleId }?.loaded = true
        }
        pool = sp
        (clips.values + fallbackClip).forEach { clip ->
            clip.durationMs = durationOf(app, clip.resId)
            clip.soundId = sp.load(app, clip.resId, 1)
        }

        // Respaldo: voz del sistema para cuando un audio no esté disponible.
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val res = engine.setLanguage(Locale("es", "EC")).takeIf {
                    it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                } ?: engine.setLanguage(Locale("es"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        queue.clear()
        playing = false
        pool?.release()
        pool = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        (clips.values + fallbackClip).forEach { it.loaded = false; it.soundId = 0 }
    }

    private fun durationOf(context: Context, resId: Int): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse("android.resource://${context.packageName}/$resId"))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        } finally {
            r.release()
        }
    }.getOrNull() ?: DEFAULT_DURATION_MS

    private fun enqueue(clip: Clip) {
        main.post {
            if (pool == null) return@post
            queue.addLast(clip)
            if (!playing) playNext()
        }
    }

    private fun playNext() {
        val clip = queue.removeFirstOrNull()
        if (clip == null) {
            playing = false
            return
        }
        playing = true
        val wait = if (clip.loaded && pool != null) {
            runCatching { pool?.play(clip.soundId, 1f, 1f, 1, 0, 1f) }
            clip.durationMs
        } else if (ttsReady) {
            runCatching { tts?.speak(clip.phrase, TextToSpeech.QUEUE_ADD, null, null) }
            // El TTS ya encola por sí solo; basta una pausa estimada por longitud de frase.
            clip.phrase.length * 80L
        } else 0L
        main.postDelayed({ playNext() }, wait + GAP_MS)
    }

    /** Traduce el resultado del escaneo por huella a su audio. */
    fun announce(r: ScanResponse) = announce(r.status)

    /** Igual que [announce] pero a partir del código de estado (huella o registro manual). */
    fun announce(status: String) = enqueue(clips[status] ?: fallbackClip)
}
