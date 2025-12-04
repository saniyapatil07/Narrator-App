package com.example.narratorapp.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.narratorapp.memory.MemoryManager
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.NavigationEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXManager(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val previewView: PreviewView,
    private val overlayView: OverlayView,
    private val navigationEngine: NavigationEngine? = null,
    private val memoryManager: MemoryManager? = null
) {
    private var analyzer: CombinedAnalyzer? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // CRITICAL FIX: Use 1280x720 for better detection
                val targetResolution = Size(1280, 720)
                
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(targetResolution)
                    .setTargetRotation(previewView.display.rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analyzer = CombinedAnalyzer(
                    context = context,
                    ttsManager = ttsManager,
                    overlayView = overlayView,
                    navigationEngine = navigationEngine,
                    memoryManager = memoryManager
                )

                analysis.setAnalyzer(cameraExecutor, analyzer!!)

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        context as LifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
                    
                    // ===== CRITICAL FIX: Update overlay with actual image dimensions =====
                    // Wait for first frame to get actual dimensions
                    previewView.post {
                        val rotation = previewView.display.rotation
                        overlayView.updateSourceSize(
                            targetResolution.width,
                            targetResolution.height,
                            rotation
                        )
                        Log.i("CameraXManager", "✓ Overlay dimensions set: ${targetResolution.width}x${targetResolution.height}, rotation=$rotation")
                    }
                    
                    Log.d("CameraXManager", "Camera started on background thread")
                } catch (e: Exception) {
                    Log.e("CameraXManager", "Use case binding failed", e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }
    
    fun getAnalyzer(): CombinedAnalyzer? = analyzer
    
    fun shutdown() {
        analyzer?.cleanup()
        cameraExecutor.shutdown()
        Log.d("CameraXManager", "Camera and executor shut down")
    }
}