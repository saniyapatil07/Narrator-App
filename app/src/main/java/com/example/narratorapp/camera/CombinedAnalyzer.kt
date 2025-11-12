package com.example.narratorapp.camera

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.util.Log
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.detection.ObjectDetector
import com.example.narratorapp.memory.FaceDetector
import com.example.narratorapp.memory.MemoryManager
import com.example.narratorapp.memory.RecognitionType
import com.example.narratorapp.narration.DecisionEngine
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.NavigationEngine
import com.example.narratorapp.ocr.OCRLine
import com.example.narratorapp.ocr.OCRProcessor
import com.example.narratorapp.utils.ImageUtils
import kotlinx.coroutines.*


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
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var lastTextDetectionTime = 0L
    private val textCooldown = 2000L // 2 seconds
    
    private var lastFaceRecognitionTime = 0L
    private val faceRecognitionCooldown = 5000L // 5 seconds
    
    private var lastPlaceRecognitionTime = 0L
    private val placeRecognitionCooldown = 10000L // 10 seconds

    enum class Mode {
        OBJECT_AND_TEXT,    // Default: objects + text
        READING_ONLY,       // OCR only
        RECOGNITION_MODE    // Face/place recognition
    }

    var mode = Mode.OBJECT_AND_TEXT

    override fun analyze(image: ImageProxy) {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = ImageUtils.imageProxyToBitmap(image)
        val rotatedBitmap = ImageUtils.rotateBitmap(bitmap, rotationDegrees.toFloat())

        when (mode) {
            Mode.OBJECT_AND_TEXT -> {
                processObjectsAndText(rotatedBitmap, image)
            }

            Mode.READING_ONLY -> {
                processTextOnly(rotatedBitmap, image)
            }
            
            Mode.RECOGNITION_MODE -> {
                processRecognition(rotatedBitmap, image)
            }
        }
    }
    
    private fun processObjectsAndText(bitmap: android.graphics.Bitmap, image: ImageProxy) {
        val detections = objectDetector.detect(bitmap)
        Log.d("CombinedAnalyzer", "Objects detected: ${detections.size}")

        // Pass detections to navigation engine for obstacle warnings
        navigationEngine?.processObstacles(detections)

        ocrProcessor.detect(bitmap) { texts ->
            val textDetected = texts.isNotEmpty()
            if (textDetected) {
                val now = System.currentTimeMillis()
                if (now - lastTextDetectionTime > textCooldown) {
                    lastTextDetectionTime = now
                    Log.d("CombinedAnalyzer", "⚡ Text detected! (${texts.size} lines)")
                    decisionEngine.process(detections, texts)
                }
            } else {
                decisionEngine.process(detections, emptyList())
            }

            // Check for faces in detected "person" objects
            if (memoryManager != null) {
                val personDetections = detections.filter { it.label == "person" && it.confidence > 0.6f }
                if (personDetections.isNotEmpty()) {
                    tryRecognizeFaces(bitmap, personDetections)
                }
            }

            overlayView?.apply {
                this.objects = detections
                this.texts = texts
                postInvalidate()
            }
            image.close()
        }
    }

    private fun processTextOnly(bitmap: android.graphics.Bitmap, image: ImageProxy) {
        ocrProcessor.detect(bitmap) { texts ->
            if (texts.isNotEmpty()) {
                Log.d("CombinedAnalyzer", "📖 Reading Mode: ${texts.size} lines detected")
                decisionEngine.process(emptyList(), texts)

                overlayView?.apply {
                    this.objects = emptyList()
                    this.texts = texts
                    postInvalidate()
                }
            }
            image.close()
        }
    }
    
    private fun processRecognition(bitmap: android.graphics.Bitmap, image: ImageProxy) {
        if (memoryManager == null) {
            image.close()
            return
        }
        
        val now = System.currentTimeMillis()
        
        // Try face recognition
        if (now - lastFaceRecognitionTime > faceRecognitionCooldown) {
            scope.launch {
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
        
        // Try place recognition periodically
        if (now - lastPlaceRecognitionTime > placeRecognitionCooldown) {
            scope.launch {
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
        
        image.close()
    }
    
    private fun tryRecognizeFaces(bitmap: android.graphics.Bitmap, personDetections: List<DetectedObject>) {
        val now = System.currentTimeMillis()
        if (now - lastFaceRecognitionTime < faceRecognitionCooldown) return
        
        scope.launch {
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
    }
}