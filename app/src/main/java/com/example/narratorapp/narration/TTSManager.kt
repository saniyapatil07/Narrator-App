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
    
    // NEW: Callback for speaking state
    private var onSpeakingStateListener: ((Boolean) -> Unit)? = null

    init {
        Log.d("TTSManager", "Initializing TTS...")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)  // Slightly faster for better responsiveness
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TTSManager", "TTS started: $utteranceId")
                        onSpeakingStateListener?.invoke(true)
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d("TTSManager", "TTS finished: $utteranceId")
                        onSpeakingStateListener?.invoke(false)
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e("TTSManager", "TTS error: $utteranceId")
                        onSpeakingStateListener?.invoke(false)
                    }
                })
                
                initialized = true
                Log.d("TTSManager", "TTS initialized successfully")
                
                if (pendingMessages.isNotEmpty()) {
                    Log.d("TTSManager", "Speaking ${pendingMessages.size} pending messages")
                    pendingMessages.forEach { speak(it) }
                    pendingMessages.clear()
                }
            } else {
                Log.e("TTSManager", "TTS initialization failed: $status")
            }
        }
    }

    fun speak(text: String) {
        Log.i("TTSManager", "SPEAK: '$text'")
        
        if (!initialized) {
            Log.w("TTSManager", "Not initialized, queuing")
            pendingMessages.add(text)
            return
        }
        
        if (tts == null) {
            Log.e("TTSManager", "TTS is null!")
            return
        }
        
        // Stop current speech
        tts?.stop()
        
        val utteranceId = "utterance_${System.currentTimeMillis()}"
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        
        when (result) {
            TextToSpeech.SUCCESS -> {
                Log.d("TTSManager", "✓ Queued: '$text'")
            }
            TextToSpeech.ERROR -> {
                Log.e("TTSManager", "✗ Failed: '$text'")
                onSpeakingStateListener?.invoke(false)
            }
            else -> {
                Log.w("TTSManager", "? Unexpected result: $result")
            }
        }
    }
    
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    fun isInitialized() = initialized
    
    // NEW: Set callback for speaking state changes
    fun setOnSpeakingStateListener(listener: (Boolean) -> Unit) {
        onSpeakingStateListener = listener
    }

    fun shutdown() {
        Log.d("TTSManager", "Shutting down TTS")
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }
}