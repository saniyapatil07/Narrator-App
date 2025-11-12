package com.example.narratorapp.utils

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import android.graphics.ImageFormat
import androidx.camera.core.Camera


object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped for NV21 format
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val yuvByteArray = out.toByteArray()
        return BitmapFactory.decodeByteArray(yuvByteArray, 0, yuvByteArray.size)
    }

    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    fun bitmapToByteBuffer(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        val scaledBitmap = resizeBitmap(bitmap, inputSize, inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixelValue in intValues) {
            buffer.putFloat((pixelValue shr 16 and 0xFF) / 255.0f) // R
            buffer.putFloat((pixelValue shr 8 and 0xFF) / 255.0f)  // G
            buffer.putFloat((pixelValue and 0xFF) / 255.0f)        // B
        }
        buffer.rewind()
        return buffer
    }
}
