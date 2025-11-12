package com.example.narratorapp.voice

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.narratorapp.R
import com.example.narratorapp.narration.TTSManager
import com.example.narratorapp.voice.VoiceCommandManager


/**
 * Foreground service for continuous voice command listening
 * Keeps the app listening for hotword even when in background
 */
class VoiceCommandService : Service() {
    
    private val binder = LocalBinder()
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var ttsManager: TTSManager
    
    private var commandCallback: ((VoiceCommand) -> Unit)? = null
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "VoiceCommandChannel"
        const val ACTION_START_LISTENING = "com.example.narratorapp.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.example.narratorapp.STOP_LISTENING"
        
        /**
         * Start the voice command service
         */
        fun start(context: Context) {
            val intent = Intent(context, VoiceCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the voice command service
         */
        fun stop(context: Context) {
            val intent = Intent(context, VoiceCommandService::class.java)
            context.stopService(intent)
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceCommandService = this@VoiceCommandService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d("VoiceCommandService", "Service created")
        
        ttsManager = TTSManager(this)
        voiceCommandManager = VoiceCommandManager(this, ttsManager)
        
        voiceCommandManager.setOnCommandRecognizedListener { command ->
            Log.d("VoiceCommandService", "Command recognized: ${command.getDescription()}")
            commandCallback?.invoke(command)
        }
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(false))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                startListening()
            }
            ACTION_STOP_LISTENING -> {
                stopListening()
            }
            else -> {
                // Default: start listening
                startListening()
            }
        }
        
        return START_STICKY // Restart service if killed
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    fun startListening() {
        voiceCommandManager.startListening()
        updateNotification(true)
        Log.d("VoiceCommandService", "Started listening for commands")
    }
    
    fun stopListening() {
        voiceCommandManager.stopListening()
        updateNotification(false)
        Log.d("VoiceCommandService", "Stopped listening for commands")
    }
    
    fun isListening(): Boolean {
        return voiceCommandManager.isCurrentlyListening()
    }
    
    /**
     * Set callback for when commands are recognized
     */
    fun setCommandCallback(callback: (VoiceCommand) -> Unit) {
        commandCallback = callback
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Commands",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Narrator app voice command service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(isListening: Boolean): Notification {
        val stopIntent = Intent(this, VoiceCommandService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val startIntent = Intent(this, VoiceCommandService::class.java).apply {
            action = ACTION_START_LISTENING
        }
        val startPendingIntent = PendingIntent.getService(
            this, 1, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = if (isListening) "Voice Commands Active" else "Voice Commands Paused"
        val text = if (isListening) "Say 'Hey Narrator' to give commands" else "Tap to resume"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic) // You'll need to add this icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                if (isListening) R.drawable.ic_pause else R.drawable.ic_play,
                if (isListening) "Pause" else "Resume",
                if (isListening) stopPendingIntent else startPendingIntent
            )
            .build()
    }
    
    private fun updateNotification(isListening: Boolean) {
        val notification = createNotification(isListening)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        voiceCommandManager.cleanup()
        ttsManager.shutdown()
        Log.d("VoiceCommandService", "Service destroyed")
    }
}