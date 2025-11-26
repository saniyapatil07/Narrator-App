package com.example.narratorapp.narration
import android.util.Log
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

class DecisionEngine(private val ttsManager: TTSManager) {

    private var lastNarrationTime = 0L
    private val narrationCooldown = 1500L

    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastNarrationTime
        
        // LOG INPUTS
        Log.d("DecisionDebug", "Processing: ${objects.size} Objects, ${texts.size} Text Lines. Time since last speak: ${timeSinceLast}ms")

        if (timeSinceLast < narrationCooldown) {
            Log.d("DecisionDebug", "SKIPPING: Cooldown active. Wait ${narrationCooldown - timeSinceLast}ms more.")
            return
        }

        if (objects.isNotEmpty()) {
            val obj = objects.first()
            Log.i("DecisionDebug", "DECISION: Speaking Object -> ${obj.label}")
            ttsManager.speak("I see a ${obj.label}")
            lastNarrationTime = now
            return
        }

        if (texts.isNotEmpty()) {
            Log.i("DecisionDebug", "DECISION: Speaking Text -> ${texts.first().text}")
            ttsManager.speak("Text ahead: ${texts.first().text}")
            lastNarrationTime = now
            return
        }
        
        Log.d("DecisionDebug", "DECISION: Nothing to speak (No objects, no text)")
    }
}
        
    
