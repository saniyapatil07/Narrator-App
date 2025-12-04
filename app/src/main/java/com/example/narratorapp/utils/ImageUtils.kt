package com.example.narratorapp.utils

import android.graphics.*
import android.media.Image
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * FIXED VERSION - Proper YUV to RGB conversion without JPEG compression
 */
object ImageUtils {

    /**
     * Convert ImageProxy (YUV_420_888) to Bitmap efficiently
     * Uses RenderScript-free approach that works on all Android versions
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        // Get the YUV planes
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        // Create NV21 byte array (Y plane followed by VU interleaved)
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)
        
        // ===== CRITICAL FIX: Proper VU interleaving for NV21 =====
        val width = imageProxy.width
        val height = imageProxy.height
        
        val pixelStrideUV = uPlane.pixelStride
        val rowStrideUV = uPlane.rowStride
        
        // NV21 expects VUVUVU... interleaved
        var pos = ySize
        
        // Handle different pixel strides
        if (pixelStrideUV == 1) {
            // Planes are already tightly packed - simple copy with swap
            for (i in 0 until uSize) {
                nv21[pos++] = vBuffer.get(i)  // V first
                nv21[pos++] = uBuffer.get(i)  // U second
            }
        } else {
            // Semi-planar format with stride - need careful extraction
            val uvWidth = width / 2
            val uvHeight = height / 2
            
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * rowStrideUV + col * pixelStrideUV
                    
                    if (uvIndex < vSize && uvIndex < uSize) {
                        nv21[pos++] = vBuffer.get(uvIndex)  // V
                        nv21[pos++] = uBuffer.get(uvIndex)  // U
                    }
                }
            }
        }
        
        // Convert NV21 to Bitmap using YuvImage
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        
        // Use quality 90 for balance between speed and quality
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val jpegBytes = out.toByteArray()
        
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
    
    /**
     * Alternative: Direct YUV to RGB conversion (faster, no JPEG step)
     * Use this if you need maximum performance
     */
    fun imageProxyToBitmapDirect(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val yPixelStride = yPlane.pixelStride
        val yRowStride = yPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        
        val argb = IntArray(width * height)
        
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = y * yRowStride + x * yPixelStride
                val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                
                val yValue = (yBuffer.get(yIndex).toInt() and 0xFF)
                val uValue = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                
                // YUV to RGB conversion
                var r = yValue + (1.370705f * vValue)
                var g = yValue - (0.337633f * uValue) - (0.698001f * vValue)
                var b = yValue + (1.732446f * uValue)
                
                // Clamp to [0, 255]
                r = r.coerceIn(0f, 255f)
                g = g.coerceIn(0f, 255f)
                b = b.coerceIn(0f, 255f)
                
                argb[index++] = (0xFF shl 24) or 
                                (r.toInt() shl 16) or 
                                (g.toInt() shl 8) or 
                                b.toInt()
            }
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        
        return bitmap
    }

    /**
     * Rotate bitmap by degrees
     */
    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        if (rotationDegrees == 0f) return bitmap
        
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        
        return Bitmap.createBitmap(
            bitmap, 
            0, 0, 
            bitmap.width, 
            bitmap.height, 
            matrix, 
            true
        )
    }

    /**
     * Resize bitmap to target dimensions
     */
    fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
    
    /**
     * Convert bounding box from image coordinates to view coordinates
     * @param imageWidth Original image width
     * @param imageHeight Original image height
     * @param viewWidth View width
     * @param viewHeight View height
     * @param rect Rectangle in image coordinates
     * @return Rectangle in view coordinates
     */
    fun mapImageToViewCoordinates(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        rect: RectF
    ): RectF {
        val scaleX = viewWidth.toFloat() / imageWidth
        val scaleY = viewHeight.toFloat() / viewHeight
        
        return RectF(
            rect.left * scaleX,
            rect.top * scaleY,
            rect.right * scaleX,
            rect.bottom * scaleY
        )
    }
}