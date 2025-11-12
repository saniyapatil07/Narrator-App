package com.example.narratorapp.navigation

import android.content.Context
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.media.Image
import com.google.ar.core.TrackingState
import com.google.ar.core.Pose

/**
 * Manages ARCore session for spatial tracking and depth sensing
 * Provides camera pose, depth data, and plane detection
 */
class ARCoreManager(private val context: Context) : DefaultLifecycleObserver {

    private var arSession: Session? = null
    private var config: Config? = null
    
    // Tracking state
    private var isTracking = false
    private var currentPose: Pose? = null
    
    // Callbacks
    private var onPoseUpdateListener: ((Pose) -> Unit)? = null
    private var onDepthAvailableListener: ((Image) -> Unit)? = null
    
    /**
     * Initialize ARCore session with depth and instant placement enabled
     */
    fun initialize(): Boolean {
        return try {
            // Check ARCore availability
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    arSession = Session(context)
                    configureSession()
                    Log.i("ARCoreManager", "ARCore initialized successfully")
                    true
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    Log.w("ARCoreManager", "ARCore needs to be installed/updated")
                    false
                }
                else -> {
                    Log.e("ARCoreManager", "ARCore not supported on this device")
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
                // Enable depth mode for obstacle detection
                depthMode = Config.DepthMode.AUTOMATIC
                
                // Enable instant placement for quick waypoint marking
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                
                // Focus mode for outdoor navigation
                focusMode = Config.FocusMode.AUTO
                
                // Enable plane detection
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            session.configure(config)
        }
    }
    
    /**
     * Update ARCore frame - call this in your camera analyzer
     */
    fun update(): Frame? {
        return try {
            arSession?.update()?.also { frame ->
                // Update tracking state
                val camera = frame.camera
                isTracking = camera.trackingState == TrackingState.TRACKING
                
                if (isTracking) {
                    currentPose = camera.pose
                    onPoseUpdateListener?.invoke(currentPose!!)
                    
                    // Check for depth data
                    frame.acquireDepthImage16Bits()?.let { depthImage ->
                        onDepthAvailableListener?.invoke(depthImage)
                        depthImage.close()
                    }
                }
            }
        } catch (e: CameraNotAvailableException) {
            Log.e("ARCoreManager", "Camera not available", e)
            null
        }
    }
    
    /**
     * Get current camera position and orientation
     */
    fun getCameraPose(): Pose? = currentPose
    
    /**
     * Get horizontal planes (floor detection)
     */
    fun getHorizontalPlanes(): List<Plane> {
        return arSession?.getAllTrackables(Plane::class.java)
            ?.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
            ?: emptyList()
    }
    
    /**
     * Calculate distance to a point in 3D space
     */
    fun getDistanceToPoint(targetPose: Pose): Float {
        val cameraPose = currentPose ?: return Float.MAX_VALUE
        val dx = targetPose.tx() - cameraPose.tx()
        val dy = targetPose.ty() - cameraPose.ty()
        val dz = targetPose.tz() - cameraPose.tz()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Hit test - find real-world position from screen coordinates
     */
    fun hitTest(x: Float, y: Float): HitResult? {
        val frame = arSession?.update() ?: return null
        val hits = frame.hitTest(x, y)
        return hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
        }
    }
    
    /**
     * Create anchor at specific pose for waypoint marking
     */
    fun createAnchor(pose: Pose): Anchor? {
        return try {
            arSession?.createAnchor(pose)
        } catch (e: Exception) {
            Log.e("ARCoreManager", "Failed to create anchor", e)
            null
        }
    }
    
    // Listener setters
    fun setOnPoseUpdateListener(listener: (Pose) -> Unit) {
        onPoseUpdateListener = listener
    }
    
    fun setOnDepthAvailableListener(listener: (Image) -> Unit) {
        onDepthAvailableListener = listener
    }
    
    // Lifecycle
    override fun onPause(owner: LifecycleOwner) {
        arSession?.pause()
    }
    
    override fun onResume(owner: LifecycleOwner) {
        try {
            arSession?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e("ARCoreManager", "Camera not available on resume", e)
        }
    }
    
    fun cleanup() {
        arSession?.close()
        arSession = null
    }
}