package com.example.narratorapp.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.narratorapp.narration.TTSManager
import java.util.*

/**
 * FIXED VERSION - Preserves spaces, handles multi-word names, checks all hypotheses
 */
class VoiceCommandManager(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isHotwordMode = true
    private val handler = Handler(Looper.getMainLooper())
    
    private var onCommandRecognized: ((VoiceCommand) -> Unit)? = null
    private var onListeningStateChanged: ((Boolean) -> Unit)? = null
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val hotwords = listOf(
        "hey narrator", "ok narrator", "hello narrator", "narrator",
        "hay narrator", "hey navigator", "hie narrator"
    )
    
    private var isTTSSpeaking = false

    init {
        initializeSpeechRecognizer()
        
        ttsManager.setOnSpeakingStateListener { speaking ->
            isTTSSpeaking = speaking
            if (speaking && isListening) {
                Log.w("VoiceCommandManager", "⚠️ TTS started - pausing recognition")
                pauseRecognition()
            } else if (!speaking && !isListening) {
                Log.i("VoiceCommandManager", "✓ TTS finished - resuming recognition")
                handler.postDelayed({ resumeRecognition() }, 500)
            }
        }
    }
    
    // ===== NEW: Proper text normalization that preserves spaces =====
    private fun normalizeSpokenText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        
        // Lowercase, trim, collapse multiple spaces to one
        val lower = raw.lowercase(Locale.getDefault()).trim()
        val collapsed = lower.replace(Regex("\\s+"), " ")
        
        // Remove punctuation except apostrophes and dashes (for names like "O'Brien" or "Mary-Jane")
        val cleaned = collapsed.replace(Regex("[^a-z0-9\\s''-]"), "")
        
        return cleaned
    }
    
    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("VoiceCommandManager", "Speech recognition not available")
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)
        Log.d("VoiceCommandManager", "Speech recognizer initialized")
    }
    
    private fun isMicrophoneAvailable(): Boolean {
        val mode = audioManager.mode
        val isMusicActive = audioManager.isMusicActive
        
        if (mode != AudioManager.MODE_NORMAL) {
            Log.w("VoiceCommandManager", "Mic unavailable - audio mode: $mode")
            return false
        }
        
        if (isMusicActive) {
            Log.w("VoiceCommandManager", "Mic unavailable - music playing")
            return false
        }
        
        return true
    }
    
    fun startListening(startWithHotword: Boolean = true) {
        if (isListening) {
            Log.w("VoiceCommandManager", "Already listening")
            return
        }
        
        if (isTTSSpeaking) {
            Log.w("VoiceCommandManager", "Cannot start - TTS is speaking")
            handler.postDelayed({ startListening(startWithHotword) }, 500)
            return
        }
        
        if (!isMicrophoneAvailable()) {
            Log.w("VoiceCommandManager", "Cannot start - microphone busy")
            handler.postDelayed({ startListening(startWithHotword) }, 1000)
            return
        }
        
        isHotwordMode = startWithHotword
        Log.i("VoiceCommandManager", "Starting: ${if (startWithHotword) "HOTWORD" else "COMMAND"}")
        startRecognition()
    }
    
    fun stopListening() {
        isListening = false
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e("VoiceCommandManager", "Error canceling recognizer", e)
        }
        onListeningStateChanged?.invoke(false)
        Log.i("VoiceCommandManager", "Stopped listening")
    }
    
    private fun pauseRecognition() {
        if (isListening) {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e("VoiceCommandManager", "Error pausing", e)
            }
            Log.d("VoiceCommandManager", "Recognition paused")
        }
    }
    
    private fun resumeRecognition() {
        if (isListening && !isTTSSpeaking) {
            startRecognition()
            Log.d("VoiceCommandManager", "Recognition resumed")
        }
    }
       
    private fun startRecognition() {
        if (isTTSSpeaking) {
            Log.w("VoiceCommandManager", "Skipping start - TTS active")
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)  // Get multiple hypotheses
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        
        isListening = true
        
        try {
            speechRecognizer?.startListening(intent)
            onListeningStateChanged?.invoke(true)
            
            val mode = if (isHotwordMode) "HOTWORD" else "COMMAND"
            Log.i("VoiceCommandManager", "🎤 Started ($mode)")
        } catch (e: Exception) {
            Log.e("VoiceCommandManager", "Failed to start recognition", e)
            isListening = false
        }
    }
    
    private val recognitionListener = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            val mode = if (isHotwordMode) "hotword" else "command"
            Log.d("VoiceCommandManager", "✓ Ready ($mode)")
        }
        
        override fun onBeginningOfSpeech() {
            Log.d("VoiceCommandManager", "🗣️ Speech detected")
        }
        
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Log.d("VoiceCommandManager", "Speech ended")
        }
        
        // ===== FIXED: Safer error handling with cancel() =====
        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "TIMEOUT"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO_ERROR (mic busy?)"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT_ERROR"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK_ERROR"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                else -> "Error $error"
            }
            
            Log.w("VoiceDebug", "❌ $errorMessage")
            
            // Handle audio errors specially
            if (error == SpeechRecognizer.ERROR_AUDIO) {
                Log.e("VoiceCommandManager", "⚠️ Microphone conflict detected!")
                try { 
                    speechRecognizer?.cancel() 
                } catch (e: Exception) {}
                
                handler.postDelayed({
                    if (isListening && isMicrophoneAvailable()) {
                        startRecognition()
                    }
                }, 2000)
                return
            }
            
            // Normal restart: cancel then restart after a short delay
            try { 
                speechRecognizer?.cancel() 
            } catch (e: Exception) {}
            
            if (isListening && !isTTSSpeaking) {
                handler.postDelayed({
                    if (isListening) startRecognition()
                }, 1000)
            }
        }
        
        // ===== FIXED: Check all hypotheses, preserve spaces =====
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            
            if (matches.isNullOrEmpty()) {
                Log.w("VoiceDebug", "No results")
                if (isListening && !isTTSSpeaking) {
                    handler.postDelayed({ 
                        if (isListening) startRecognition() 
                    }, 500)
                }
                return
            }
            
            val firstNonEmpty = matches.firstOrNull { !it.isNullOrBlank() } ?: matches[0]
            val rawSpokenText = firstNonEmpty
            val normalized = normalizeSpokenText(rawSpokenText)
            
            Log.i("VoiceDebug", "📣 Raw: '$rawSpokenText' → Normalized: '$normalized'")
            
            // HOTWORD MODE: Check all hypotheses for hotword
            if (isHotwordMode) {
                val foundHotword = matches
                    .map { normalizeSpokenText(it) }
                    .firstOrNull { hyp ->
                        hotwords.any { hw ->
                            // Allow optional spaces in hotword
                            hyp.contains(hw) || hyp.contains(hw.replace(" ", ""))
                        }
                    }
                
                if (foundHotword != null) {
                    Log.i("VoiceCommandManager", "✓ HOTWORD detected")
                    
                    // Pause recognition cleanly
                    try { 
                        speechRecognizer?.cancel() 
                    } catch (e: Exception) {}
                    
                    ttsManager.speak("Yes?", TTSManager.Priority.HIGH)
                    isHotwordMode = false
                    
                    handler.postDelayed({
                        if (isListening && !isTTSSpeaking) {
                            Log.i("VoiceCommandManager", "→ COMMAND mode")
                            startRecognition()
                        }
                    }, 600)
                } else {
                    // No hotword - continue listening
                    handler.postDelayed({ 
                        if (isListening && !isTTSSpeaking) startRecognition() 
                    }, 500)
                }
                return
            }
            
            // COMMAND MODE: Check all hypotheses for valid command
            var commandFound: VoiceCommand? = null
            for (hyp in matches) {
                val norm = normalizeSpokenText(hyp)
                val parsed = parseCommand(norm)
                if (parsed != null) {
                    commandFound = parsed
                    Log.i("VoiceCommandManager", "Found command in hypothesis: '$hyp'")
                    break
                }
            }
            
            if (commandFound != null) {
                Log.i("VoiceCommandManager", "✓ COMMAND: ${commandFound.getDescription()}")
                onCommandRecognized?.invoke(commandFound)
                isHotwordMode = true
                
                handler.postDelayed({ 
                    if (isListening && !isTTSSpeaking) {
                        Log.i("VoiceCommandManager", "→ HOTWORD mode")
                        startRecognition()
                    }
                }, 600)
            } else {
                Log.w("VoiceCommandManager", "❌ Unknown: '$normalized'")
                ttsManager.speak("I didn't understand")
                isHotwordMode = true
                
                handler.postDelayed({ 
                    if (isListening && !isTTSSpeaking) startRecognition() 
                }, 1000)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    // ===== FIXED: Preserve spaces, extract multi-word names =====
    private fun parseCommand(normalizedText: String): VoiceCommand? {
        val t = normalizedText
        
        return when {
            t.contains("start navigation") || t.contains("start nav") -> 
                VoiceCommand.StartNavigation
                
            t.contains("stop navigation") || t.contains("stop nav") -> 
                VoiceCommand.StopNavigation
                
            t.contains("record waypoint") -> 
                VoiceCommand.RecordWaypoint
                
            t.contains("where am i") || t.contains("what is my location") || t.contains("my location") -> 
                VoiceCommand.GetLocation
                
            t.contains("read text") || t.contains("reading mode") || t.contains("read mode") -> 
                VoiceCommand.EnableReadingMode
                
            t.contains("stop reading") || t.contains("normal mode") -> 
                VoiceCommand.DisableReadingMode
            
            // ===== CRITICAL: Extract multi-word names =====
            t.startsWith("learn face") -> {
                val name = t.removePrefix("learn face").trim()
                if (name.isNotEmpty()) {
                    Log.i("VoiceCommandManager", "Extracted face name: '$name'")
                    VoiceCommand.LearnFace(name)
                } else {
                    VoiceCommand.LearnFacePrompt
                }
            }
            
            t.startsWith("learn place") -> {
                val name = t.removePrefix("learn place").trim()
                if (name.isNotEmpty()) {
                    Log.i("VoiceCommandManager", "Extracted place name: '$name'")
                    VoiceCommand.LearnPlace(name)
                } else {
                    VoiceCommand.LearnPlacePrompt
                }
            }
            
            t.contains("who is this") || t.contains("identify face") || t.contains("recognize face") -> 
                VoiceCommand.RecognizeFace
                
            t.contains("where is this") || t.contains("identify place") || t.contains("recognize place") -> 
                VoiceCommand.RecognizePlace
                
            t.contains("what do you see") || t.contains("describe scene") -> 
                VoiceCommand.DescribeScene
                
            t.contains("find object") -> 
                VoiceCommand.FindObject
                
            t.contains("increase volume") || t.contains("volume up") -> 
                VoiceCommand.IncreaseVolume
                
            t.contains("decrease volume") || t.contains("volume down") -> 
                VoiceCommand.DecreaseVolume
                
            t.contains("pause") -> 
                VoiceCommand.Pause
                
            t.contains("resume") -> 
                VoiceCommand.Resume
                
            t.contains("help") -> 
                VoiceCommand.Help
                
            else -> null
        }
    }
    
    fun setOnCommandRecognizedListener(listener: (VoiceCommand) -> Unit) {
        onCommandRecognized = listener
    }
    
    fun setOnListeningStateChangedListener(listener: (Boolean) -> Unit) {
        onListeningStateChanged = listener
    }
    
    fun isCurrentlyListening() = isListening
    
    fun cleanup() {
        stopListening()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d("VoiceCommandManager", "Cleaned up")
    }
}