package com.example.narratorapp.narration

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TTSManager(context: Context) {
    private var tts: TextToSpeech? = null
    private var initialized = false
    private val pendingMessages = mutableListOf<String>()

    init {
        Log.d("TTSManager", "Initializing TTS...")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                
                // Set up listener to track TTS events
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TTSManager", "TTS started speaking: $utteranceId")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d("TTSManager", "TTS finished speaking: $utteranceId")
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e("TTSManager", "TTS error: $utteranceId")
                    }
                })
                
                initialized = true
                Log.d("TTSManager", "TTS initialized successfully")
                
                // Speak any pending messages
                if (pendingMessages.isNotEmpty()) {
                    Log.d("TTSManager", "Speaking ${pendingMessages.size} pending messages")
                    pendingMessages.forEach { speak(it) }
                    pendingMessages.clear()
                }
            } else {
                Log.e("TTSManager", "TTS initialization failed with status: $status")
            }
        }
    }

    fun speak(text: String) {
        Log.i("TTSManager", "SPEAK REQUEST: '$text'")
        
        if (!initialized) {
            Log.w("TTSManager", "TTS not initialized yet, queuing message")
            pendingMessages.add(text)
            return
        }
        
        if (tts == null) {
            Log.e("TTSManager", "TTS is null!")
            return
        }
        
        // Stop any current speech
        tts?.stop()
        
        // Speak with utterance ID for tracking
        val utteranceId = "utterance_${System.currentTimeMillis()}"
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        
        when (result) {
            TextToSpeech.SUCCESS -> {
                Log.d("TTSManager", "✓ TTS speak command queued successfully: '$text'")
            }
            TextToSpeech.ERROR -> {
                Log.e("TTSManager", "✗ TTS speak command FAILED: '$text'")
            }
            else -> {
                Log.w("TTSManager", "? TTS speak returned unexpected result: $result")
            }
        }
    }
    
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    fun isInitialized() = initialized

    fun shutdown() {
        Log.d("TTSManager", "Shutting down TTS")
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }
}