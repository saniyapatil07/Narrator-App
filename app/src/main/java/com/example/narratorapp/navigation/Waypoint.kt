package com.example.narratorapp.navigation

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.ar.core.Pose
import kotlin.math.sqrt


/**
 * Represents a navigation waypoint with spatial position
 */
@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    
    // Spatial coordinates (ARCore pose components)
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float, // Quaternion x for orientation
    val qy: Float, // Quaternion y
    val qz: Float, // Quaternion z
    val qw: Float, // Quaternion w
    
    // Metadata
    val label: String, // "Turn left", "Entrance", "Stairs ahead"
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val routeId: String? = null, // Group waypoints into routes
    
    // Navigation properties
    val isDestination: Boolean = false,
    val obstacleDetected: Boolean = false
) {
    /**
     * Convert to ARCore Pose
     */
    fun toPose(): Pose {
        return Pose(
            floatArrayOf(x, y, z),
            floatArrayOf(qx, qy, qz, qw)
        )
    }
    
    /**
     * Calculate straight-line distance to another waypoint
     */
    fun distanceTo(other: Waypoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Calculate 2D distance (ignore height)
     */
    fun distanceTo2D(other: Waypoint): Float {
        val dx = x - other.x
        val dz = z - other.z
        return sqrt(dx * dx + dz * dz)
    }
    
    companion object {
        /**
         * Create waypoint from ARCore Pose
         */
        fun fromPose(pose: Pose, label: String, description: String = ""): Waypoint {
            val translation = pose.translation
            val rotation = pose.rotationQuaternion
            return Waypoint(
                x = translation[0],
                y = translation[1],
                z = translation[2],
                qx = rotation[0],
                qy = rotation[1],
                qz = rotation[2],
                qw = rotation[3],
                label = label,
                description = description
            )
        }
    }
}

/**
 * A complete route with multiple waypoints
 */
data class NavigationRoute(
    val id: String,
    val name: String,
    val waypoints: List<Waypoint>,
    val startLocation: String = "",
    val endLocation: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Total route distance
     */
    fun totalDistance(): Float {
        return waypoints.zipWithNext().sumOf { (a, b) ->
            a.distanceTo(b).toDouble()
        }.toFloat()
    }
    
    /**
     * Get next waypoint based on current position
     */
    fun getNextWaypoint(currentPose: Pose): Waypoint? {
        val currentX = currentPose.tx()
        val currentZ = currentPose.tz()
        
        return waypoints
            .filter { !it.isDestination }
            .minByOrNull { waypoint ->
                val dx = waypoint.x - currentX
                val dz = waypoint.z - currentZ
                sqrt(dx * dx + dz * dz)
            }
    }
}

/**
 * Direction instruction for navigation
 */
enum class Direction {
    FORWARD,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    ARRIVED;
    
    fun toSpeechInstruction(distance: Float = 0f): String {
        return when (this) {
            FORWARD -> "Continue straight ahead"
            TURN_LEFT -> "Turn left in ${distance.toInt()} meters"
            TURN_RIGHT -> "Turn right in ${distance.toInt()} meters"
            SLIGHT_LEFT -> "Bear slightly left"
            SLIGHT_RIGHT -> "Bear slightly right"
            SHARP_LEFT -> "Make a sharp left turn"
            SHARP_RIGHT -> "Make a sharp right turn"
            ARRIVED -> "You have arrived at your destination"
        }
    }
}