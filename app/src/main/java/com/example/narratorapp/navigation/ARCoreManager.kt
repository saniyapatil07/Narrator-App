package com.example.narratorapp.navigation

import android.content.Context
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class ARCoreManager(private val context: Context) : DefaultLifecycleObserver {

    private var arSession: Session? = null
    private var config: Config? = null
    private var isTracking = false
    private var currentPose: Pose? = null
    
    private var onPoseUpdateListener: ((Pose) -> Unit)? = null

    fun initialize(): Boolean {
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    arSession = Session(context)
                    configureSession()
                    Log.i("ARCoreManager", "ARCore initialized successfully")
                    true
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
                depthMode = Config.DepthMode.AUTOMATIC
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            session.configure(config)
        }
    }
    
    fun update(): Frame? {
        return try {
            arSession?.update()?.also { frame ->
                val camera = frame.camera
                isTracking = camera.trackingState == TrackingState.TRACKING
                
                if (isTracking) {
                    currentPose = camera.pose
                    onPoseUpdateListener?.invoke(currentPose!!)
                }
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
    
    //distance to specific 2d point on screen
    fun getDistanceFromScreenPoint(x: Float, y: Float): Float? {
        val frame = arSession?.update()?: return null

        val hitResults = frame.hitTest(x, y)

        val closestHit = hitResults.firstOrNull { 
        it.trackable is Plane || it.trackable is Point 
    }
    
    return closestHit?.distance
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
