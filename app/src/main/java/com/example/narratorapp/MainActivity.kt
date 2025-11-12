package com.example.narratorapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.narratorapp.camera.CameraXManager
import com.example.narratorapp.camera.OverlayView
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.navigation.NavigationEngine
import com.example.narratorapp.memory.MemoryManager
import com.example.narratorapp.voice.VoiceCommand
import com.example.narratorapp.voice.VoiceCommandService
import android.content.*
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.narratorapp.detection.DetectedObject
import com.example.narratorapp.navigation.ARCoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



class MainActivity : ComponentActivity() {
    
    private lateinit var cameraXManager: CameraXManager
    private lateinit var ttsManager: TTSManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var navigationEngine: NavigationEngine
    private lateinit var arCoreManager: ARCoreManager
    
    private var voiceCommandService: VoiceCommandService? = null
    private var serviceBound = false
    
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceCommandService.LocalBinder
            voiceCommandService = binder.getService()
            serviceBound = true
            
            // Set up command callback
            voiceCommandService?.setCommandCallback { command ->
                handleVoiceCommand(command)
            }
            
            Log.d("MainActivity", "Voice command service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceCommandService = null
            serviceBound = false
            Log.d("MainActivity", "Voice command service disconnected")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize UI
        previewView = findViewById(R.id.view_finder)
        overlayView = findViewById(R.id.overlayView)
        
        // Initialize components
        ttsManager = TTSManager(this)
        memoryManager = MemoryManager(this)
        arCoreManager = ARCoreManager(this)
        lifecycle.addObserver(arCoreManager)
        
        if (arCoreManager.initialize()) {
            navigationEngine = NavigationEngine(this, ttsManager, arCoreManager)
        }
        
        // Request permissions
        requestPermissions()
    }
    
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            
            if (cameraGranted) {
                startCamera()
            }
            
            if (audioGranted) {
                startVoiceCommandService()
            } else {
                Toast.makeText(this, 
                    "Microphone permission required for voice commands", 
                    Toast.LENGTH_LONG).show()
            }
        }
        
        permissionLauncher.launch(permissions)
    }
    
    private fun startVoiceCommandService() {
        // Start the service
        VoiceCommandService.start(this)
        
        // Bind to the service
        val intent = Intent(this, VoiceCommandService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun handleVoiceCommand(command: VoiceCommand) {
        Log.d("MainActivity", "Handling command: ${command.getDescription()}")
        
        when (command) {
            // Navigation
            is VoiceCommand.StartNavigation -> {
                // TODO: Implement route selection
                ttsManager.speak("Please select a destination first")
            }
            
            is VoiceCommand.StopNavigation -> {
                if (::navigationEngine.isInitialized) {
                    navigationEngine.stopNavigation()
                }
            }
            
            is VoiceCommand.RecordWaypoint -> {
                if (::navigationEngine.isInitialized) {
                    val waypoint = navigationEngine.recordWaypoint("Voice Waypoint")
                    if (waypoint != null) {
                        ttsManager.speak("Waypoint recorded")
                    } else {
                        ttsManager.speak("Failed to record waypoint")
                    }
                }
            }
            
            is VoiceCommand.GetLocation -> {
                val pose = arCoreManager.getCameraPose()
                if (pose != null) {
                    ttsManager.speak("Your coordinates are X ${pose.tx().toInt()}, Z ${pose.tz().toInt()}")
                } else {
                    ttsManager.speak("Location not available")
                }
            }
            
            // Reading Mode
            is VoiceCommand.EnableReadingMode -> {
                // Switch analyzer mode to reading
                ttsManager.speak("Reading mode enabled")
                // TODO: Set analyzer mode
            }
            
            is VoiceCommand.DisableReadingMode -> {
                ttsManager.speak("Normal mode enabled")
                // TODO: Set analyzer mode
            }
            
            // Memory Commands
            is VoiceCommand.LearnFace -> {
                captureFaceForLearning(command.name)
            }
            
            is VoiceCommand.LearnFacePrompt -> {
                ttsManager.speak("Please say the person's name, then say learn face again")
            }
            
            is VoiceCommand.LearnPlace -> {
                capturePlaceForLearning(command.name)
            }
            
            is VoiceCommand.LearnPlacePrompt -> {
                ttsManager.speak("Please say the place name, then say learn place again")
            }
            
            is VoiceCommand.RecognizeFace -> {
                ttsManager.speak("Scanning for faces")
                // Face recognition happens automatically in CombinedAnalyzer
            }
            
            is VoiceCommand.RecognizePlace -> {
                ttsManager.speak("Analyzing location")
                // Place recognition happens automatically in CombinedAnalyzer
            }
            
            // Scene Description
            is VoiceCommand.DescribeScene -> {
                describeCurrentScene()
            }
            
            is VoiceCommand.FindObject -> {
                ttsManager.speak("What object would you like me to find?")
                // TODO: Implement specific object search
            }
            
            // Volume Control
            is VoiceCommand.IncreaseVolume -> {
                adjustVolume(increase = true)
            }
            
            is VoiceCommand.DecreaseVolume -> {
                adjustVolume(increase = false)
            }
            
            // Playback Control
            is VoiceCommand.Pause -> {
                voiceCommandService?.stopListening()
                ttsManager.speak("Paused. Say resume to continue")
            }
            
            is VoiceCommand.Resume -> {
                voiceCommandService?.startListening()
                ttsManager.speak("Resumed")
            }
            
            // Help
            is VoiceCommand.Help -> {
                announceAvailableCommands()
            }
        }
    }
    
    private fun captureFaceForLearning(name: String) {
        lifecycleScope.launch {
            try {
                // Capture current camera frame
                val bitmap = captureCurrentFrame()
                
                if (bitmap != null) {
                    val success = memoryManager.learnFace(bitmap, name)
                    if (success) {
                        ttsManager.speak("I will remember $name")
                    } else {
                        ttsManager.speak("Failed to learn face. Please try again")
                    }
                } else {
                    ttsManager.speak("Could not capture image")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error learning face", e)
                ttsManager.speak("Error occurred while learning face")
            }
        }
    }
    
    private fun capturePlaceForLearning(name: String) {
        lifecycleScope.launch {
            try {
                val bitmap = captureCurrentFrame()
                
                if (bitmap != null) {
                    val success = memoryManager.learnPlace(bitmap, name)
                    if (success) {
                        ttsManager.speak("Location saved as $name")
                    } else {
                        ttsManager.speak("Failed to learn location")
                    }
                } else {
                    ttsManager.speak("Could not capture image")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error learning place", e)
                ttsManager.speak("Error occurred while learning place")
            }
        }
    }
    
    private fun describeCurrentScene() {
        lifecycleScope.launch {
            // Get current detections from analyzer
            val objects = getCurrentDetections() // You'll need to implement this
            
            if (objects.isEmpty()) {
                ttsManager.speak("I don't see any objects in view")
            } else {
                val description = buildString {
                    append("I see ")
                    objects.take(5).forEachIndexed { index, obj ->
                        if (index > 0 && index == objects.size - 1) {
                            append(" and ")
                        } else if (index > 0) {
                            append(", ")
                        }
                        append("a ${obj.label}")
                    }
                }
                ttsManager.speak(description)
            }
        }
    }
    
    private fun adjustVolume(increase: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }
    
    private fun announceAvailableCommands() {
        val commands = """
            Available commands:
            Navigation: start navigation, stop navigation, record waypoint, where am I.
            Reading: read text, stop reading.
            Memory: learn face, learn place, who is this, where is this.
            Scene: what do you see, find object.
            Control: increase volume, decrease volume, pause, resume.
        """.trimIndent()
        
        ttsManager.speak(commands)
    }
    
    // Helper function to capture current camera frame
    private suspend fun captureCurrentFrame(): Bitmap? {
        return withContext(Dispatchers.Main) {
            try {
                // You'll need to implement frame capture from PreviewView
                // This is a placeholder implementation
                previewView.bitmap
            } catch (e: Exception) {
                Log.e("MainActivity", "Error capturing frame", e)
                null
            }
        }
    }
    
    private fun getCurrentDetections(): List<DetectedObject> {
        // Return current detections from overlay
        return overlayView?.objects ?: emptyList()
    }
    
    private fun startCamera() {
        cameraXManager = CameraXManager(
            context = this,
            ttsManager = ttsManager,
            previewView = previewView,
            overlayView = overlayView,
            navigationEngine = if (::navigationEngine.isInitialized) navigationEngine else null,
            memoryManager = memoryManager
        )
        cameraXManager.start()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        
        VoiceCommandService.stop(this)
        
        ttsManager.shutdown()
        memoryManager.cleanup()
        if (::navigationEngine.isInitialized) {
            navigationEngine.cleanup()
        }
        arCoreManager.cleanup()
    }
}