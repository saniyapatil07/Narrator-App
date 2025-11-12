package com.example.narrator.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "embeddings")
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String, // e.g., "Rahul", "Office Entrance"
    val type: String,  // "face" or "place"
    val embedding: String // stored as comma-separated string
)
