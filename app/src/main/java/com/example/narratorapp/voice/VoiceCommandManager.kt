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
    
    private val hotwords = listOf("hey narrator", "ok narrator", "hello narrator", "narrator")

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
    
    fun startListening() {
        if (isListening) return
        isHotwordMode = true
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
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Error: $error"
            }
            
            Log.e("VoiceCommandManager", "Recognition error: $errorMessage")
            
            if (error == SpeechRecognizer.ERROR_NO_MATCH && isHotwordMode) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 500)
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            
            if (matches.isNullOrEmpty()) {
                if (isHotwordMode) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isListening) startRecognition()
                    }, 500)
                }
                return
            }
            
            val spokenText = matches[0].lowercase(Locale.getDefault())
            Log.d("VoiceCommandManager", "Recognized: $spokenText")
            
            if (isHotwordMode) {
                if (hotwords.any { spokenText.contains(it) }) {
                    Log.d("VoiceCommandManager", "Hotword detected!")
                    ttsManager.speak("Yes, listening")
                    
                    isHotwordMode = false
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startRecognition()
                    }, 1000)
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isListening) startRecognition()
                    }, 500)
                }
            } else {
                val command = parseCommand(spokenText)
                if (command != null) {
                    onCommandRecognized?.invoke(command)
                } else {
                    ttsManager.speak("Sorry, I didn't understand that command")
                }
                
                isHotwordMode = true
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 1500)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    private fun parseCommand(text: String): VoiceCommand? {
        val lowerText = text.lowercase(Locale.getDefault()).trim()
        
        return when {
            lowerText.contains("start navigation") -> VoiceCommand.StartNavigation
            lowerText.contains("stop navigation") -> VoiceCommand.StopNavigation
            lowerText.contains("record waypoint") -> VoiceCommand.RecordWaypoint
            lowerText.contains("where am i") -> VoiceCommand.GetLocation
            lowerText.contains("read text") || lowerText.contains("reading mode") -> VoiceCommand.EnableReadingMode
            lowerText.contains("stop reading") -> VoiceCommand.DisableReadingMode
            lowerText.contains("learn face") -> extractName(lowerText)?.let { VoiceCommand.LearnFace(it) } ?: VoiceCommand.LearnFacePrompt
            lowerText.contains("learn place") -> extractName(lowerText)?.let { VoiceCommand.LearnPlace(it) } ?: VoiceCommand.LearnPlacePrompt
            lowerText.contains("who is this") -> VoiceCommand.RecognizeFace
            lowerText.contains("where is this") -> VoiceCommand.RecognizePlace
            lowerText.contains("what do you see") -> VoiceCommand.DescribeScene
            lowerText.contains("find object") -> VoiceCommand.FindObject
            lowerText.contains("increase volume") -> VoiceCommand.IncreaseVolume
            lowerText.contains("decrease volume") -> VoiceCommand.DecreaseVolume
            lowerText.contains("pause") -> VoiceCommand.Pause
            lowerText.contains("resume") -> VoiceCommand.Resume
            lowerText.contains("help") -> VoiceCommand.Help
            else -> null
        }
    }
    
    private fun extractName(text: String): String? {
        val patterns = listOf("learn face (.+)", "learn place (.+)")
        patterns.forEach { pattern ->
            val regex = pattern.toRegex()
            val match = regex.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1].trim()
            }
        }
        return null
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
