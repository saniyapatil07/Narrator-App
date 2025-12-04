package com.example.narratorapp.narration

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * FIXED VERSION - Proper message queue, no interruptions
 */
class TTSManager(context: Context) {
    private var tts: TextToSpeech? = null
    private var initialized = false
    
    // ===== NEW: Proper queue system =====
    private val messageQueue = LinkedBlockingQueue<TTSMessage>()
    private var isSpeaking = false
    
    private var onSpeakingStateListener: ((Boolean) -> Unit)? = null

    data class TTSMessage(
        val text: String,
        val priority: Priority = Priority.NORMAL,
        val interruptible: Boolean = true
    )
    
    enum class Priority { 
        LOW,      // Object detections
        NORMAL,   // Regular announcements
        HIGH,     // User commands, warnings
        CRITICAL  // Emergencies, confirmation needed
    }

    init {
        Log.d("TTSManager", "Initializing TTS...")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TTSManager", "TTS started: $utteranceId")
                        isSpeaking = true
                        onSpeakingStateListener?.invoke(true)
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d("TTSManager", "TTS finished: $utteranceId")
                        isSpeaking = false
                        onSpeakingStateListener?.invoke(false)
                        
                        // ===== CRITICAL: Process next message in queue =====
                        processQueue()
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e("TTSManager", "TTS error: $utteranceId")
                        isSpeaking = false
                        onSpeakingStateListener?.invoke(false)
                        
                        // Continue with next message even if error
                        processQueue()
                    }
                })
                
                initialized = true
                Log.d("TTSManager", "TTS initialized successfully")
                
                // Process any pending messages
                processQueue()
            } else {
                Log.e("TTSManager", "TTS initialization failed: $status")
            }
        }
    }

    /**
     * Queue a message to be spoken
     * @param text The text to speak
     * @param priority Priority level (affects interrupt behavior)
     * @param interrupt If true, interrupts current speech (only for HIGH/CRITICAL priority)
     */
    fun speak(text: String, priority: Priority = Priority.NORMAL, interrupt: Boolean = false) {
        Log.i("TTSManager", "SPEAK (${priority.name}): '$text'")
        
        if (!initialized) {
            Log.w("TTSManager", "Not initialized, queuing")
            messageQueue.offer(TTSMessage(text, priority))
            return
        }
        
        if (tts == null) {
            Log.e("TTSManager", "TTS is null!")
            return
        }
        
        val message = TTSMessage(text, priority, !interrupt)
        
        // High priority messages can interrupt
        if (interrupt && (priority == Priority.HIGH || priority == Priority.CRITICAL)) {
            Log.i("TTSManager", "HIGH PRIORITY - Interrupting current speech")
            tts?.stop()
            isSpeaking = false
            
            // Clear queue for critical messages
            if (priority == Priority.CRITICAL) {
                messageQueue.clear()
            }
            
            speakNow(message)
        } else {
            // Normal queueing
            messageQueue.offer(message)
            processQueue()
        }
    }
    
    /**
     * Convenience method - speaks without priority/interrupt params
     */
    fun speak(text: String) {
        speak(text, Priority.NORMAL, false)
    }
    
    /**
     * Process next message in queue if not currently speaking
     */
    private fun processQueue() {
        if (isSpeaking) {
            return  // Wait for current speech to finish
        }
        
        val message = messageQueue.poll() ?: return  // Queue empty
        
        speakNow(message)
    }
    
    /**
     * Actually speak the message now
     */
    private fun speakNow(message: TTSMessage) {
        val utteranceId = "utterance_${System.currentTimeMillis()}"
        
        // ===== CRITICAL: Use QUEUE_ADD, not QUEUE_FLUSH =====
        val result = tts?.speak(
            message.text, 
            TextToSpeech.QUEUE_ADD,  // Don't flush, add to TTS internal queue
            null, 
            utteranceId
        )
        
        when (result) {
            TextToSpeech.SUCCESS -> {
                Log.d("TTSManager", "✓ Speaking: '${message.text}'")
                isSpeaking = true
            }
            TextToSpeech.ERROR -> {
                Log.e("TTSManager", "✗ Failed: '${message.text}'")
                isSpeaking = false
                onSpeakingStateListener?.invoke(false)
                processQueue()  // Try next message
            }
            else -> {
                Log.w("TTSManager", "? Unexpected result: $result")
            }
        }
    }
    
    /**
     * Stop current speech and clear queue
     */
    fun stopAndClear() {
        Log.i("TTSManager", "Stopping TTS and clearing queue")
        tts?.stop()
        messageQueue.clear()
        isSpeaking = false
        onSpeakingStateListener?.invoke(false)
    }
    
    /**
     * Get current queue size (for debugging)
     */
    fun getQueueSize(): Int = messageQueue.size
    
    fun isSpeaking(): Boolean = isSpeaking
    
    fun isInitialized() = initialized
    
    fun setOnSpeakingStateListener(listener: (Boolean) -> Unit) {
        onSpeakingStateListener = listener
    }

    fun shutdown() {
        Log.d("TTSManager", "Shutting down TTS")
        tts?.stop()
        messageQueue.clear()
        tts?.shutdown()
        tts = null
        initialized = false
        isSpeaking = false
    }
}