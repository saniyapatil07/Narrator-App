package com.example.narratorapp.narration

import android.util.Log
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

class DecisionEngine(private val ttsManager: TTSManager) {

    private var lastNarrationTime = 0L
    private val narrationCooldown = 5000L  // 5 seconds between scene descriptions
    
    private var lastObjectNarrationTime = 0L
    private val objectNarrationCooldown = 2000L  // 2 seconds for individual objects
    
    private val seenObjects = mutableMapOf<String, Long>()  // Track what we've announced
    private val objectMemoryDuration = 10000L  // Remember objects for 10 seconds
    
    private var lastAnnouncedObjects = setOf<String>()

    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        val now = System.currentTimeMillis()
        
        // Priority 1: Announce text if present
        if (texts.isNotEmpty()) {
            if (now - lastNarrationTime > narrationCooldown) {
                announceText(texts.first())
                lastNarrationTime = now
            }
            return
        }
        
        // Priority 2: Announce objects
        if (objects.isNotEmpty()) {
            announceObjects(objects, now)
        }
    }
    
    private fun announceText(text: OCRLine) {
        val announcement = "Text detected: ${text.text}"
        ttsManager.speak(announcement)
        Log.d("DecisionEngine", "Announced: $announcement")
    }
    
    private fun announceObjects(objects: List<DetectedObject>, now: Long) {
        // Clean up old memories
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        // Filter to high-confidence objects we haven't announced recently
        val newObjects = objects
            .filter { it.confidence > 0.15f }
            .sortedByDescending { it.confidence }
            .filter { obj ->
                val lastSeen = seenObjects[obj.label]
                lastSeen == null || (now - lastSeen) > objectMemoryDuration
            }
        
        if (newObjects.isEmpty()) {
            // All objects are already known, do scene summary if cooldown passed
            if (now - lastNarrationTime > narrationCooldown && objects.isNotEmpty()) {
                announceSceneSummary(objects)
                lastNarrationTime = now
            }
            return
        }
        
        // Announce new/important objects
        if (now - lastObjectNarrationTime > objectNarrationCooldown) {
            val objToAnnounce = newObjects.first()
            announceObject(objToAnnounce)
            seenObjects[objToAnnounce.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
        }
    }
    
    private fun announceObject(obj: DetectedObject) {
        val announcement = when {
            obj.confidence > 0.30f -> "I see a ${obj.label}"
            obj.confidence > 0.20f -> "Possibly a ${obj.label}"
            else -> "${obj.label} detected"
        }
        
        ttsManager.speak(announcement)
        Log.d("DecisionEngine", "Announced: $announcement (confidence: ${obj.confidencePercent()})")
    }
    
    private fun announceSceneSummary(objects: List<DetectedObject>) {
        // Group by label and count
        val objectCounts = objects
            .groupBy { it.label }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)  // Top 3 object types
        
        if (objectCounts.isEmpty()) return
        
        val announcement = buildString {
            append("I see ")
            objectCounts.forEachIndexed { index, (label, count) ->
                if (index > 0 && index == objectCounts.size - 1) {
                    append(" and ")
                } else if (index > 0) {
                    append(", ")
                }
                
                if (count > 1) {
                    append("$count ${label}s")
                } else {
                    append("a $label")
                }
            }
        }
        
        ttsManager.speak(announcement)
        Log.d("DecisionEngine", "Scene summary: $announcement")
    }
    
    // Call this when user requests scene description
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
        lastAnnouncedObjects = emptySet()
        lastNarrationTime = 0L
        lastObjectNarrationTime = 0L
    }
}