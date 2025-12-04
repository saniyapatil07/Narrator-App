package com.example.narratorapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * FIXED VERSION - Correct YOLO coordinate normalization
 */
class ObjectDetector(context: Context) {

    private val confidenceThreshold = 0.1f
    private val iouThreshold = 0.5f
    private val inputSize = 320
    private val numThreads = 4
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

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
    
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: Array<Array<FloatArray>>

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    try {
                        val delegateOptions = compatList.bestOptionsForThisDevice
                        gpuDelegate = GpuDelegate(delegateOptions)
                        addDelegate(gpuDelegate)
                        Log.i("ObjectDetector", "✓ GPU delegate enabled")
                    } catch (e: Exception) {
                        Log.w("ObjectDetector", "GPU delegate failed, using NNAPI", e)
                        setUseNNAPI(true)
                    }
                } else {
                    Log.i("ObjectDetector", "GPU not available, using NNAPI")
                    setUseNNAPI(true)
                }
                
                setAllowFp16PrecisionForFp32(true)
            }
            
            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            val inputBufferSize = inputSize * inputSize * 3 * 4
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize).apply {
                order(ByteOrder.nativeOrder())
            }
            
            val outputShape = outputTensor.shape()
            outputBuffer = Array(outputShape[0]) {
                Array(outputShape[1]) { FloatArray(outputShape[2]) }
            }
            
            Log.i("ObjectDetector", "=== MODEL INFO ===")
            Log.i("ObjectDetector", "Input: ${inputTensor.shape().contentToString()}")
            Log.i("ObjectDetector", "Output: ${outputShape.contentToString()}")
            Log.i("ObjectDetector", "GPU: ${gpuDelegate != null}, NNAPI: ${options.useNNAPI}")
            
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error loading model", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        if (interpreter == null) return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        
        try {
            inputBuffer.rewind()
            bitmapToByteBuffer(resizedBitmap, inputBuffer)

            val startTime = SystemClock.uptimeMillis()
            interpreter!!.run(inputBuffer, outputBuffer)
            val inferenceTime = SystemClock.uptimeMillis() - startTime
            
            if (Math.random() < 0.1) {
                Log.i("ObjectDetector", "Inference: ${inferenceTime}ms")
            }

            val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
            val finalDetections = nonMaxSuppression(detections)
            
            return finalDetections
        } finally {
            // Clean up scaled bitmap
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
        }
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap, buffer: ByteBuffer) {
        buffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

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

            buffer.putFloat((b - MEAN_B) / STD_R)
            buffer.putFloat((g - MEAN_G) / STD_G)
            buffer.putFloat((r - MEAN_R) / STD_B)
        }
    }

    // ===== CRITICAL FIX: Correct YOLO coordinate decoding =====
    private fun decodeYOLO(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): MutableList<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        
        for (prediction in output) {
            val objectness = prediction[4]
            
            if (objectness < confidenceThreshold) continue
            
            // Find best class
            var maxClassScore = 0f
            var classId = -1
            for (i in 5 until prediction.size) {
                if (prediction[i] > maxClassScore) {
                    maxClassScore = prediction[i]
                    classId = i - 5
                }
            }
            
            val confidence = objectness * maxClassScore
            if (confidence < confidenceThreshold) continue

            // ===== CRITICAL FIX: YOLO outputs are ALREADY normalized [0,1] =====
            // prediction[0] = center_x (0.0 to 1.0)
            // prediction[1] = center_y (0.0 to 1.0)
            // prediction[2] = width (0.0 to 1.0)
            // prediction[3] = height (0.0 to 1.0)
            
            val xCenter = prediction[0]  // Already 0-1
            val yCenter = prediction[1]  // Already 0-1
            val width = prediction[2]    // Already 0-1
            val height = prediction[3]   // Already 0-1
            
            // Convert center coordinates to top-left corner
            val xMin = xCenter - (width / 2f)
            val yMin = yCenter - (height / 2f)
            val xMax = xMin + width
            val yMax = yMin + height
            
            // Now scale to original image dimensions
            val left = xMin * originalWidth
            val top = yMin * originalHeight
            val right = xMax * originalWidth
            val bottom = yMax * originalHeight
            
            // Clamp to image bounds
            val clampedLeft = left.coerceIn(0f, originalWidth.toFloat())
            val clampedTop = top.coerceIn(0f, originalHeight.toFloat())
            val clampedRight = right.coerceIn(0f, originalWidth.toFloat())
            val clampedBottom = bottom.coerceIn(0f, originalHeight.toFloat())
            
            // Validate box size
            if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) {
                continue  // Invalid box
            }

            results.add(
                DetectedObject(
                    label = labels.getOrElse(classId) { "Unknown" },
                    confidence = confidence,
                    boundingBox = RectF(clampedLeft, clampedTop, clampedRight, clampedBottom)
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
    
    fun cleanup() {
        gpuDelegate?.close()
        interpreter?.close()
        Log.d("ObjectDetector", "Cleaned up")
    }
}