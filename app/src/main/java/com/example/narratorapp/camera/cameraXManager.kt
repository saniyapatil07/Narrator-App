package com.example.narratorapp.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.narratorapp.narration.TTSManager
import android.util.Size
import androidx.lifecycle.Lifecycle
import com.example.narratorapp.navigation.NavigationEngine
import com.example.narratorapp.memory.MemoryManager

class CameraXManager(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val previewView: PreviewView,
    private val overlayView: OverlayView,
    private val navigationEngine: NavigationEngine? = null,
    private val memoryManager: MemoryManager? = null // ADD THIS
) {
    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(previewView.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            // Pass memory manager to analyzer
            analysis.setAnalyzer(
                ContextCompat.getMainExecutor(context),
                CombinedAnalyzer(
                    context, 
                    ttsManager, 
                    overlayView,
                    navigationEngine,
                    memoryManager // PASS IT HERE
                )
            )
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    context as LifecycleOwner,
                    cameraSelector,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e("CameraXManager", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}