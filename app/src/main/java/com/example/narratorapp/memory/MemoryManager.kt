package com.example.narratorapp.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*

class MemoryManager(private val context: Context) {
    
    private val faceRecognizer = FaceRecognizer(context)
    private val placeRecognizer = PlaceRecognizer(context)
    private val database = RecognitionDatabase.getDatabase(context)
    private val dao = database.embeddingDao()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val faceCache = mutableMapOf<String, FloatArray>()
    private val placeCache = mutableMapOf<String, FloatArray>()
    
    private val recognitionThreshold = 0.75f

    init {
        scope.launch {
            loadCacheFromDatabase()
        }
    }
    
    private suspend fun loadCacheFromDatabase() {
    try {
        val allEmbeddings = dao.getAllEmbeddings()
        allEmbeddings.forEach { entity ->
            // ✅ No parsing needed - already FloatArray
            when (entity.type) {
                "face" -> faceCache[entity.labelLower] = entity.embedding
                "place" -> placeCache[entity.labelLower] = entity.embedding
            }
        }
        Log.d("MemoryManager", "Loaded ${faceCache.size} faces and ${placeCache.size} places")
    } catch (e: Exception) {
        Log.e("MemoryManager", "Error loading cache", e)
    }
}
    
    suspend fun learnFace(bitmap: Bitmap, personName: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val embedding = faceRecognizer.getEmbedding(bitmap)
            
            val entity = EmbeddingEntity(
                label = personName,
                labelLower = personName.lowercase(),  // ✅ Add normalized
                type = "face",
                embedding = embedding  // ✅ Direct FloatArray
            )
            dao.insertEmbedding(entity)
            
            faceCache[personName.lowercase()] = embedding  // ✅ Use lowercase key
            
            Log.d("MemoryManager", "Learned face: $personName")
            true
        } catch (e: Exception) {
            Log.e("MemoryManager", "Error learning face", e)
            false
        }
    }
}
    
    suspend fun recognizeFace(bitmap: Bitmap): RecognitionResult? {
        return withContext(Dispatchers.Default) {
            try {
                if (faceCache.isEmpty()) return@withContext null
                
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
    
    suspend fun learnPlace(bitmap: Bitmap, placeName: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val embedding = placeRecognizer.getEmbedding(bitmap)
            
            val entity = EmbeddingEntity(
                label = placeName,
                labelLower = placeName.lowercase(),  // ✅ Add normalized
                type = "place",
                embedding = embedding  // ✅ Direct FloatArray
            )
            dao.insertEmbedding(entity)
            
            placeCache[placeName.lowercase()] = embedding  // ✅ Use lowercase key
            
            Log.d("MemoryManager", "Learned place: $placeName")
            true
        } catch (e: Exception) {
            Log.e("MemoryManager", "Error learning place", e)
            false
        }
    }
}

    
    suspend fun recognizePlace(bitmap: Bitmap): RecognitionResult? {
        return withContext(Dispatchers.Default) {
            try {
                if (placeCache.isEmpty()) return@withContext null
                
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
    
    suspend fun forget(label: String, type: RecognitionType) {
    withContext(Dispatchers.IO) {
        val labelLower = label.lowercase()
        val typeStr = when (type) {
            RecognitionType.FACE -> "face"
            RecognitionType.PLACE -> "place"
        }
        
        dao.deleteByLabelAndType(labelLower, typeStr)
        
        when (type) {
            RecognitionType.FACE -> faceCache.remove(labelLower)
            RecognitionType.PLACE -> placeCache.remove(labelLower)
        }
    }
}
    
    suspend fun getAllFaces(): List<String> {
        return withContext(Dispatchers.IO) {
            dao.getAllEmbeddings()
                .filter { it.type == "face" }
                .map { it.label }
        }
    }
    
    suspend fun getAllPlaces(): List<String> {
        return withContext(Dispatchers.IO) {
            dao.getAllEmbeddings()
                .filter { it.type == "place" }
                .map { it.label }
        }
    }
    
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            dao.clearAll()
            faceCache.clear()
            placeCache.clear()
        }
    }
    
    private fun floatArrayToString(array: FloatArray): String {
        return array.joinToString(",")
    }
    
    private fun stringToFloatArray(string: String): FloatArray {
        return string.split(",").map { it.toFloat() }.toFloatArray()
    }
    
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size)
        
        val dot = vec1.zip(vec2).sumOf { (a, b) -> (a * b).toDouble() }
        val mag1 = kotlin.math.sqrt(vec1.sumOf { (it * it).toDouble() })
        val mag2 = kotlin.math.sqrt(vec2.sumOf { (it * it).toDouble() })
        
        return (dot / (mag1 * mag2)).toFloat()
    }
    
    fun cleanup() {
        scope.cancel()
    }
}

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
