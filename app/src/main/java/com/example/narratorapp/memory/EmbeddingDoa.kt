package com.example.narratorapp.memory

import androidx.room.*

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(entity: EmbeddingEntity)

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>
    
    @Query("SELECT * FROM embeddings WHERE type = :type")
    suspend fun getEmbeddingsByType(type: String): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE labelLower = :labelLower AND type = :type")
    suspend fun deleteByLabelAndType(labelLower: String, type: String)
    
    @Query("DELETE FROM embeddings WHERE type = :type")
    suspend fun deleteAllOfType(type: String)

    @Query("DELETE FROM embeddings")
    suspend fun clearAll()
    
    @Query("UPDATE embeddings SET lastSeen = :timestamp, usageCount = usageCount + 1 WHERE labelLower = :labelLower AND type = :type")
    suspend fun updateUsage(labelLower: String, type: String, timestamp: Long)
}