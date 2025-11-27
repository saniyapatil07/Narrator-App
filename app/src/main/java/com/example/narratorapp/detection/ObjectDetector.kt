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

    private val confidenceThreshold = 0.10f  // LOWERED for testing - you should see detections now!
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

        // Debug: Check what the model is actually outputting
        if (outputBuffer[0].isNotEmpty()) {
            val maxObj = outputBuffer[0].maxOfOrNull { it[4] } ?: 0f
            val maxClass = outputBuffer[0].maxOfOrNull { pred -> 
                (5 until pred.size).maxOfOrNull { pred[it] } ?: 0f 
            } ?: 0f
            val bestPrediction = outputBuffer[0].maxByOrNull { pred ->
                pred[4] * ((5 until pred.size).maxOfOrNull { pred[it] } ?: 0f)
            }
            
            if (bestPrediction != null) {
                val bestObj = bestPrediction[4]
                val bestClass = (5 until bestPrediction.size).maxOfOrNull { bestPrediction[it] } ?: 0f
                val bestClassId = (5 until bestPrediction.size).maxByOrNull { bestPrediction[it] } ?: 5
                val bestConf = bestObj * bestClass
                
                Log.i("ObjectDetector", "Best prediction: ${labels.getOrNull(bestClassId - 5) ?: "?"} " +
                      "obj=$bestObj × class=$bestClass = $bestConf")
            }
            
            Log.i("ObjectDetector", "Max objectness: $maxObj, Max class score: $maxClass")
        }

        val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
        Log.i("ObjectDetector", "Detections before NMS: ${detections.size}")
        
        val finalDetections = nonMaxSuppression(detections)
        Log.i("ObjectDetector", "Final detections: ${finalDetections.size}")
        
        if (finalDetections.isNotEmpty()) {
            finalDetections.take(3).forEach { obj ->
                Log.i("ObjectDetector", "✓ DETECTED: ${obj.label} at ${obj.confidencePercent()}")
            }
        }
        
        return finalDetections
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            buffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f))
            buffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))
            buffer.putFloat(((pixel and 0xFF) / 255.0f))
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