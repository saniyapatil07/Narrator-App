package com.example.narratorapp.narration

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

class TTSManager(context: Context) {
    private var tts: TextToSpeech? = null
    private var initialized = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                initialized = true
                Log.d("TTSManager", "TTS initialized successfully")
            } else {
                Log.e("TTSManager", "TTS initialization failed")
            }
        }
    }

    fun speak(text: String) {
        if (initialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun isInitialized() = initialized

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}