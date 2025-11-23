package com.example.narratorapp.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.ocr.OCRLine

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 40f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var objects: List<DetectedObject> = listOf()
    var texts: List<OCRLine> = listOf()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        objects.forEach { obj ->
            val rect = obj.toRect()
            canvas.drawRect(rect, boxPaint)
            canvas.drawText(
                "${obj.label} ${obj.confidencePercent()}",
                rect.left.toFloat(),
                rect.top.toFloat() - 10,
                textPaint
            )
        }

        texts.forEach { ocr ->
            canvas.drawRect(ocr.boundingBox, boxPaint)
            canvas.drawText(
                ocr.text,
                ocr.boundingBox.left.toFloat(),
                ocr.boundingBox.top.toFloat() - 10,
                textPaint
            )
        }
    }
}