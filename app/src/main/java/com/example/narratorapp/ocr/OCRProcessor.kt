package com.example.narratorapp.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions



class OCRProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun detect(bitmap: Bitmap, onResult: (List<OCRLine>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val results = mutableListOf<OCRLine>()
                visionText.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        results.add(
                            OCRLine(
                                text = line.text,
                                boundingBox = line.boundingBox ?: Rect()
                            )
                        )
                    }
                }
                onResult(results)
            }
            .addOnFailureListener { e ->
                Log.e("OCRProcessor", "Text recognition failed", e)
                onResult(emptyList())
            }
    }
}