package com.example.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener
import java.io.File
import java.io.FileOutputStream

/**
 * Fully offline "help" wake-word listener. Place the unpacked model at
 * app/src/main/assets/vosk-model-small-en-us-0.15/ before building.
 */
class WakeWordService : Service(), RecognitionListener {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var speechService: SpeechService? = null
    private var model: Model? = null
    @Volatile private var sosTriggered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                val modelDirectory = File(filesDir, MODEL_DIRECTORY)
                updateNotification("Copying AI model...")
                if (!File(modelDirectory, "am").exists()) {
                    copyAssetDirectory(MODEL_DIRECTORY, modelDirectory)
                }
                updateNotification("Loading AI model...")
                val loadedModel = Model(modelDirectory.absolutePath)
                model = loadedModel
                val recognizer = Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR)
                updateNotification("Starting microphone...")
                speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                    it.startListening(this@WakeWordService)
                }
                updateNotification("✅ Listening for 'help'")
                Log.i(TAG, "Offline wake-word detection started.")
            } catch (error: Exception) {
                Log.e(TAG, "Unable to initialize Vosk. Ensure the model assets are included.", error)
                updateNotification("❌ Error: ${error.message}")
                stopSelf()
            }
        }
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        destination.mkdirs()
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        } else {
            children.forEach { child ->
                copyAssetDirectory("$assetPath/$child", File(destination, child))
            }
        }
    }

    override fun onResult(hypothesis: String?) = inspectResult(hypothesis)
    override fun onPartialResult(hypothesis: String?) = inspectResult(hypothesis)
    override fun onFinalResult(hypothesis: String?) = inspectResult(hypothesis)
    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error", exception)
    }
    
    override fun onTimeout() {
        // FIX: Replaced invalid 'isDestroyed' check with a safe null-check
        if (speechService != null) {
            speechService?.startListening(this)
        }
    }

    private fun inspectResult(raw: String?) {
        if (raw.isNullOrBlank() || sosTriggered) return
        try {
            val recognized = JSONObject(raw).optString("text").trim().lowercase()
            if (recognized == "help") {
                sosTriggered = true
                Log.w(TAG, "WAKE WORD DETECTED: help")
                triggerSOSProtocol()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not parse Vosk result", error)
        }
    }

    /** Placeholder only. Connect this to a reviewed SOS flow when ready. */
    private fun triggerSOSProtocol() {
        Log.w(TAG, "SOS protocol placeholder invoked.")
        // TODO: Invoke the app's explicitly designed SOS workflow here.
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentTitle("Guardian Status")
                .setContentText(message)
                .setOngoing(true)
                .setSilent(true)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Offline Wake Word", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Silent, on-device detection of the word help" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("Guardian wake-word detection")
            .setContentText("Listening on-device for “help”")
            .setOngoing(true)
            .setSilent(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "guardian_wake_word"
        private const val NOTIFICATION_ID = 4102
        private const val MODEL_DIRECTORY = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16_000.0f
        private const val GRAMMAR = """["help", "[unk]"]"""
    }
}
