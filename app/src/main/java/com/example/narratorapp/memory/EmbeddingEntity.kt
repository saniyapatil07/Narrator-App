package com.example.narratorapp.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "embeddings")
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val type: String,
    val embedding: String
)