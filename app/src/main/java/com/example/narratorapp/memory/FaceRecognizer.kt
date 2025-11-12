package com.example.narrator.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import kotlin.math.sqrt

class FaceRecognizer(context: Context) {

    private var interpreter: Interpreter
    private val inputSize = 112 // depends on your face embedding model

    init {
        val model = FileUtil.loadMappedFile(context, "face_embedding.tflite")
        interpreter = Interpreter(model)
        Log.d("FaceRecognizer", "Loaded face embedding model")
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)
        val output = Array(1) { FloatArray(128) } // typical embedding size

        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16 and 0xFF) - 127.5f) / 128f
                input[0][y][x][1] = ((pixel shr 8 and 0xFF) - 127.5f) / 128f
                input[0][y][x][2] = ((pixel and 0xFF) - 127.5f) / 128f
            }
        }
        return input
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        val norm = sqrt(embedding.sumOf { it * it }.toDouble()).toFloat()
        return embedding.map { it / norm }.toFloatArray()
    }

    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        val dot = vec1.zip(vec2).sumOf { it.first * it.second }
        val mag1 = sqrt(vec1.sumOf { it * it }.toDouble()).toFloat()
        val mag2 = sqrt(vec2.sumOf { it * it }.toDouble()).toFloat()
        return dot / (mag1 * mag2)
    }
}
