package com.example.narratorapp.detection

import android.graphics.Rect
import android.graphics.RectF

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
) {
    /** Returns confidence as percentage string (e.g., 87%) */
    fun confidencePercent(): String = "${(confidence * 100).toInt()}%"

    /** Convert boundingBox (RectF) to integer Rect for Canvas ops if needed */
    fun toRect(): Rect = Rect(
        boundingBox.left.toInt(),
        boundingBox.top.toInt(),
        boundingBox.right.toInt(),
        boundingBox.bottom.toInt()
    )
}
