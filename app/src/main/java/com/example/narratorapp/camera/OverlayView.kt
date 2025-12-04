package com.example.narratorapp.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

/**
 * FIXED VERSION - Proper coordinate mapping from image space to view space
 */
class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    // ===== NEW: Source image dimensions =====
    private var imageWidth = 1
    private var imageHeight = 1
    private var rotationDegrees: Int = 0

    private val boxPaint = Paint().apply {
        color = Color.rgb(0, 255, 0)  // Bright green for better contrast
        style = Paint.Style.STROKE
        strokeWidth = 6f  // Thicker for visibility
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f  // Larger text
        style = Paint.Style.FILL
        isAntiAlias = true
        isFakeBoldText = true  // Bold for readability
    }
    
    // NEW: Background for text
    private val bgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)  // Semi-transparent black
        style = Paint.Style.FILL
    }

    var objects: List<DetectedObject> = listOf()
    var texts: List<OCRLine> = listOf()

    // ===== NEW: Update source dimensions from camera =====
    fun updateSourceSize(w: Int, h: Int, rotation: Int = 0) {
        imageWidth = w
        imageHeight = h
        rotationDegrees = rotation
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0 || imageWidth <= 1 || imageHeight <= 1) {
            return  // Not ready yet
        }

        // ===== CRITICAL FIX: Scale from image coordinates to view coordinates =====
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        // Draw detected objects
        objects.forEach { obj ->
            // Map bounding box from image space to view space
            val mapped = RectF(
                obj.boundingBox.left * scaleX,
                obj.boundingBox.top * scaleY,
                obj.boundingBox.right * scaleX,
                obj.boundingBox.bottom * scaleY
            )
            
            // Draw bounding box
            canvas.drawRect(mapped, boxPaint)
            
            // Draw label with background
            val label = "${obj.label} ${obj.confidencePercent()}"
            val textWidth = textPaint.measureText(label)
            val textHeight = 60f
            
            // Background rectangle for text
            canvas.drawRect(
                mapped.left,
                mapped.top - textHeight,
                mapped.left + textWidth + 20f,
                mapped.top,
                bgPaint
            )
            
            // Text
            canvas.drawText(
                label,
                mapped.left + 10f,
                mapped.top - 15f,
                textPaint
            )
        }

        // Draw OCR text
        texts.forEach { ocr ->
            // Map text bounding box
            val mapped = Rect(
                (ocr.boundingBox.left * scaleX).toInt(),
                (ocr.boundingBox.top * scaleY).toInt(),
                (ocr.boundingBox.right * scaleX).toInt(),
                (ocr.boundingBox.bottom * scaleY).toInt()
            )
            
            // Draw box around text
            canvas.drawRect(mapped, boxPaint)
            
            // Draw text with background
            val textWidth = textPaint.measureText(ocr.text)
            canvas.drawRect(
                mapped.left.toFloat(),
                mapped.top.toFloat() - 60f,
                mapped.left.toFloat() + textWidth + 20f,
                mapped.top.toFloat(),
                bgPaint
            )
            
            canvas.drawText(
                ocr.text,
                mapped.left.toFloat() + 10f,
                mapped.top.toFloat() - 15f,
                textPaint
            )
        }
    }
}