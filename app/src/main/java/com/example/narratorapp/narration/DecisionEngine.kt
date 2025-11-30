package com.example.narratorapp.narration

import android.util.Log
import com.example.narratorapp.camera.CombinedAnalyzer
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

class DecisionEngine(private val ttsManager: TTSManager) {

    // Throttling for announcements
    private var lastNarrationTime = 0L
    private val narrationCooldown = 2000L  // 2 seconds
    
    private var lastObjectNarrationTime = 0L
    private val objectNarrationCooldown = 500L  // 0.5 second
    
    private val seenObjects = mutableMapOf<String, Long>()
    private val objectMemoryDuration = 3000L  // 3 seconds
    
    private val objectDetectionCount = mutableMapOf<String, Int>()
    private val requiredConsecutiveDetections = 1  // Announce immediately
    
    private var processCallCount = 0

    // NEW: Process objects WITH depth and position data
    fun processWithDepth(objectsWithDepth: List<CombinedAnalyzer.ObjectWithDepth>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        if (processCallCount % 10 == 0) {
            Log.i("DecisionEngine", "=== PROCESS CALL #$processCallCount ===")
            Log.i("DecisionEngine", "Objects with depth: ${objectsWithDepth.size}")
        }
        
        if (objectsWithDepth.isEmpty()) {
            objectDetectionCount.clear()
            return
        }
        
        Log.i("DecisionEngine", "Processing ${objectsWithDepth.size} objects with depth...")
        objectsWithDepth.take(3).forEach { data ->
            Log.i("DecisionEngine", "  - ${data.obj.label}: ${data.obj.confidencePercent()}, " +
                  "depth=${data.depth?.let { "%.1fm".format(it) } ?: "unknown"}, ${data.position}")
        }
        
        announceObjectsWithDepth(objectsWithDepth, now)
    }
    
    // EXISTING: Keep for backwards compatibility
    fun process(objects: List<DetectedObject>, texts: List<OCRLine>) {
        processCallCount++
        val now = System.currentTimeMillis()
        
        if (processCallCount % 10 == 0) {
            Log.i("DecisionEngine", "=== PROCESS CALL #$processCallCount ===")
            Log.i("DecisionEngine", "Objects: ${objects.size}, Texts: ${texts.size}")
        }
        
        // Priority 1: Announce text if present
        if (texts.isNotEmpty()) {
            if (now - lastNarrationTime > narrationCooldown) {
                announceText(texts.first())
                lastNarrationTime = now
            }
            return
        }
        
        // Priority 2: Announce objects (without depth - fallback)
        if (objects.isNotEmpty()) {
            Log.i("DecisionEngine", "Processing ${objects.size} objects (no depth)...")
            announceObjects(objects, now)
        } else {
            objectDetectionCount.clear()
        }
    }
    
    private fun announceText(text: OCRLine) {
        val cleanText = text.text.trim()
        if (cleanText.length < 2) {
            Log.d("DecisionEngine", "Skipping single character: '$cleanText'")
            return
        }
        
        val announcement = if (cleanText.length > 50) {
            cleanText.take(50)
        } else {
            cleanText
        }
        
        Log.i("DecisionEngine", "🔊 READING: $announcement")
        ttsManager.speak(announcement)
    }
    
    private fun announceObjectsWithDepth(objectsWithDepth: List<CombinedAnalyzer.ObjectWithDepth>, now: Long) {
        // Clean up old memories
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        // Update detection counts
        val currentLabels = objectsWithDepth.map { it.obj.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        for (data in objectsWithDepth.filter { it.obj.confidence > 0.08f }) {
            val oldCount = objectDetectionCount[data.obj.label] ?: 0
            objectDetectionCount[data.obj.label] = oldCount + 1
        }
        
        // Find objects ready to announce
        val confirmedObjects = objectsWithDepth
            .filter { it.obj.confidence > 0.05f }
            .filter { 
                val count = objectDetectionCount[it.obj.label] ?: 0
                count >= requiredConsecutiveDetections
            }
            .sortedByDescending { it.obj.confidence }
            .filter { data ->
                val lastSeen = seenObjects[data.obj.label]
                lastSeen == null || (now - lastSeen) > objectMemoryDuration
            }
        
        Log.i("DecisionEngine", "Confirmed objects ready: ${confirmedObjects.size}")
        
        if (confirmedObjects.isEmpty()) return
        
        // Announce the most confident new object
        val timeSinceLastAnnouncement = now - lastObjectNarrationTime
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown) {
            val dataToAnnounce = confirmedObjects.first()
            Log.i("DecisionEngine", "✅ ANNOUNCING: ${dataToAnnounce.obj.label}")
            announceObjectWithDepthAndPosition(dataToAnnounce)
            seenObjects[dataToAnnounce.obj.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
            
            objectDetectionCount[dataToAnnounce.obj.label] = 0
        }
    }
    
    private fun announceObjectWithDepthAndPosition(data: CombinedAnalyzer.ObjectWithDepth) {
        val obj = data.obj
        val depth = data.depth
        val position = data.position
        
        // Build announcement with depth and position
        val depthStr = if (depth != null) {
            when {
                depth < 0.5f -> "very close"
                depth < 1.0f -> String.format("%.1f meters away", depth)
                depth < 3.0f -> String.format("%.1f meters away", depth)
                else -> "far ahead"
            }
        } else {
            null
        }
        
        val announcement = buildString {
            // Start with confidence level
            when {
                obj.confidence > 0.30f -> append("${obj.label} ")
                obj.confidence > 0.15f -> append("${obj.label} detected ")
                else -> append("Possibly ${obj.label} ")
            }
            
            // Add position
            append(position)
            
            // Add depth if available
            if (depthStr != null) {
                append(", $depthStr")
            }
        }
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement'")
        Log.i("DecisionEngine", "   Confidence: ${obj.confidencePercent()}, Depth: ${depth?.let { "%.2fm".format(it) } ?: "N/A"}")
        
        ttsManager.speak(announcement)
    }
    
    // Fallback: announce without depth (for backwards compatibility)
    private fun announceObjects(objects: List<DetectedObject>, now: Long) {
        seenObjects.entries.removeIf { now - it.value > objectMemoryDuration }
        
        val currentLabels = objects.map { it.label }.toSet()
        objectDetectionCount.keys.retainAll(currentLabels)
        
        for (obj in objects.filter { it.confidence > 0.08f }) {
            val oldCount = objectDetectionCount[obj.label] ?: 0
            objectDetectionCount[obj.label] = oldCount + 1
        }
        
        val confirmedObjects = objects
            .filter { it.confidence > 0.05f }
            .filter { 
                val count = objectDetectionCount[it.label] ?: 0
                count >= requiredConsecutiveDetections
            }
            .sortedByDescending { it.confidence }
            .filter { obj ->
                val lastSeen = seenObjects[obj.label]
                lastSeen == null || (now - lastSeen) > objectMemoryDuration
            }
        
        if (confirmedObjects.isEmpty()) return
        
        val timeSinceLastAnnouncement = now - lastObjectNarrationTime
        
        if (timeSinceLastAnnouncement > objectNarrationCooldown) {
            val objToAnnounce = confirmedObjects.first()
            announceObjectSimple(objToAnnounce)
            seenObjects[objToAnnounce.label] = now
            lastObjectNarrationTime = now
            lastNarrationTime = now
            
            objectDetectionCount[objToAnnounce.label] = 0
        }
    }
    
    private fun announceObjectSimple(obj: DetectedObject) {
        val announcement = when {
            obj.confidence > 0.30f -> "I see a ${obj.label}"
            obj.confidence > 0.15f -> "${obj.label} detected"
            else -> "Possibly a ${obj.label}"
        }
        
        Log.i("DecisionEngine", "🔊 ANNOUNCING: '$announcement' (no depth)")
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
        lastNarrationTime = 0L
        lastObjectNarrationTime = 0L
        Log.i("DecisionEngine", "Engine reset")
    }
}