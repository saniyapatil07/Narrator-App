package com.example.narratorapp.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.detection.ObjectDetector
import com.example.narratorapp.memory.FaceDetector
import com.example.narratorapp.memory.MemoryManager
import com.example.narratorapp.narration.DecisionEngine
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.NavigationEngine
import com.example.narratorapp.ocr.OCRLine
import com.example.narratorapp.ocr.OCRProcessor
import com.example.narratorapp.utils.ImageUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class CombinedAnalyzer(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val overlayView: OverlayView? = null,
    private val navigationEngine: NavigationEngine? = null,
    private val memoryManager: MemoryManager? = null
) : ImageAnalysis.Analyzer {

    private val objectDetector = ObjectDetector(context)
    private val ocrProcessor = OCRProcessor()
    private val faceDetector = FaceDetector()
    private val decisionEngine = DecisionEngine(ttsManager)
    
    // Use a SINGLE background thread for all ML work
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mlDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(mlDispatcher + SupervisorJob())
    
    // REDUCED throttling for testing
    private var lastAnalysisTime = 0L
    private val analysisInterval = 200L  // Process every 200ms (5 FPS) - was 300ms
    
    private val isProcessing = AtomicBoolean(false)
    
    private var lastTextDetectionTime = 0L
    private val textCooldown = 2000L
    
    private var lastFaceRecognitionTime = 0L
    private val faceRecognitionCooldown = 5000L
    
    private var lastPlaceRecognitionTime = 0L
    private val placeRecognitionCooldown = 10000L
    
    private var frameCount = 0
    private var processedFrameCount = 0

    enum class Mode {
        OBJECT_AND_TEXT,
        READING_ONLY,
        RECOGNITION_MODE
    }

    var mode = Mode.OBJECT_AND_TEXT

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        frameCount++
        
        // Log every 30 frames
        if (frameCount % 30 == 0) {
            Log.i("CombinedAnalyzer", "=== FRAME STATS ===")
            Log.i("CombinedAnalyzer", "Total frames: $frameCount, Processed: $processedFrameCount")
            Log.i("CombinedAnalyzer", "Processing rate: ${(processedFrameCount.toFloat() / frameCount * 100).toInt()}%")
        }
        
        // Throttle frame rate
        if (now - lastAnalysisTime < analysisInterval) {
            image.close()
            return
        }
        
        // Drop frames if still processing
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d("CombinedAnalyzer", "⚠️ Frame dropped - still processing")
            image.close()
            return
        }
        
        lastAnalysisTime = now
        processedFrameCount++
        
        // Convert image ONCE, then close it immediately
        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = ImageUtils.imageProxyToBitmap(image)
        image.close()  // Release camera buffer ASAP
        
        // Do ALL heavy work in background
        scope.launch {
            try {
                val rotatedBitmap = ImageUtils.rotateBitmap(bitmap, rotationDegrees.toFloat())
                
                when (mode) {
                    Mode.OBJECT_AND_TEXT -> processObjectsAndText(rotatedBitmap)
                    Mode.READING_ONLY -> processTextOnly(rotatedBitmap)
                    Mode.RECOGNITION_MODE -> processRecognition(rotatedBitmap)
                }
            } catch (e: Exception) {
                Log.e("CombinedAnalyzer", "Analysis error", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }
    
    private suspend fun processObjectsAndText(bitmap: android.graphics.Bitmap) {
        val startTime = System.currentTimeMillis()
        
        // Run object detection in background
        val detections = withContext(mlDispatcher) {
            objectDetector.detect(bitmap)
        }
        
        val detectionTime = System.currentTimeMillis() - startTime
        
        Log.i("CombinedAnalyzer", "📦 DETECTIONS: ${detections.size} objects in ${detectionTime}ms")
        if (detections.isNotEmpty()) {
            detections.take(3).forEach { obj ->
                Log.i("CombinedAnalyzer", "  ✓ ${obj.label}: ${obj.confidencePercent()}")
            }
        }
        
        // Send to navigation (if active)
        navigationEngine?.processObstacles(detections)
        
        // Run OCR asynchronously (don't block on it)
        val ocrJob = scope.async(mlDispatcher) {
            try {
                ocrProcessor.detectSync(bitmap)
            } catch (e: Exception) {
                Log.e("CombinedAnalyzer", "OCR error", e)
                emptyList<OCRLine>()
            }
        }
        
        // Wait for OCR with timeout
        val texts = withTimeoutOrNull(500) {
            ocrJob.await()
        } ?: emptyList()
        
        if (texts.isNotEmpty()) {
            Log.i("CombinedAnalyzer", "📝 TEXT: ${texts.size} lines detected")
        }
        
        // CRITICAL: Process results and trigger announcements
        val now = System.currentTimeMillis()
        Log.d("CombinedAnalyzer", "🎤 Sending to DecisionEngine: ${detections.size} objects, ${texts.size} texts")
        
        if (texts.isNotEmpty() && now - lastTextDetectionTime > textCooldown) {
            lastTextDetectionTime = now
            decisionEngine.process(detections, texts)
        } else {
            // ALWAYS send detections to DecisionEngine
            decisionEngine.process(detections, emptyList())
        }
        
        // Background face recognition (non-blocking)
        if (memoryManager != null && frameCount % 10 == 0) {
            val personDetections = detections.filter { 
                it.label == "person" && it.confidence > 0.6f 
            }
            if (personDetections.isNotEmpty()) {
                tryRecognizeFaces(bitmap, personDetections)
            }
        }
        
        // Update UI on main thread
        withContext(Dispatchers.Main) {
            overlayView?.apply {
                this.objects = detections
                this.texts = texts
                postInvalidate()
            }
        }
    }

    private suspend fun processTextOnly(bitmap: android.graphics.Bitmap) {
        val texts = withContext(mlDispatcher) {
            ocrProcessor.detectSync(bitmap)
        }
        
        if (texts.isNotEmpty()) {
            decisionEngine.process(emptyList(), texts)
            
            withContext(Dispatchers.Main) {
                overlayView?.apply {
                    this.objects = emptyList()
                    this.texts = texts
                    postInvalidate()
                }
            }
        }
    }
    
    private suspend fun processRecognition(bitmap: android.graphics.Bitmap) {
        if (memoryManager == null) return
        
        val now = System.currentTimeMillis()
        
        // Face recognition (throttled)
        if (now - lastFaceRecognitionTime > faceRecognitionCooldown) {
            scope.launch(mlDispatcher) {
                try {
                    val faces = faceDetector.detectFaces(bitmap)
                    if (faces.isNotEmpty()) {
                        val bestFace = faces.first()
                        val result = memoryManager.recognizeFace(bestFace.bitmap)
                        
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                ttsManager.speak("Hello ${result.label}, confidence ${result.confidencePercent()} percent")
                            }
                            lastFaceRecognitionTime = now
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "Face recognition error", e)
                }
            }
        }
        
        // Place recognition (throttled even more)
        if (now - lastPlaceRecognitionTime > placeRecognitionCooldown) {
            scope.launch(mlDispatcher) {
                try {
                    val result = memoryManager.recognizePlace(bitmap)
                    if (result != null) {
                        withContext(Dispatchers.Main) {
                            ttsManager.speak("You are at ${result.label}")
                        }
                        lastPlaceRecognitionTime = now
                    }
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "Place recognition error", e)
                }
            }
        }
    }
    
    private fun tryRecognizeFaces(bitmap: android.graphics.Bitmap, @Suppress("UNUSED_PARAMETER") personDetections: List<DetectedObject>) {
        val now = System.currentTimeMillis()
        if (now - lastFaceRecognitionTime < faceRecognitionCooldown) return
        
        scope.launch(mlDispatcher) {
            try {
                val faces = faceDetector.detectFaces(bitmap)
                if (faces.isNotEmpty()) {
                    val bestFace = faces.first()
                    val result = memoryManager?.recognizeFace(bestFace.bitmap)
                    
                    if (result != null) {
                        withContext(Dispatchers.Main) {
                            ttsManager.speak("I see ${result.label}")
                        }
                        lastFaceRecognitionTime = now
                    }
                }
            } catch (e: Exception) {
                Log.e("CombinedAnalyzer", "Background face recognition error", e)
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
        faceDetector.shutdown()
        Log.d("CombinedAnalyzer", "Cleanup complete")
    }
}
