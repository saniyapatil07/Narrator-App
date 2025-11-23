package com.example.narratorapp.detection

import android.graphics.Rect
import android.graphics.RectF

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
) {
    fun confidencePercent(): String = "${(confidence * 100).toInt()}%"

    fun toRect(): Rect = Rect(
        boundingBox.left.toInt(),
        boundingBox.top.toInt(),
        boundingBox.right.toInt(),
        boundingBox.bottom.toInt()
    )
}