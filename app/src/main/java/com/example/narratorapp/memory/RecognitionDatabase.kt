package com.example.narrator.memory

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(entities = [EmbeddingEntity::class], version = 1, exportSchema = false)
abstract class RecognitionDatabase : RoomDatabase() {
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        @Volatile
        private var INSTANCE: RecognitionDatabase? = null

        fun getDatabase(context: Context): RecognitionDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecognitionDatabase::class.java,
                    "recognition_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
