package com.eatfood.control.mobile.util

import android.media.AudioManager
import android.media.ToneGenerator

/** Tonos de éxito/error para el kiosco (equivale a sound.js del frontend). */
object ToneFeedback {
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }

    fun success() = runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
    fun error() = runCatching { tone.startTone(ToneGenerator.TONE_PROP_NACK, 350) }
}
