package com.example.narratorapp.camera

import android.content.Context
import android.graphics.Bitmap
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
import java.util.concurrent.LinkedBlockingQueue

/**
 * DROP-IN REPLACEMENT for existing CombinedAnalyzer
 * Same API, optimized with double-buffering and parallel processing
 */
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
    
    // OPTIMIZED: Separate dispatchers for parallel execution
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detectionDispatcher = Dispatchers.Default.limitedParallelism(1)
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ocrDispatcher = Dispatchers.Default.limitedParallelism(1)
    
    private val scope = CoroutineScope(SupervisorJob())
    
    // OPTIMIZED: Double buffer queue
    private val bitmapQueue = LinkedBlockingQueue<BitmapTask>(2)
    
    // OPTIMIZED: Processing flags
    private val isDetecting = AtomicBoolean(false)
    private val isOCRing = AtomicBoolean(false)
    
    // Same throttling as before
    private var lastAnalysisTime = 0L
    private val analysisInterval = 200L
    
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
        
        // Stats logging (same as before)
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
        
        lastAnalysisTime = now
        processedFrameCount++
        
        // Convert image ONCE, close immediately
        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = ImageUtils.imageProxyToBitmap(image)
        image.close()  // Release ASAP
        
        // OPTIMIZED: Queue for async processing
        // val rotatedBitmap = ImageUtils.rotateBitmap(bitmap, rotationDegrees.toFloat())
        val task = BitmapTask(bitmap, bitmap.width, bitmap.height, rotationDegrees)
        
        if (!bitmapQueue.offer(task)) {
            Log.d("CombinedAnalyzer", "⚠️ Queue full, dropping frame")
            return
        }
        
        // Trigger processing based on mode
        when (mode) {
            Mode.OBJECT_AND_TEXT -> processObjectsAndText()
            Mode.READING_ONLY -> processTextOnly()
            Mode.RECOGNITION_MODE -> processRecognition()
        }
    }
    
    // OPTIMIZED: Detection with depth and position announcements
    private fun processObjectsAndText() {
        val task = bitmapQueue.poll() ?: return
        val bitmap = task.bitmap
        
        // Detection job (high priority)
        if (isDetecting.compareAndSet(false, true)) {
            scope.launch(detectionDispatcher) {
                try {
                    val startTime = System.currentTimeMillis()
                    val detections = objectDetector.detect(bitmap)
                    val detectionTime = System.currentTimeMillis() - startTime
                    
                    if (mode != Mode.OBJECT_AND_TEXT) {
                    Log.i("CombinedAnalyzer", "⚠️ Dropping object detection result (Mode changed)")
                    return@launch
                    }
                    Log.i("CombinedAnalyzer", "📦 DETECTIONS: ${detections.size} objects in ${detectionTime}ms")
                    if (detections.isNotEmpty()) {
                        detections.take(3).forEach { obj ->
                            Log.i("CombinedAnalyzer", "  ✓ ${obj.label}: ${obj.confidencePercent()}")
                        }
                    }
                    
                    // Calculate depth for detected objects
                    val depthData = mutableMapOf<String, ObjectWithDepth>()
                    if (detections.isNotEmpty() && navigationEngine != null) {
                        for (obj in detections) {
                            val depth = navigationEngine.arCoreManager.getDepthForBoundingBox(obj.boundingBox)
                            val position = getObjectPosition(obj, task.width, task.height)
                            depthData[obj.label] = ObjectWithDepth(obj, depth, position)
                        }
                    }
                    
                    // Send to navigation with depth
                    navigationEngine?.processObstacles(detections)
                    
                    // Update UI
                    withContext(Dispatchers.Main) {
                        overlayView?.objects = detections
                        overlayView?.postInvalidate()
                    }
                    
                    // Send to DecisionEngine with depth data
                    decisionEngine.processWithDepth(depthData.values.toList())
                    
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "Detection error", e)
                } finally {
                    isDetecting.set(false)
                }
            }
        }
         
        // Background face recognition (if needed)
        if (memoryManager != null && frameCount % 10 == 0) {
            scope.launch(detectionDispatcher) {
                val detections = overlayView?.objects ?: emptyList()
                val personDetections = detections.filter { 
                    it.label == "person" && it.confidence > 0.6f 
                }
                if (personDetections.isNotEmpty()) {
                    tryRecognizeFaces(bitmap, personDetections)
                }
            }
        }
    }
    
    // Calculate object position (left, center, right)
    private fun getObjectPosition(obj: DetectedObject, imageWidth: Int, imageHeight: Int): String {
        val centerX = obj.boundingBox.centerX()
        val leftThird = imageWidth / 3f
        val rightThird = imageWidth * 2f / 3f
        
        return when {
            centerX < leftThird -> "on your left"
            centerX > rightThird -> "on your right"
            else -> "ahead"
        }
    }

    private fun processTextOnly() {
        val task = bitmapQueue.poll() ?: return
        val bitmap = task.bitmap
        
        // ONLY OCR - no object detection in reading mode
        if (isOCRing.compareAndSet(false, true)) {
            scope.launch(ocrDispatcher) {
                try {
                    val texts = ocrProcessor.detectSync(bitmap,rotationDegrees = task.rotationDegrees)
                    
                    if (texts.isNotEmpty()) {
                        Log.i("CombinedAnalyzer", "📖 READING MODE: ${texts.size} text blocks")
                        
                        // Update UI - clear objects, show only text
                        withContext(Dispatchers.Main) {
                            overlayView?.apply {
                                this.objects = emptyList()  // Clear object boxes
                                this.texts = texts
                                postInvalidate()
                            }
                        }
                        
                        // Send ONLY text to decision engine
                        decisionEngine.process(emptyList(), texts)
                    }
                } catch (e: Exception) {
                    Log.e("CombinedAnalyzer", "OCR error", e)
                } finally {
                    isOCRing.set(false)
                }
            }
        }
    }
    
    private fun processRecognition() {
        val task = bitmapQueue.poll() ?: return
        val bitmap = task.bitmap
        
        if (memoryManager == null) return
        
        val now = System.currentTimeMillis()
        
        // Face recognition (throttled)
        if (now - lastFaceRecognitionTime > faceRecognitionCooldown) {
            scope.launch(detectionDispatcher) {
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
        
        // Place recognition (throttled more)
        if (now - lastPlaceRecognitionTime > placeRecognitionCooldown) {
            scope.launch(detectionDispatcher) {
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
    
    private suspend fun tryRecognizeFaces(bitmap: Bitmap, @Suppress("UNUSED_PARAMETER") personDetections: List<DetectedObject>) {
        val now = System.currentTimeMillis()
        if (now - lastFaceRecognitionTime < faceRecognitionCooldown) return
        
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
    
    fun cleanup() {
        scope.cancel()
        bitmapQueue.clear()
        faceDetector.shutdown()
        Log.d("CombinedAnalyzer", "Cleanup complete")
    }
    
    private data class BitmapTask(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )
    
    data class ObjectWithDepth(
        val obj: DetectedObject,
        val depth: Float?,
        val position: String
    )
}