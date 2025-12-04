package com.example.narratorapp.memory

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromFloatArray(value: FloatArray): String {
        return value.joinToString(",")
    }
    
    @TypeConverter
    fun toFloatArray(value: String): FloatArray {
        return if (value.isEmpty()) {
            floatArrayOf()
        } else {
            value.split(",").map { it.toFloat() }.toFloatArray()
        }
    }
}