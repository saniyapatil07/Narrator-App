package com.example.narratorapp.narration

import android.util.Log
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

class DecisionEngine(private val ttsManager: TTSManager) {

    // TEMPORARILY REDUCED for testing - you should hear announcements now!
    private var lastNarrationTime = 0L
    private val narrationCooldown = 2000L  // 2 seconds (was 3)
    
    private var lastObjectNarrationTime = 0L
    private val objectNarrationCooldown = 500L  // 0.5 second (was 1) - VERY FAST for testing
    
    private val seenObjects = mutableMapOf<String, Long>()
    private val objectMemoryDuration = 3000L  // 3 seconds (was 5) - shorter memory
    
    // REDUCED for testing - announce after seeing once
    private val objectDetectionCount = mutableMapOf<String, Int>()
    private val requiredConsecutiveDetections = 1  // Announce immediately (was 2)
    
    private var lastAnnouncedObjects = setOf<String>()
    
    private var processCallCount = 0

    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        // DIAGNOSTIC: Log every process call
        if (processCallCount % 10 == 0) {
            Log.i("DecisionEngine", "=== PROCESS CALL #$processCallCount ===")
            Log.i("DecisionEngine", "Objects received: ${objects.size}")
            Log.i("DecisionEngine", "Texts received: ${texts.size}")
        }
        
        // Priority 1: Announce text if present
        if (texts.isNotEmpty()) {
            if (now - lastNarrationTime > narrationCooldown) {
                announceText(texts.first())
                lastNarrationTime = now
            }
            return
        }
        
        // Priority 2: Announce objects (FASTER)
        if (objects.isNotEmpty()) {
            Log.i("DecisionEngine", "Processing ${objects.size} objects...")
            objects.take(3).forEach { obj ->
                Log.i("DecisionEngine", "  - ${obj.label}: ${obj.confidencePercent()} confidence")
            }
            announceObjects(objects, now)
        } else {
            // Clear detection counts when no objects visible
            if (objectDetectionCount.isNotEmpty()) {
                Log.d("DecisionEngine", "No objects detected, clearing counts")
            }
            objectDetectionCount.clear()
        }
    }
    
    private fun announceText(text: OCRLine) {
        // Filter out single characters and very short text
        val cleanText = text.text.trim()
        if (cleanText.length < 2) {
            Log.d("DecisionEngine", "Skipping single character: '$cleanText'")
            return
        }
        
        val announcement = if (cleanText.length > 50) {
            "Text detected: ${cleanText.take(50)}..."
        } else {
            "Text detected: $cleanText"
        }
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING TEXT: $announcement")
        ttsManager.speak(announcement)
    }
    
    private fun announceObjects(objects: List<DetectedObject>, now: Long) {
        // Clean up old memories
        val oldSize = seenObjects.size
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        val removedCount = oldSize - seenObjects.size
        if (removedCount > 0) {
            Log.d("DecisionEngine", "Removed $removedCount old object memories")
        }
        
        // Update detection counts
        val currentLabels = objects.map { it.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        Log.d("DecisionEngine", "Updating detection counts for ${objects.size} objects")
        for (obj in objects.filter { it.confidence > 0.08f }) {  // VERY LOW threshold for testing
            val oldCount = objectDetectionCount[obj.label] ?: 0
            objectDetectionCount[obj.label] = oldCount + 1
            Log.d("DecisionEngine", "  ${obj.label}: count now ${oldCount + 1} (need $requiredConsecutiveDetections)")
        }
        
        // Find objects that are ready to announce
        val confirmedObjects = objects
            .filter { it.confidence > 0.05f }  // VERY LOW for testing
            .filter { 
                val count = objectDetectionCount[it.label] ?: 0
                val ready = count >= requiredConsecutiveDetections
                if (!ready) {
                    Log.d("DecisionEngine", "  ${it.label} not ready: $count < $requiredConsecutiveDetections")
                }
                ready
            }
            .sortedByDescending { it.confidence }
            .filter { obj ->
                val lastSeen = seenObjects[obj.label]
                val isNew = lastSeen == null || (now - lastSeen) > objectMemoryDuration
                if (!isNew) {
                    Log.d("DecisionEngine", "  ${obj.label} seen recently: ${now - lastSeen!!}ms ago")
                }
                isNew
            }
        
        Log.i("DecisionEngine", "Confirmed objects ready to announce: ${confirmedObjects.size}")
        
        if (confirmedObjects.isEmpty()) {
            Log.d("DecisionEngine", "No new objects to announce")
            return
        }
        
        // Announce the most confident new object IMMEDIATELY
        val timeSinceLastAnnouncement = now - lastObjectNarrationTime
        Log.d("DecisionEngine", "Time since last announcement: ${timeSinceLastAnnouncement}ms (cooldown: ${objectNarrationCooldown}ms)")
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown) {
            val objToAnnounce = confirmedObjects.first()
            Log.i("DecisionEngine", "✅ READY TO ANNOUNCE: ${objToAnnounce.label}")
            announceObject(objToAnnounce)
            seenObjects[objToAnnounce.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
            
            // Reset detection count after announcement
            objectDetectionCount[objToAnnounce.label] = 0
        } else {
            Log.w("DecisionEngine", "⏳ COOLDOWN: Need ${objectNarrationCooldown - timeSinceLastAnnouncement}ms more")
        }
    }
    
    private fun announceObject(obj: DetectedObject) {
        val announcement = when {
            obj.confidence > 0.30f -> "I see a ${obj.label}"
            obj.confidence > 0.15f -> "${obj.label} detected"
            else -> "Possibly a ${obj.label}"
        }
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement' (confidence: ${obj.confidencePercent()})")
        Log.i("DecisionEngine", "🔊 TTS Manager state: initialized=${ttsManager.isInitialized()}, speaking=${ttsManager.isSpeaking()}")
        
        ttsManager.speak(announcement)
    }
    
    fun describeScene(objects: List<DetectedObject>) {
        if (objects.isEmpty()) {
            ttsManager.speak("No objects detected")
            return
        }
        
        val topObjects = objects
            .sortedByDescending { it.confidence }
            .take(5)
            .map { "${it.label} at ${it.confidencePercent()}" }
        
        val announcement = "Scene contains: ${topObjects.joinToString(", ")}"
        ttsManager.speak(announcement)
        Log.d("DecisionEngine", announcement)
    }
    
    fun reset() {
        seenObjects.clear()
        objectDetectionCount.clear()
        lastAnnouncedObjects = emptySet()
        lastNarrationTime = 0L
        lastObjectNarrationTime = 0L
        Log.i("DecisionEngine", "Engine reset")
    }
}