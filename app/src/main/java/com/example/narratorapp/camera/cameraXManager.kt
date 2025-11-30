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
    
    // CRITICAL: Dedicated background thread for camera analysis
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

                val analysis = ImageAnalysis.Builder()
                    // REDUCED resolution for better performance
                    .setTargetResolution(Size(640, 480))
                    .setTargetRotation(previewView.display.rotation)
                    // CRITICAL: Keep latest frame, drop old ones
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Lower output format for speed
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analyzer = CombinedAnalyzer(
                    context = context,
                    ttsManager = ttsManager,
                    overlayView = overlayView,
                    navigationEngine = navigationEngine,
                    memoryManager = memoryManager
                )

                // CRITICAL: Run analyzer on dedicated background thread
                analysis.setAnalyzer(cameraExecutor, analyzer!!)

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        context as LifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
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