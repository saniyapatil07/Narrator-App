package com.example.narratorapp.model

import android.graphics.RectF

sealed class SceneEvent {
    data class ObjectEvent(val label: String, val confidence: Float, val box: RectF) : SceneEvent()
    data class TextEvent(val text: String, val box: android.graphics.Rect) : SceneEvent()
}
