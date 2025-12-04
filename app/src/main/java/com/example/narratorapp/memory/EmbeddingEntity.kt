package com.example.narratorapp.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "embeddings",
    primaryKeys = ["labelLower", "type"]
)
data class EmbeddingEntity(
    val label: String,                  // Display name (e.g., "Rohit")
    val labelLower: String,             // Lowercase for matching (e.g., "rohit")
    val type: String,                   // "face" or "place"
    val embedding: FloatArray,          // ✅ Direct storage, not String
    val lastSeen: Long = System.currentTimeMillis(),
    val usageCount: Int = 0
) {
    // Required for FloatArray comparison
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingEntity
        
        if (label != other.label) return false
        if (labelLower != other.labelLower) return false
        if (type != other.type) return false
        if (!embedding.contentEquals(other.embedding)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + labelLower.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}