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

    private val confidenceThreshold = 0.4f
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
            val options = Interpreter.Options().apply { setNumThreads(numThreads) }
            interpreter = Interpreter(modelBuffer, options)
            Log.i("ObjectDetector", "TFLite Interpreter loaded successfully.")
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error loading TFLite model", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        if (interpreter == null) return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resizedBitmap)

        val outputShape = interpreter!!.getOutputTensor(0).shape()
        Log.e("ObjectDetector", "MODEL SHAPE: [${outputShape.joinToString(",")}]")
        val outputBuffer = Array(outputShape[0]) {
            Array(outputShape[1]) { FloatArray(outputShape[2]) }
        }

        val startTime = SystemClock.uptimeMillis()
        interpreter!!.run(inputBuffer, outputBuffer)
        val inferenceTime = SystemClock.uptimeMillis() - startTime
        Log.i("ObjectDetector", "Inference time: $inferenceTime ms")

        val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
        return nonMaxSuppression(detections)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)

            // CHANGED: Use (Value - Mean) / StdDev for [-1.0 to 1.0] range
            buffer.putFloat((r - 127.5f) / 127.5f)
            buffer.putFloat((g - 127.5f) / 127.5f)
            buffer.putFloat((b - 127.5f) / 127.5f)
        }
        buffer.rewind()
        return buffer
    }

    private fun decodeYOLO(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): List<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        val rows = output.size
        val cols = output[0].size
        
        // Check if model is transposed
        val isTransposed = (rows == 85 && cols > 85) 
        val numAnchors = if (isTransposed) cols else rows
        val numClassParams = if (isTransposed) rows else cols
        
        // LOG ONCE: Print the raw numbers of the first box to see what the model is thinking
        val firstConf = if (isTransposed) output[4][0] else output[0][4]
        val firstX = if (isTransposed) output[0][0] else output[0][0]
        Log.v("ObjectDetector", "DEBUG: First Anchor -> X:$firstX, Conf:$firstConf")

        for (i in 0 until numAnchors) {
            val confidence = if (isTransposed) output[4][i] else output[i][4]
            
            // LOW THRESHOLD DEBUGGING
            // If you see negative numbers in logs, the model outputs 'Logits' and needs a sigmoid function.
            if (confidence > 0.1f) { 
                var maxClassScore = 0f
                var classId = -1
                
                for (c in 5 until numClassParams) {
                    val score = if (isTransposed) output[c][i] else output[i][c]
                    if (score > maxClassScore) {
                        maxClassScore = score
                        classId = c - 5
                    }
                }

                if (maxClassScore > confidenceThreshold) {
                    val xRaw = if (isTransposed) output[0][i] else output[i][0]
                    val yRaw = if (isTransposed) output[1][i] else output[i][1]
                    val wRaw = if (isTransposed) output[2][i] else output[i][2]
                    val hRaw = if (isTransposed) output[3][i] else output[i][3]

                    val xCenter = xRaw * originalWidth
                    val yCenter = yRaw * originalHeight
                    val width = wRaw * originalWidth
                    val height = hRaw * originalHeight
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
            }
        }
        
        if (results.isNotEmpty()) {
             Log.i("ObjectDetector", "Found ${results.size} objects!")
        }
        
        return nonMaxSuppression(results)
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