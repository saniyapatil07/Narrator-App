package com.example.narratorapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class ObjectDetector(context: Context) {

    private val confidenceThreshold = 0.1f
    private val iouThreshold = 0.5f
    private val inputSize = 320
    private val numThreads = 4
    private var interpreter: Interpreter? = null

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            val options = Interpreter.Options().apply { 
                setNumThreads(numThreads)
                setUseNNAPI(false)
            }
            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            Log.i("ObjectDetector", "=== MODEL INFO ===")
            Log.i("ObjectDetector", "Input shape: ${inputTensor.shape().contentToString()}")
            Log.i("ObjectDetector", "Input type: ${inputTensor.dataType()}")
            Log.i("ObjectDetector", "Output shape: ${outputTensor.shape().contentToString()}")
            Log.i("ObjectDetector", "Output type: ${outputTensor.dataType()}")
            Log.i("ObjectDetector", "Confidence threshold: $confidenceThreshold")
            Log.i("ObjectDetector", "==================")
            
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error loading TFLite model", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        if (interpreter == null) return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resizedBitmap)

        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val outputBuffer = Array(outputShape[0]) {
            Array(outputShape[1]) { FloatArray(outputShape[2]) }
        }

        val startTime = SystemClock.uptimeMillis()
        interpreter!!.run(inputBuffer, outputBuffer)
        val inferenceTime = SystemClock.uptimeMillis() - startTime
        Log.i("ObjectDetector", "Inference time: $inferenceTime ms")

        // Debug output statistics
        if (outputBuffer[0].isNotEmpty()) {
            val allObjectness = outputBuffer[0].map { it[4] }
            val maxObj = allObjectness.maxOrNull() ?: 0f
            val avgObj = allObjectness.average().toFloat()
            
            val allClassScores = outputBuffer[0].map { pred -> 
                (5 until pred.size).maxOfOrNull { pred[it] } ?: 0f 
            }
            val maxClass = allClassScores.maxOrNull() ?: 0f
            val avgClass = allClassScores.average().toFloat()
            
            Log.i("ObjectDetector", "Objectness - max: $maxObj, avg: $avgObj")
            Log.i("ObjectDetector", "Class scores - max: $maxClass, avg: $avgClass")
            
            // Find best prediction for debugging
            val bestPrediction = outputBuffer[0].maxByOrNull { pred ->
                val obj = pred[4]
                val cls = (5 until pred.size).maxOfOrNull { pred[it] } ?: 0f
                obj * cls
            }
            
            if (bestPrediction != null) {
                val bestObj = bestPrediction[4]
                val bestClassIdx = (5 until bestPrediction.size).maxByOrNull { bestPrediction[it] } ?: 5
                val bestClass = bestPrediction[bestClassIdx]
                val bestConf = bestObj * bestClass
                
                Log.i("ObjectDetector", "Best: ${labels.getOrNull(bestClassIdx - 5) ?: "?"} " +
                      "obj=$bestObj × cls=$bestClass = $bestConf")
            }
        }

        val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
        Log.i("ObjectDetector", "Raw detections: ${detections.size}")
        
        val finalDetections = nonMaxSuppression(detections)
        Log.i("ObjectDetector", "After NMS: ${finalDetections.size}")
        
        if (finalDetections.isNotEmpty()) {
            finalDetections.take(3).forEach { obj ->
                Log.i("ObjectDetector", "✓ ${obj.label} at ${obj.confidencePercent()}")
            }
        }
        
        return finalDetections
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // ImageNet Mean and StdDev values (from your report)
        // These are standard for models trained on ImageNet (like many TFLite object detectors)
        val MEAN_R = 123.675f
        val MEAN_G = 116.28f
        val MEAN_B = 103.53f
        
        val STD_R = 58.395f
        val STD_G = 57.12f
        val STD_B = 57.375f

        for (pixel in intValues) {
            val r = (pixel shr 16 and 0xFF).toFloat()
            val g = (pixel shr 8 and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()

            // Apply ImageNet Normalization: (value - mean) / std
            // buffer.putFloat((r - MEAN_R) / STD_R)
            // buffer.putFloat((g - MEAN_G) / STD_G)
            // buffer.putFloat((b - MEAN_B) / STD_B)

            // Only try this if the ImageNet fix above doesn't work perfectly
            buffer.putFloat((b - MEAN_B) / STD_B) // Blue first
            buffer.putFloat((g - MEAN_G) / STD_G) // Green
            buffer.putFloat((r - MEAN_R) / STD_R) // Red
        }
        
        buffer.rewind()
        return buffer
    }

    private fun decodeYOLO(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): MutableList<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        
        for (prediction in output) {
            val objectness = prediction[4]
            
            // Find best class
            var maxClassScore = 0f
            var classId = -1
            for (i in 5 until prediction.size) {
                if (prediction[i] > maxClassScore) {
                    maxClassScore = prediction[i]
                    classId = i - 5
                }
            }
            
            // Calculate final confidence
            val confidence = objectness * maxClassScore
            
            if (confidence < confidenceThreshold) continue

            // Convert from normalized coordinates to pixels
            val xCenter = prediction[0] * originalWidth
            val yCenter = prediction[1] * originalHeight
            val width = prediction[2] * originalWidth
            val height = prediction[3] * originalHeight
            
            val x = xCenter - (width / 2)
            val y = yCenter - (height / 2)

            results.add(
                DetectedObject(
                    label = labels.getOrElse(classId) { "Unknown" },
                    confidence = confidence,
                    boundingBox = RectF(x, y, x + width, y + height)
                )
            )
        }
        
        return results
    }

    private fun nonMaxSuppression(detections: List<DetectedObject>): List<DetectedObject> {
        val finalDetections = mutableListOf<DetectedObject>()
        val byClass = detections.groupBy { it.label }

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }
            val selected = mutableListOf<DetectedObject>()

            for (detection in sorted) {
                var keep = true
                for (sel in selected) {
                    val iou = calculateIoU(detection.boundingBox, sel.boundingBox)
                    if (iou > iouThreshold) {
                        keep = false
                        break
                    }
                }
                if (keep) selected.add(detection)
            }
            finalDetections.addAll(selected)
        }
        return finalDetections
    }

    private fun calculateIoU(r1: RectF, r2: RectF): Float {
        val xA = max(r1.left, r2.left)
        val yA = max(r1.top, r2.top)
        val xB = min(r1.right, r2.right)
        val yB = min(r1.bottom, r2.bottom)

        val intersection = max(0f, xB - xA) * max(0f, yB - yA)
        val boxA = (r1.right - r1.left) * (r1.bottom - r1.top)
        val boxB = (r2.right - r2.left) * (r2.bottom - r2.top)
        val union = boxA + boxB - intersection

        return if (union > 0) intersection / union else 0f
    }
}