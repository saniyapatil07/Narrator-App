package com.example.narratorapp.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

class PlaceRecognizer(context: Context) {

    private var interpreter: Interpreter
    private val inputSize = 224

    init {
        val model = FileUtil.loadMappedFile(context, "place_embedding.tflite")
        interpreter = Interpreter(model)
        Log.d("PlaceRecognizer", "Loaded place embedding model")
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)
        val output = Array(1) { FloatArray(256) }
        interpreter.run(input, output)
        return output[0]
    }

    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = (pixel shr 16 and 0xFF) / 255.0f
                input[0][y][x][1] = (pixel shr 8 and 0xFF) / 255.0f
                input[0][y][x][2] = (pixel and 0xFF) / 255.0f
            }
        }
        return input
    }
}