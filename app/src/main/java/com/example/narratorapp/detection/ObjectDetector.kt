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
    
    // Pre-allocated buffers for zero-copy inference
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: Array<Array<FloatArray>>

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            
            // CRITICAL: Try GPU first, fallback to CPU with NNAPI
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                
                // Try GPU delegation
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    try {
                        val delegateOptions = compatList.bestOptionsForThisDevice
                        gpuDelegate = GpuDelegate(delegateOptions)
                        addDelegate(gpuDelegate)
                        Log.i("OptimizedDetector", "✓ GPU delegate enabled")
                    } catch (e: Exception) {
                        Log.w("OptimizedDetector", "GPU delegate failed, using NNAPI", e)
                        setUseNNAPI(true)  // Fallback to NNAPI
                    }
                } else {
                    Log.i("OptimizedDetector", "GPU not available, using NNAPI")
                    setUseNNAPI(true)
                }
                
                // Allow FP16 quantization for faster inference
                setAllowFp16PrecisionForFp32(true)
            }
            
            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            // Pre-allocate buffers
            val inputBufferSize = inputSize * inputSize * 3 * 4  // FLOAT32
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize).apply {
                order(ByteOrder.nativeOrder())
            }
            
            val outputShape = outputTensor.shape()
            outputBuffer = Array(outputShape[0]) {
                Array(outputShape[1]) { FloatArray(outputShape[2]) }
            }
            
            Log.i("OptimizedDetector", "=== OPTIMIZED MODEL INFO ===")
            Log.i("OptimizedDetector", "Input: ${inputTensor.shape().contentToString()}")
            Log.i("OptimizedDetector", "Output: ${outputShape.contentToString()}")
            Log.i("OptimizedDetector", "GPU: ${gpuDelegate != null}, NNAPI: ${options.useNNAPI}")
            Log.i("OptimizedDetector", "============================")
            
        } catch (e: Exception) {
            Log.e("OptimizedDetector", "Error loading model", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        if (interpreter == null) return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        
        // Reuse pre-allocated buffer
        inputBuffer.rewind()
        bitmapToByteBuffer(resizedBitmap, inputBuffer)

        val startTime = SystemClock.uptimeMillis()
        interpreter!!.run(inputBuffer, outputBuffer)
        val inferenceTime = SystemClock.uptimeMillis() - startTime
        
        // Only log periodically to reduce overhead
        if (Math.random() < 0.1) {  // 10% of frames
            Log.i("OptimizedDetector", "Inference: ${inferenceTime}ms")
        }

        val detections = decodeYOLO(outputBuffer[0], bitmap.width, bitmap.height)
        val finalDetections = nonMaxSuppression(detections)
        
        return finalDetections
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap, buffer: ByteBuffer) {
        buffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // ImageNet normalization
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

            buffer.putFloat((b - MEAN_B) / STD_B)
            buffer.putFloat((g - MEAN_G) / STD_G)
            buffer.putFloat((r - MEAN_R) / STD_R)
        }
    }

    private fun decodeYOLO(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): MutableList<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        
        for (prediction in output) {
            val objectness = prediction[4]
            
            if (objectness < confidenceThreshold) continue
            
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
    
    fun cleanup() {
        gpuDelegate?.close()
        interpreter?.close()
        Log.d("OptimizedDetector", "Cleaned up")
    }
}