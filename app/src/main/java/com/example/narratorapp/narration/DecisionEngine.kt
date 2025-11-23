package com.example.narratorapp.narration

import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

class DecisionEngine(private val ttsManager: TTSManager) {

    private var lastNarrationTime = 0L
    private val narrationCooldown = 3000L

    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        val now = System.currentTimeMillis()
        if (now - lastNarrationTime < narrationCooldown) return

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