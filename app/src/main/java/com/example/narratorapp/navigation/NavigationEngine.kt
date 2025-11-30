package com.example.narratorapp.navigation

import android.content.Context
import android.util.Log
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.narration.TTSManager
import com.google.ar.core.Pose
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * DROP-IN REPLACEMENT for existing NavigationEngine
 * Same API, optimized to use selective depth from ARCoreManager
 */
class NavigationEngine(
    private val context: Context,
    private val ttsManager: TTSManager,
    val arCoreManager: ARCoreManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var currentRoute: NavigationRoute? = null
    private var currentWaypointIndex = 0
    private var isNavigating = false
    
    private var obstacleWarningDistance = 2.0f
    private var lastObstacleWarning = 0L
    private val obstacleWarningCooldown = 3000L
    
    private var lastAnnouncementTime = 0L
    private val announcementInterval = 5000L

    fun startNavigation(route: NavigationRoute) {
        currentRoute = route
        currentWaypointIndex = 0
        isNavigating = true
        
        ttsManager.speak("Navigation started to ${route.endLocation}")
        
        scope.launch {
            monitorNavigation()
        }
    }
    
    private suspend fun monitorNavigation() {
        while (isNavigating) {
            val currentPose = arCoreManager.getCameraPose()
            val currentWaypoint = getCurrentWaypoint()
            
            if (currentPose != null && currentWaypoint != null) {
                val distance = calculateDistance(currentPose, currentWaypoint.toPose())
                val direction = calculateDirection(currentPose, currentWaypoint.toPose())
                
                if (distance < 1.5f) {
                    onWaypointReached()
                }
                
                val now = System.currentTimeMillis()
                if (now - lastAnnouncementTime > announcementInterval) {
                    announceProgress(direction, distance)
                    lastAnnouncementTime = now
                }
            }
            
            delay(500)
        }
    }
    
    // EXISTING FUNCTION - now optimized with selective depth
    fun processObstacles(objects: List<DetectedObject>) {
        if (!isNavigating) return
        
        val now = System.currentTimeMillis()
        if (now - lastObstacleWarning < obstacleWarningCooldown) return
        
        val dangerousObjects = objects.filter { obj ->
            isDangerousObstacle(obj.label) && obj.confidence > 0.5f
        }
        
        if (dangerousObjects.isEmpty()) return
        
        // OPTIMIZED: Use selective depth calculation
        var closestObstacle: DetectedObject? = null
        var closestDepth = Float.MAX_VALUE
        
        for (obstacle in dangerousObjects) {
            // Use optimized ARCoreManager depth function
            val depth = arCoreManager.getDepthForBoundingBox(obstacle.boundingBox)
            
            if (depth != null && depth < closestDepth && depth < obstacleWarningDistance) {
                closestObstacle = obstacle
                closestDepth = depth
            }
        }
        
        if (closestObstacle != null) {
            announceObstacle(closestObstacle, closestDepth)
            lastObstacleWarning = now
        }
    }
    
    private fun isDangerousObstacle(label: String): Boolean {
        val obstacles = listOf(
            "person", "bicycle", "car", "motorcycle", "truck", "bus",
            "stop sign", "traffic light", "fire hydrant", "bench", "chair"
        )
        return obstacles.contains(label.lowercase())
    }
    
    // OPTIMIZED: Now uses real metric depth
    private fun announceObstacle(obj: DetectedObject, depthMeters: Float) {
        val formattedDistance = String.format("%.1f", depthMeters)
        val urgency = when {
            depthMeters < 0.5f -> "Caution! "
            depthMeters < 1.0f -> "Warning: "
            else -> ""
        }
        
        ttsManager.speak("${urgency}${obj.label} detected $formattedDistance meters away")
        Log.i("NavigationEngine", "Obstacle: ${obj.label} at ${formattedDistance}m")
    }
    
    private fun calculateDirection(currentPose: Pose, targetPose: Pose): Direction {
        val forward = floatArrayOf(0f, 0f, -1f)
        val rotatedForward = FloatArray(3)
        currentPose.rotateVector(forward, 0, rotatedForward, 0)
        
        val toTarget = floatArrayOf(
            targetPose.tx() - currentPose.tx(),
            0f,
            targetPose.tz() - currentPose.tz()
        )
        
        val toTargetMag = sqrt(toTarget[0] * toTarget[0] + toTarget[2] * toTarget[2])
        if (toTargetMag < 0.01f) return Direction.ARRIVED
        
        toTarget[0] /= toTargetMag
        toTarget[2] /= toTargetMag
        
        val dot = rotatedForward[0] * toTarget[0] + rotatedForward[2] * toTarget[2]
        val cross = rotatedForward[0] * toTarget[2] - rotatedForward[2] * toTarget[0]
        
        val angle = atan2(cross, dot) * (180f / PI.toFloat())
        
        return when {
            abs(angle) < 10 -> Direction.FORWARD
            angle > 10 && angle < 45 -> Direction.SLIGHT_RIGHT
            angle > 45 && angle < 120 -> Direction.TURN_RIGHT
            angle > 120 -> Direction.SHARP_RIGHT
            angle < -10 && angle > -45 -> Direction.SLIGHT_LEFT
            angle < -45 && angle > -120 -> Direction.TURN_LEFT
            angle < -120 -> Direction.SHARP_LEFT
            else -> Direction.FORWARD
        }
    }
    
    private fun calculateDistance(pose1: Pose, pose2: Pose): Float {
        val dx = pose1.tx() - pose2.tx()
        val dy = pose1.ty() - pose2.ty()
        val dz = pose1.tz() - pose2.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    private fun announceProgress(direction: Direction, distance: Float) {
        val instruction = when {
            distance < 2f -> "Almost there"
            distance < 10f -> "${direction.toSpeechInstruction(distance)}"
            else -> "Continue ${direction.name.lowercase()}"
        }
        ttsManager.speak(instruction)
    }
    
    private fun onWaypointReached() {
        val waypoint = getCurrentWaypoint() ?: return
        ttsManager.speak(waypoint.label)
        currentWaypointIndex++
        
        if (currentWaypointIndex >= (currentRoute?.waypoints?.size ?: 0)) {
            stopNavigation()
            ttsManager.speak("You have arrived at your destination")
        }
    }
    
    private fun getCurrentWaypoint(): Waypoint? {
        return currentRoute?.waypoints?.getOrNull(currentWaypointIndex)
    }
    
    fun recordWaypoint(label: String, description: String = ""): Waypoint? {
        val pose = arCoreManager.getCameraPose() ?: return null
        return Waypoint.fromPose(pose, label, description)
    }
    
    fun stopNavigation() {
        isNavigating = false
        currentRoute = null
        currentWaypointIndex = 0
        ttsManager.speak("Navigation stopped")
    }
    
    fun isNavigating() = isNavigating
    
    fun cleanup() {
        scope.cancel()
    }
}