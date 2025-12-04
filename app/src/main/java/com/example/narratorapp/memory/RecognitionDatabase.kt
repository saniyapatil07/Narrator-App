package com.example.narratorapp.memory

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import android.util.Log

@Database(entities = [EmbeddingEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)  // ✅ Add type converter
abstract class RecognitionDatabase : RoomDatabase() {
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        @Volatile
        private var INSTANCE: RecognitionDatabase? = null

        // ===== Migration from version 1 to 2 =====
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.i("RecognitionDB", "Migrating database from v1 to v2...")
                
                try {
                    // Create new table with correct schema
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS embeddings_new (
                            label TEXT NOT NULL,
                            labelLower TEXT NOT NULL,
                            type TEXT NOT NULL,
                            embedding TEXT NOT NULL,
                            lastSeen INTEGER NOT NULL DEFAULT 0,
                            usageCount INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (labelLower, type)
                        )
                    """)
                    
                    // Copy existing data (embedding is already string, so we can copy directly)
                    database.execSQL("""
                        INSERT INTO embeddings_new (label, labelLower, type, embedding, lastSeen, usageCount)
                        SELECT 
                            label, 
                            LOWER(label) as labelLower, 
                            type, 
                            embedding,
                            ${System.currentTimeMillis()} as lastSeen,
                            0 as usageCount
                        FROM embeddings
                    """)
                    
                    // Drop old table
                    database.execSQL("DROP TABLE IF EXISTS embeddings")
                    
                    // Rename new table
                    database.execSQL("ALTER TABLE embeddings_new RENAME TO embeddings")
                    
                    Log.i("RecognitionDB", "✓ Migration successful - user data preserved!")
                    
                } catch (e: Exception) {
                    Log.e("RecognitionDB", "Migration failed", e)
                    // If migration fails, create fresh table (will lose data)
                    database.execSQL("DROP TABLE IF EXISTS embeddings")
                    database.execSQL("""
                        CREATE TABLE embeddings (
                            label TEXT NOT NULL,
                            labelLower TEXT NOT NULL,
                            type TEXT NOT NULL,
                            embedding TEXT NOT NULL,
                            lastSeen INTEGER NOT NULL DEFAULT 0,
                            usageCount INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (labelLower, type)
                        )
                    """)
                }
            }
        }

        fun getDatabase(context: Context): RecognitionDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecognitionDatabase::class.java,
                    "recognition_database"
                )
                .addMigrations(MIGRATION_1_2)  // ✅ Add migration
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
