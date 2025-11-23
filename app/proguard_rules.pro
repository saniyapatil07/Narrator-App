# Keep TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep ML Kit
-keep class com.google.mlkit.** { *; }

# Keep ARCore
-keep class com.google.ar.** { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep data classes
-keep class com.example.narratorapp.model.** { *; }
-keep class com.example.narratorapp.detection.** { *; }
-keep class com.example.narratorapp.navigation.** { *; }
-keep class com.example.narratorapp.memory.** { *; }
-keep class com.example.narratorapp.voice.** { *; }