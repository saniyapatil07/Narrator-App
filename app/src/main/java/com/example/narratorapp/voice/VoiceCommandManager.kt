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
        "heynarrator", "oknarrator", "hellonarrator", "narrator",
        "haynarrator", "heynavigator", "hienarrator"
    )
    
    // Track TTS state to avoid mic conflicts
    private var isTTSSpeaking = false

    init {
        initializeSpeechRecognizer()
        
        // Monitor TTS state
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
        // Check if another app is using the microphone
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
        speechRecognizer?.stopListening()
        onListeningStateChanged?.invoke(false)
        Log.i("VoiceCommandManager", "Stopped listening")
    }
    
    private fun pauseRecognition() {
        if (isListening) {
            speechRecognizer?.stopListening()
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)  // Reduce overhead
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)  // Reduced from 5
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
                handler.postDelayed({
                    if (isListening && isMicrophoneAvailable()) {
                        startRecognition()
                    }
                }, 2000)  // Longer delay for audio errors
                return
            }
            
            // Normal restart
            if (isListening && !isTTSSpeaking) {
                handler.postDelayed({
                    if (isListening) startRecognition()
                }, 1000)
            }
        }
        
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
            
            val rawSpokenText = matches[0]
            val cleanText = rawSpokenText.filter { !it.isWhitespace() }.lowercase()
            
            Log.i("VoiceDebug", "📣 '$rawSpokenText' → '$cleanText'")
            
            if (isHotwordMode) {
                val foundHotword = hotwords.find { cleanText.contains(it) }
                
                if (foundHotword != null) {
                    Log.i("VoiceCommandManager", "✓ HOTWORD: '$foundHotword'")
                    ttsManager.speak("Yes?")
                    
                    isHotwordMode = false
                    // Wait for TTS to finish before listening for command
                    handler.postDelayed({
                        if (isListening && !isTTSSpeaking) {
                            Log.i("VoiceCommandManager", "→ COMMAND mode")
                            startRecognition()
                        }
                    }, 2000)  // Give TTS time to finish
                } else {
                    Log.d("VoiceCommandManager", "No hotword, continuing...")
                    handler.postDelayed({
                        if (isListening && !isTTSSpeaking) startRecognition()
                    }, 500)
                }
            } else {
                // Command Mode
                val command = parseCommand(cleanText)
                
                if (command != null) {
                    Log.i("VoiceCommandManager", "✓ COMMAND: ${command.getDescription()}")
                    onCommandRecognized?.invoke(command)
                    isHotwordMode = true
                } else {
                    Log.w("VoiceCommandManager", "❌ Unknown: '$cleanText'")
                    ttsManager.speak("I didn't understand")
                }
                
                handler.postDelayed({
                    if (isListening && !isTTSSpeaking) {
                        Log.i("VoiceCommandManager", "→ HOTWORD mode")
                        startRecognition()
                    }
                }, 2000)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    private fun parseCommand(cleanText: String): VoiceCommand? {
        return when {
            cleanText.contains("startnavigation") -> VoiceCommand.StartNavigation
            cleanText.contains("stopnavigation") -> VoiceCommand.StopNavigation
            cleanText.contains("recordwaypoint") -> VoiceCommand.RecordWaypoint
            cleanText.contains("whereami") || cleanText.contains("location") -> VoiceCommand.GetLocation
            cleanText.contains("readtext") || cleanText.contains("readingmode") -> VoiceCommand.EnableReadingMode
            cleanText.contains("stopreading") || cleanText.contains("normalmode") -> VoiceCommand.DisableReadingMode
            cleanText.startsWith("learnface") -> {
                val name = cleanText.removePrefix("learnface").trim()
                if (name.isNotEmpty()) VoiceCommand.LearnFace(name) else VoiceCommand.LearnFacePrompt
            }
            cleanText.startsWith("learnplace") -> {
                val name = cleanText.removePrefix("learnplace").trim()
                if (name.isNotEmpty()) VoiceCommand.LearnPlace(name) else VoiceCommand.LearnPlacePrompt
            }
            cleanText.contains("whoisthis") -> VoiceCommand.RecognizeFace
            cleanText.contains("whereisthis") -> VoiceCommand.RecognizePlace
            cleanText.contains("whatdoyousee") || cleanText.contains("describescene") -> VoiceCommand.DescribeScene
            cleanText.contains("findobject") -> VoiceCommand.FindObject
            cleanText.contains("increasevolume") || cleanText.contains("volumeup") -> VoiceCommand.IncreaseVolume
            cleanText.contains("decreasevolume") || cleanText.contains("volumedown") -> VoiceCommand.DecreaseVolume
            cleanText.contains("pause") -> VoiceCommand.Pause
            cleanText.contains("resume") -> VoiceCommand.Resume
            cleanText.contains("help") -> VoiceCommand.Help
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