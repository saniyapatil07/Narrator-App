package com.example.narratorapp.narration

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.*

class TTSManager(context: Context) {
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
            }
        }
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun shutdown() {
        tts.shutdown()
    }
}