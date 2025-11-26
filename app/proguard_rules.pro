# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options


# ========================
# App & library keeps (your existing rules)
# ========================

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

# Keep data classes (app packages)
-keep class com.example.narratorapp.model.** { *; }
-keep class com.example.narratorapp.detection.** { *; }
-keep class com.example.narratorapp.navigation.** { *; }
-keep class com.example.narratorapp.memory.** { *; }
-keep class com.example.narratorapp.voice.** { *; }

# ========================
# TensorFlow Lite GPU / support specifics (fixes R8 missing class error)
# ========================

# Explicitly keep GPU delegate + factory + nested options used reflectively
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory$Options { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend { *; }

# Keep entire GPU package (conservative; safe)
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# Keep TF core & support classes used reflectively
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.support.**

# Avoid warnings for TF native-backed classes
-dontwarn org.tensorflow.**
