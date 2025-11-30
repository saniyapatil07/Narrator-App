package com.example.narratorapp.navigation

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * DROP-IN REPLACEMENT for existing ARCoreManager
 * Same function names, optimized implementation
 */
class ARCoreManager(private val context: Context) : DefaultLifecycleObserver {

    private var arSession: Session? = null
    private var config: Config? = null
    private var isTracking = false
    private var currentPose: Pose? = null
    private var currentFrame: Frame? = null
    
    private var onPoseUpdateListener: ((Pose) -> Unit)? = null
    
    // OPTIMIZED: Aggressive throttling (same pattern as before)
    private var lastUpdateTime = 0L
    private val updateInterval = 200L  // 5 FPS for ARCore
    
    // NEW: Depth caching for performance
    private val depthCache = mutableMapOf<String, DepthResult>()
    private val cacheLifetime = 500L

    fun initialize(): Boolean {
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    arSession = Session(context)
                    configureSession()
                    Log.i("ARCoreManager", "ARCore initialized (optimized)")
                    true
                }
                else -> {
                    Log.e("ARCoreManager", "ARCore not supported")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("ARCoreManager", "Failed to initialize ARCore", e)
            false
        }
    }
    
    private fun configureSession() {
        arSession?.let { session ->
            config = Config(session).apply {
                // OPTIMIZED: Minimal configuration
                depthMode = Config.DepthMode.DISABLED  // We use hit testing
                instantPlacementMode = Config.InstantPlacementMode.DISABLED
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            session.configure(config)
        }
    }
    
    fun update(): Frame? {
        val now = System.currentTimeMillis()
        
        // OPTIMIZED: Throttling
        if (now - lastUpdateTime < updateInterval) {
            return currentFrame
        }
        
        lastUpdateTime = now
        
        return try {
            arSession?.update()?.also { frame ->
                currentFrame = frame
                val camera = frame.camera
                isTracking = camera.trackingState == TrackingState.TRACKING
                
                if (isTracking) {
                    currentPose = camera.pose
                    onPoseUpdateListener?.invoke(currentPose!!)
                }
                
                cleanDepthCache(now)
            }
        } catch (e: CameraNotAvailableException) {
            Log.e("ARCoreManager", "Camera not available", e)
            null
        }
    }
    
    fun getCameraPose(): Pose? = currentPose
    
    fun getHorizontalPlanes(): List<Plane> {
        return arSession?.getAllTrackables(Plane::class.java)
            ?.filter { 
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && 
                it.trackingState == TrackingState.TRACKING 
            } ?: emptyList()
    }
    
    fun getDistanceToPoint(targetPose: Pose): Float {
        val cameraPose = currentPose ?: return Float.MAX_VALUE
        val dx = targetPose.tx() - cameraPose.tx()
        val dy = targetPose.ty() - cameraPose.ty()
        val dz = targetPose.tz() - cameraPose.tz()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    // EXISTING FUNCTION - kept for compatibility, now optimized
    fun getDistanceFromScreenPoint(x: Float, y: Float): Float? {
        val frame = currentFrame ?: return null
        if (!isTracking) return null
        
        // Check cache first
        val cacheKey = "${x.toInt()}_${y.toInt()}"
        val now = System.currentTimeMillis()
        depthCache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < cacheLifetime) {
                return cached.depth
            }
        }
        
        // Calculate depth
        return try {
            val hits = frame.hitTest(x, y)
            val validHit = hits.firstOrNull { 
                it.trackable is Plane || it.trackable is Point 
            }
            validHit?.distance?.also { depth ->
                depthCache[cacheKey] = DepthResult(depth, now)
            }
        } catch (e: Exception) {
            Log.d("ARCoreManager", "Hit test failed at ($x, $y)")
            null
        }
    }
    
    // NEW OPTIMIZED FUNCTION: Batch depth for multiple objects
    fun getDepthForBoundingBox(boundingBox: RectF): Float? {
        val frame = currentFrame ?: return null
        if (!isTracking) return null
        
        val centerX = boundingBox.centerX()
        val centerY = boundingBox.centerY()
        val cacheKey = "${centerX.toInt()}_${centerY.toInt()}"
        
        val now = System.currentTimeMillis()
        depthCache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < cacheLifetime) {
                return cached.depth
            }
        }
        
        // Sample 5 points for robustness
        val samplePoints = listOf(
            Pair(boundingBox.centerX(), boundingBox.centerY()),
            Pair(boundingBox.left + boundingBox.width() * 0.25f, boundingBox.top + boundingBox.height() * 0.25f),
            Pair(boundingBox.right - boundingBox.width() * 0.25f, boundingBox.top + boundingBox.height() * 0.25f),
            Pair(boundingBox.left + boundingBox.width() * 0.25f, boundingBox.bottom - boundingBox.height() * 0.25f),
            Pair(boundingBox.right - boundingBox.width() * 0.25f, boundingBox.bottom - boundingBox.height() * 0.25f)
        )
        
        val depths = mutableListOf<Float>()
        for ((x, y) in samplePoints) {
            try {
                val hits = frame.hitTest(x, y)
                hits.firstOrNull { it.trackable is Plane || it.trackable is Point }
                    ?.distance?.let { depths.add(it) }
            } catch (e: Exception) {
                // Skip failed points
            }
        }
        
        return if (depths.isNotEmpty()) {
            val medianDepth = depths.sorted()[depths.size / 2]
            depthCache[cacheKey] = DepthResult(medianDepth, now)
            medianDepth
        } else {
            null
        }
    }
    
    // NEW: Batch processing helper
    fun getDepthForMultipleBoxes(boxes: List<Pair<String, RectF>>): Map<String, Float> {
        val results = mutableMapOf<String, Float>()
        for ((label, box) in boxes) {
            getDepthForBoundingBox(box)?.let { depth ->
                results[label] = depth
            }
        }
        return results
    }
    
    private fun cleanDepthCache(currentTime: Long) {
        depthCache.entries.removeIf { entry ->
            currentTime - entry.value.timestamp > cacheLifetime
        }
    }
    
    fun createAnchor(pose: Pose): Anchor? {
        return try {
            arSession?.createAnchor(pose)
        } catch (e: Exception) {
            Log.e("ARCoreManager", "Failed to create anchor", e)
            null
        }
    }
    
    fun setOnPoseUpdateListener(listener: (Pose) -> Unit) {
        onPoseUpdateListener = listener
    }
    
    override fun onPause(owner: LifecycleOwner) {
        arSession?.pause()
        Log.d("ARCoreManager", "Paused")
    }
    
    override fun onResume(owner: LifecycleOwner) {
        try {
            arSession?.resume()
            Log.d("ARCoreManager", "Resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e("ARCoreManager", "Camera not available on resume", e)
        }
    }
    
    fun cleanup() {
        depthCache.clear()
        arSession?.close()
        arSession = null
        Log.d("ARCoreManager", "Cleaned up")
    }
    
    private data class DepthResult(
        val depth: Float,
        val timestamp: Long
    )
}