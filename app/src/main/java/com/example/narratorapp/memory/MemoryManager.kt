package com.example.narratorapp.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import com.example.narratorapp.memory.FaceRecognizer
import com.example.narratorapp.memory.PlaceRecognizer
import com.example.narratorapp.memory.RecognitionDatabase
import com.example.narratorapp.memory.EmbeddingEntity

/**

/**
 * Unified memory manager for faces and places
 * Handles recognition, storage, and retrieval
 */
class MemoryManager(private val context: Context) {
    
    private val faceRecognizer = FaceRecognizer(context)
    private val placeRecognizer = PlaceRecognizer(context)
    private val database = RecognitionDatabase.getDatabase(context)
    private val dao = database.embeddingDao()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // In-memory cache for fast lookup
    private val faceCache = mutableMapOf<String, FloatArray>()
    private val placeCache = mutableMapOf<String, FloatArray>()
    
    private val recognitionThreshold = 0.75f // Cosine similarity threshold
    
    init {
        // Load embeddings into cache on initialization
        scope.launch {
            loadCacheFromDatabase()
        }
    }
    
    private suspend fun loadCacheFromDatabase() {
        try {
            val allEmbeddings = dao.getAllEmbeddings()
            allEmbeddings.forEach { entity ->
                val embedding = stringToFloatArray(entity.embedding)
                when (entity.type) {
                    "face" -> faceCache[entity.label] = embedding
                    "place" -> placeCache[entity.label] = embedding
                }
            }
            Log.d("MemoryManager", "Loaded ${faceCache.size} faces and ${placeCache.size} places")
        } catch (e: Exception) {
            Log.e("MemoryManager", "Error loading cache", e)
        }
    }
    
    /**
     * Learn a new face
     */
    suspend fun learnFace(bitmap: Bitmap, personName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val embedding = faceRecognizer.getEmbedding(bitmap)
                
                // Store in database
                val entity = EmbeddingEntity(
                    label = personName,
                    type = "face",
                    embedding = floatArrayToString(embedding)
                )
                dao.insertEmbedding(entity)
                
                // Update cache
                faceCache[personName] = embedding
                
                Log.d("MemoryManager", "Learned face: $personName")
                true
            } catch (e: Exception) {
                Log.e("MemoryManager", "Error learning face", e)
                false
            }
        }
    }
    
    /**
     * Recognize a face from bitmap
     */
    suspend fun recognizeFace(bitmap: Bitmap): RecognitionResult? {
        return withContext(Dispatchers.Default) {
            try {
                if (faceCache.isEmpty()) {
                    return@withContext null
                }
                
                val embedding = faceRecognizer.getEmbedding(bitmap)
                
                var bestMatch: String? = null
                var bestSimilarity = 0f
                
                faceCache.forEach { (name, storedEmbedding) ->
                    val similarity = faceRecognizer.cosineSimilarity(embedding, storedEmbedding)
                    if (similarity > bestSimilarity && similarity > recognitionThreshold) {
                        bestSimilarity = similarity
                        bestMatch = name
                    }
                }
                
                if (bestMatch != null) {
                    RecognitionResult(
                        label = bestMatch!!,
                        confidence = bestSimilarity,
                        type = RecognitionType.FACE
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("MemoryManager", "Error recognizing face", e)
                null
            }
        }
    }
    
    /**
     * Learn a new place/location
     */
    suspend fun learnPlace(bitmap: Bitmap, placeName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val embedding = placeRecognizer.getEmbedding(bitmap)
                
                val entity = EmbeddingEntity(
                    label = placeName,
                    type = "place",
                    embedding = floatArrayToString(embedding)
                )
                dao.insertEmbedding(entity)
                
                placeCache[placeName] = embedding
                
                Log.d("MemoryManager", "Learned place: $placeName")
                true
            } catch (e: Exception) {
                Log.e("MemoryManager", "Error learning place", e)
                false
            }
        }
    }
    
    /**
     * Recognize a place from bitmap
     */
    suspend fun recognizePlace(bitmap: Bitmap): RecognitionResult? {
        return withContext(Dispatchers.Default) {
            try {
                if (placeCache.isEmpty()) {
                    return@withContext null
                }
                
                val embedding = placeRecognizer.getEmbedding(bitmap)
                
                var bestMatch: String? = null
                var bestSimilarity = 0f
                
                placeCache.forEach { (name, storedEmbedding) ->
                    val similarity = cosineSimilarity(embedding, storedEmbedding)
                    if (similarity > bestSimilarity && similarity > recognitionThreshold) {
                        bestSimilarity = similarity
                        bestMatch = name
                    }
                }
                
                if (bestMatch != null) {
                    RecognitionResult(
                        label = bestMatch!!,
                        confidence = bestSimilarity,
                        type = RecognitionType.PLACE
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("MemoryManager", "Error recognizing place", e)
                null
            }
        }
    }
    
    /**
     * Delete a learned face or place
     */
    suspend fun forget(label: String, type: RecognitionType) {
        withContext(Dispatchers.IO) {
            dao.deleteByLabel(label)
            when (type) {
                RecognitionType.FACE -> faceCache.remove(label)
                RecognitionType.PLACE -> placeCache.remove(label)
            }
            Log.d("MemoryManager", "Forgot: $label")
        }
    }
    
    /**
     * Get all learned faces
     */
    suspend fun getAllFaces(): List<String> {
        return withContext(Dispatchers.IO) {
            dao.getAllEmbeddings()
                .filter { it.type == "face" }
                .map { it.label }
        }
    }
    
    /**
     * Get all learned places
     */
    suspend fun getAllPlaces(): List<String> {
        return withContext(Dispatchers.IO) {
            dao.getAllEmbeddings()
                .filter { it.type == "place" }
                .map { it.label }
        }
    }
    
    /**
     * Clear all memories
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            dao.clearAll()
            faceCache.clear()
            placeCache.clear()
            Log.d("MemoryManager", "All memories cleared")
        }
    }
    
    // Helper functions
    private fun floatArrayToString(array: FloatArray): String {
        return array.joinToString(",")
    }
    
    private fun stringToFloatArray(string: String): FloatArray {
        return string.split(",").map { it.toFloat() }.toFloatArray()
    }
    
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must have same dimension" }
        
        val dot = vec1.zip(vec2).sumOf { (a, b) -> (a * b).toDouble() }
        val mag1 = kotlin.math.sqrt(vec1.sumOf { (it * it).toDouble() })
        val mag2 = kotlin.math.sqrt(vec2.sumOf { (it * it).toDouble() })
        
        return (dot / (mag1 * mag2)).toFloat()
    }
    
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * Recognition result data class
 */
data class RecognitionResult(
    val label: String,
    val confidence: Float,
    val type: RecognitionType
) {
    fun confidencePercent(): Int = (confidence * 100).toInt()
}

enum class RecognitionType {
    FACE,
    PLACE
}