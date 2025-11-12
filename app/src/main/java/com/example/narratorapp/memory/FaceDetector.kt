package com.example.narratorapp.memory

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await


/**
 * Detects faces in images using ML Kit
 * Extracts face regions for recognition
 */
class FaceDetector {
    
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.15f) // Detect faces at least 15% of image
        .build()
    
    private val detector = FaceDetection.getClient(options)
    
    /**
     * Detect faces in bitmap
     * Returns list of face bounding boxes
     */
    suspend fun detectFaces(bitmap: Bitmap): List<FaceRegion> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(image).await()
            
            faces.mapNotNull { face ->
                face.boundingBox?.let { box ->
                    // Ensure bounding box is within image bounds
                    val safeBox = Rect(
                        maxOf(0, box.left),
                        maxOf(0, box.top),
                        minOf(bitmap.width, box.right),
                        minOf(bitmap.height, box.bottom)
                    )
                    
                    // Extract face region from bitmap
                    val faceBitmap = Bitmap.createBitmap(
                        bitmap,
                        safeBox.left,
                        safeBox.top,
                        safeBox.width(),
                        safeBox.height()
                    )
                    
                    FaceRegion(
                        bitmap = faceBitmap,
                        boundingBox = safeBox,
                        confidence = face.headEulerAngleY // Use head angle as proxy for quality
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FaceDetector", "Error detecting faces", e)
            emptyList()
        }
    }
    
    /**
     * Detect single best face (largest)
     */
    suspend fun detectBestFace(bitmap: Bitmap): FaceRegion? {
        val faces = detectFaces(bitmap)
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }
    
    fun shutdown() {
        detector.close()
    }
}

/**
 * Represents a detected face region
 */
data class FaceRegion(
    val bitmap: Bitmap,
    val boundingBox: Rect,
    val confidence: Float
)