package com.example.narratorapp.memory

import androidx.room.*

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(entity: EmbeddingEntity)

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE label = :label")
    suspend fun deleteByLabel(label: String)

    @Query("DELETE FROM embeddings")
    suspend fun clearAll()
}