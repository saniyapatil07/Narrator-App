package com.example.narratorapp.narration

import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.NavigationEngine

class DecisionEngine(
    private val ttsManager: TTSManager,
    private val navigationEngine: NavigationEngine? = null
) {

    private var lastNarrationTime = 0L

    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        val now = System.currentTimeMillis()
        if (now - lastNarrationTime < 3000) return // throttle

        when {
            texts.isNotEmpty() -> {
                ttsManager.speak("Text ahead: ${texts.first().text}")
            }
            objects.isNotEmpty() -> {
                val obj = objects.first()
                ttsManager.speak("I see a ${obj.label}")
            }
        }

        lastNarrationTime = now
    }
}
