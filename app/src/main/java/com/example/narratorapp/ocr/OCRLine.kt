package com.example.narratorapp.ocr

import android.graphics.Rect

data class OCRLine(
    val text: String,
    val boundingBox: Rect
)
