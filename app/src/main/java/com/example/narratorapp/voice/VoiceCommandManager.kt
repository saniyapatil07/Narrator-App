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

class VoiceCommandManager(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isHotwordMode = true
    
    private var onCommandRecognized: ((VoiceCommand) -> Unit)? = null
    private var onListeningStateChanged: ((Boolean) -> Unit)? = null
    
    // Normalized hotwords (no spaces, lowercase)
    private val hotwords = listOf(
        "heynarrator", "oknarrator", "hellonarrator", "narrator",
        "haynarrator", "heynavigator", "hienarrator" // Common misspellings
    )

    init {
        initializeSpeechRecognizer()
    }
    
    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("VoiceCommandManager", "Speech recognition not available")
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)
    }
    
    // Updated: Accept a parameter to skip hotword check (for button presses)
    fun startListening(startWithHotword: Boolean = true) {
        if (isListening) return
        isHotwordMode = startWithHotword
        startRecognition()
    }
    
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
        }
        
        isListening = true
        speechRecognizer?.startListening(intent)
        onListeningStateChanged?.invoke(true)
    }
    
    private val recognitionListener = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("VoiceCommandManager", if (isHotwordMode) "Listening for hotword..." else "Listening for command...")
        }
        
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        
        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "NO MATCH (The phone heard nothing)"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "TIMEOUT (No speech detected)"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO ERROR"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT ERROR"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK ERROR"
                else -> "Error Code: $error"
            }
            
            // LOG THE ERROR
            Log.e("VoiceDebug", "Recognition FAILED: $errorMessage")
            
            if (isListening && (isHotwordMode || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 500)
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            
            if (matches.isNullOrEmpty()) {
                Log.w("VoiceDebug", "Results returned but MATCH LIST IS EMPTY")
                if (isListening) startRecognition()
                return
            }
            
            // LOG ALL POSSIBILITIES
            Log.i("VoiceDebug", "Google heard these possibilities: $matches")
            
            // 1. Take the first result
            val rawSpokenText = matches[0]
            
            // 2. NORMALIZE: Remove ALL spaces and convert to lowercase
            val cleanText = rawSpokenText.filter { !it.isWhitespace() }.lowercase()
            
            Log.d("VoiceCommandManager", "Raw: '$rawSpokenText' -> Clean: '$cleanText'")
            
            if (isHotwordMode) {
                // Check if any hotword is inside the cleaned text
                if (hotwords.any { cleanText.contains(it) }) {
                    Log.d("VoiceCommandManager", "Hotword detected!")
                    ttsManager.speak("Yes?")
                    
                    isHotwordMode = false
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startRecognition()
                    }, 500)
                } else {
                    // Keep listening for hotword
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isListening) startRecognition()
                    }, 500)
                }
            } else {
                // Command Mode
                val command = parseCommand(cleanText) // Pass the cleaned text
                
                if (command != null) {
                    onCommandRecognized?.invoke(command)
                    // Go back to hotword mode after a successful command
                    isHotwordMode = true 
                } else {
                    ttsManager.speak("I didn't catch that")
                }
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 1000)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    private fun parseCommand(cleanText: String): VoiceCommand? {
        // Logic: Check if the cleaned string CONTAINS the command keyword
        
        return when {
            // Navigation
            cleanText.contains("startnavigation") -> VoiceCommand.StartNavigation
            cleanText.contains("stopnavigation") -> VoiceCommand.StopNavigation
            cleanText.contains("recordwaypoint") -> VoiceCommand.RecordWaypoint
            cleanText.contains("whereami") || cleanText.contains("location") -> VoiceCommand.GetLocation
            
            // Reading
            cleanText.contains("readtext") || cleanText.contains("readingmode") -> VoiceCommand.EnableReadingMode
            cleanText.contains("stopreading") || cleanText.contains("normalmode") -> VoiceCommand.DisableReadingMode
            
            // Memory (Face/Place)
            // Pattern: "learnfacerahul" -> starts with "learnface"
            cleanText.startsWith("learnface") -> {
                val name = cleanText.removePrefix("learnface")
                if (name.isNotEmpty()) VoiceCommand.LearnFace(name) else VoiceCommand.LearnFacePrompt
            }
            cleanText.startsWith("learnplace") -> {
                val name = cleanText.removePrefix("learnplace")
                if (name.isNotEmpty()) VoiceCommand.LearnPlace(name) else VoiceCommand.LearnPlacePrompt
            }
            cleanText.contains("whoisthis") -> VoiceCommand.RecognizeFace
            cleanText.contains("whereisthis") -> VoiceCommand.RecognizePlace
            
            // Scene
            cleanText.contains("whatdoyousee") || cleanText.contains("describescene") -> VoiceCommand.DescribeScene
            cleanText.contains("findobject") -> VoiceCommand.FindObject
            
            // Controls
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
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}