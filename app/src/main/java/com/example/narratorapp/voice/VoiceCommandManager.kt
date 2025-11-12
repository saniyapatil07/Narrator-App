package com.example.narratorapp.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.narratorapp.narration.TTSManager
import java.util.*


/**
 * Manages voice command recognition with hotword triggering
 * Listens for "Hey Narrator" hotword, then processes commands
 */
class VoiceCommandManager(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isHotwordMode = true // Start in hotword listening mode
    
    // Callbacks
    private var onCommandRecognized: ((VoiceCommand) -> Unit)? = null
    private var onListeningStateChanged: ((Boolean) -> Unit)? = null
    
    // Hotword variations
    private val hotwords = listOf(
        "hey narrator",
        "ok narrator",
        "hello narrator",
        "narrator"
    )
    
    init {
        initializeSpeechRecognizer()
    }
    
    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("VoiceCommandManager", "Speech recognition not available")
            ttsManager.speak("Voice commands are not available on this device")
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)
    }
    
    /**
     * Start listening for hotword
     */
    fun startListening() {
        if (isListening) return
        
        isHotwordMode = true
        startRecognition()
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
        onListeningStateChanged?.invoke(false)
    }
    
    private fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            
            // For continuous listening
            if (isHotwordMode) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
        }
        
        isListening = true
        speechRecognizer?.startListening(intent)
        onListeningStateChanged?.invoke(true)
    }
    
    private val recognitionListener = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("VoiceCommandManager", if (isHotwordMode) "Listening for hotword..." else "Listening for command...")
        }
        
        override fun onBeginningOfSpeech() {
            Log.d("VoiceCommandManager", "Speech detected")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Audio level feedback (optional)
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Log.d("VoiceCommandManager", "End of speech")
        }
        
        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error: $error"
            }
            
            Log.e("VoiceCommandManager", "Recognition error: $errorMessage")
            
            // Restart listening after error (except for no match in hotword mode)
            if (error == SpeechRecognizer.ERROR_NO_MATCH && isHotwordMode) {
                // Continue listening for hotword
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 500)
            } else if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                stopListening()
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            
            if (matches.isNullOrEmpty()) {
                if (isHotwordMode) {
                    // Restart hotword listening
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isListening) startRecognition()
                    }, 500)
                }
                return
            }
            
            val spokenText = matches[0].lowercase(Locale.getDefault())
            Log.d("VoiceCommandManager", "Recognized: $spokenText")
            
            if (isHotwordMode) {
                // Check if hotword was spoken
                if (hotwords.any { spokenText.contains(it) }) {
                    Log.d("VoiceCommandManager", "Hotword detected!")
                    ttsManager.speak("Yes, listening")
                    
                    // Switch to command mode
                    isHotwordMode = false
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startRecognition()
                    }, 1000) // Wait for TTS to finish
                } else {
                    // Not a hotword, continue listening
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isListening) startRecognition()
                    }, 500)
                }
            } else {
                // Process command
                val command = parseCommand(spokenText)
                if (command != null) {
                    onCommandRecognized?.invoke(command)
                } else {
                    ttsManager.speak("Sorry, I didn't understand that command")
                }
                
                // Return to hotword mode
                isHotwordMode = true
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 1500)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            // Optional: Show partial results in UI
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    /**
     * Parse spoken text into VoiceCommand
     */
    private fun parseCommand(text: String): VoiceCommand? {
        val lowerText = text.lowercase(Locale.getDefault()).trim()
        
        return when {
            // Navigation commands
            lowerText.contains("start navigation") || lowerText.contains("begin navigation") -> {
                VoiceCommand.StartNavigation
            }
            lowerText.contains("stop navigation") || lowerText.contains("end navigation") -> {
                VoiceCommand.StopNavigation
            }
            lowerText.contains("record waypoint") || lowerText.contains("mark location") -> {
                VoiceCommand.RecordWaypoint
            }
            lowerText.contains("where am i") || lowerText.contains("current location") -> {
                VoiceCommand.GetLocation
            }
            
            // Reading mode
            lowerText.contains("read text") || lowerText.contains("reading mode") -> {
                VoiceCommand.EnableReadingMode
            }
            lowerText.contains("stop reading") || lowerText.contains("normal mode") -> {
                VoiceCommand.DisableReadingMode
            }
            
            // Memory commands
            lowerText.contains("learn face") || lowerText.contains("remember face") -> {
                extractName(lowerText)?.let { VoiceCommand.LearnFace(it) } ?: VoiceCommand.LearnFacePrompt
            }
            lowerText.contains("learn place") || lowerText.contains("remember place") -> {
                extractName(lowerText)?.let { VoiceCommand.LearnPlace(it) } ?: VoiceCommand.LearnPlacePrompt
            }
            lowerText.contains("who is this") || lowerText.contains("recognize face") -> {
                VoiceCommand.RecognizeFace
            }
            lowerText.contains("where is this") || lowerText.contains("recognize place") -> {
                VoiceCommand.RecognizePlace
            }
            
            // Object detection
            lowerText.contains("what do you see") || lowerText.contains("describe scene") -> {
                VoiceCommand.DescribeScene
            }
            lowerText.contains("find") && lowerText.contains("object") -> {
                VoiceCommand.FindObject
            }
            
            // Settings
            lowerText.contains("increase volume") || lowerText.contains("louder") -> {
                VoiceCommand.IncreaseVolume
            }
            lowerText.contains("decrease volume") || lowerText.contains("quieter") -> {
                VoiceCommand.DecreaseVolume
            }
            lowerText.contains("pause") || lowerText.contains("be quiet") -> {
                VoiceCommand.Pause
            }
            lowerText.contains("resume") || lowerText.contains("continue") -> {
                VoiceCommand.Resume
            }
            
            // Help
            lowerText.contains("help") || lowerText.contains("what can you do") -> {
                VoiceCommand.Help
            }
            
            else -> null
        }
    }
    
    /**
     * Extract name from command like "learn face John" or "remember place kitchen"
     */
    private fun extractName(text: String): String? {
        val patterns = listOf(
            "learn face (.+)",
            "remember face (.+)",
            "learn place (.+)",
            "remember place (.+)"
        )
        
        patterns.forEach { pattern ->
            val regex = pattern.toRegex()
            val match = regex.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1].trim()
            }
        }
        
        return null
    }
    
    // Callback setters
    fun setOnCommandRecognizedListener(listener: (VoiceCommand) -> Unit) {
        onCommandRecognized = listener
    }
    
    fun setOnListeningStateChangedListener(listener: (Boolean) -> Unit) {
        onListeningStateChanged = listener
    }
    
    fun isCurrentlyListening() = isListening
    
    fun cleanup() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}