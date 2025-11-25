package com.example.narratorapp.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import kotlin.math.sqrt

class FaceRecognizer(context: Context) {

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    // WARNING: If you used the Google Colab script I gave you, these values MUST be 160 and 512.
    // If you downloaded a different MobileFaceNet model, change them back to 112 and 128.
    private val inputSize = 160
    private val outputSize = 128

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "face_embedding.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(false) // Disable NNAPI as fallback
            }
            interpreter = Interpreter(model, options)
            isInitialized = true
            Log.d("FaceRecognizer", "Loaded face embedding model successfully")
        } catch (e: IllegalArgumentException) {
            Log.e("FaceRecognizer", "Model version incompatibility: ${e.message}")
            Log.e("FaceRecognizer", "Please update TensorFlow Lite to version 2.16.1 or later")
            isInitialized = false
        } catch (e: Exception) {
            Log.e("FaceRecognizer", "Error loading face embedding model", e)
            isInitialized = false
        }
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray {
        if (!isInitialized || interpreter == null) {
            Log.e("FaceRecognizer", "Model not initialized, returning zero embedding")
            return FloatArray(outputSize) { 0f }
        }

        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val input = preprocess(resized)
            val output = Array(1) { FloatArray(outputSize) }

            interpreter!!.run(input, output)
            l2Normalize(output[0])
        } catch (e: Exception) {
            Log.e("FaceRecognizer", "Error during inference", e)
            FloatArray(outputSize) { 0f }
        }
    }

    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bitmap.getPixel(x, y)
                // Normalize to [-1, 1]
                input[0][y][x][0] = ((pixel shr 16 and 0xFF) - 127.5f) / 128f
                input[0][y][x][1] = ((pixel shr 8 and 0xFF) - 127.5f) / 128f
                input[0][y][x][2] = ((pixel and 0xFF) - 127.5f) / 128f
            }
        }
        return input
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sumSq = 0.0
        for (value in embedding) {
            sumSq += value * value
        }
        val norm = sqrt(sumSq).toFloat()
        
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] = embedding[i] / norm
            }
        }
        return embedding
    }

    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        var dot = 0.0
        var mag1 = 0.0
        var mag2 = 0.0

        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
            mag1 += vec1[i] * vec1[i]
            mag2 += vec2[i] * vec2[i]
        }

        return (dot / (sqrt(mag1) * sqrt(mag2))).toFloat()
    }
    
    fun isModelAvailable(): Boolean = isInitialized
}