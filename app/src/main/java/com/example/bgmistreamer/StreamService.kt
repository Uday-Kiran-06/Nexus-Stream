package com.example.bgmistreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpDisplay

import android.media.MediaPlayer
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import androidx.compose.runtime.mutableStateOf

class StreamService : Service(), ConnectChecker {

    companion object {
        private const val CHANNEL_ID = "StreamChannel"
        private const val NOTIFICATION_ID = 1

        val isStreamingState = mutableStateOf(false)
        val streamStartTime = mutableStateOf(0L)
        
        private val mediaPlayers = mutableListOf<MediaPlayer?>()
    }

    private lateinit var rtmpDisplay: RtmpDisplay
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var streamUrl: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        rtmpDisplay = RtmpDisplay(baseContext, true, this)
        rtmpDisplay.glInterface.setForceRender(true)
        
        // Setup placeholders for filters
        setupFilters()
    }
    
    private fun setupFilters() {
        // Filters are now added dynamically in onStartCommand
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == "STOP") {
                stopStream()
                stopSelf()
                return START_NOT_STICKY
            }

            streamUrl = intent.getStringExtra("url") ?: ""
            val resultCode = intent.getIntExtra("resultCode", android.app.Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")

            if (resultCode == android.app.Activity.RESULT_OK && data != null && !rtmpDisplay.isStreaming) {
                rtmpDisplay.setIntentResult(resultCode, data)
                
                val quality = intent.getStringExtra("quality") ?: "1080p 60fps"
                val isLandscape = intent.getBooleanExtra("isLandscape", true)
                val useChromaKey = intent.getBooleanExtra("chromaKey", false)
                
                val (width, height, fps, videoBitrate) = when (quality) {
                    "720p 30fps" -> listOf(if(isLandscape) 1280 else 720, if(isLandscape) 720 else 1280, 30, 2500 * 1024)
                    "1440p 60fps" -> listOf(if(isLandscape) 2560 else 1440, if(isLandscape) 1440 else 2560, 60, 9000 * 1024)
                    "4K 60fps" -> listOf(if(isLandscape) 3840 else 2160, if(isLandscape) 2160 else 3840, 60, 15000 * 1024)
                    else -> listOf(if(isLandscape) 1920 else 1080, if(isLandscape) 1080 else 1920, 60, 4500 * 1024)
                }

                val audioBitrate = 128 * 1024
                val sampleRate = 32000 // typical
                val isStereo = true

                val videoPrepared = rtmpDisplay.prepareVideo(width, height, fps, videoBitrate, 0, 0)
                val audioPrepared = rtmpDisplay.prepareAudio(audioBitrate, sampleRate, isStereo, false, false)

                if (videoPrepared && audioPrepared) {
                    
                    rtmpDisplay.startStream(streamUrl)
                    isStreamingState.value = true
                    streamStartTime.value = System.currentTimeMillis()
                    
                    // Setup filters dynamically based on UI layout
                    val uris = intent.getStringArrayListExtra("overlayUris")
                    val scales = intent.getFloatArrayExtra("overlayScales")
                    val xPos = intent.getFloatArrayExtra("overlayX")
                    val yPos = intent.getFloatArrayExtra("overlayY")
                    val useChromaKey = intent.getBooleanExtra("chromaKey", false)
                    
                    if (uris != null && scales != null && xPos != null && yPos != null) {
                        for (i in uris.indices) {
                            try {
                                val uri = android.net.Uri.parse(uris[i])
                                val mimeType = contentResolver.getType(uri)
                                val isVideo = mimeType?.startsWith("video/") == true
                                
                                if (isVideo) {
                                    val surfaceFilter = SurfaceFilterRender { surfaceTexture ->
                                        val mediaPlayer = MediaPlayer.create(baseContext, uri)
                                        mediaPlayer?.setSurface(Surface(surfaceTexture))
                                        mediaPlayer?.isLooping = true
                                        mediaPlayer?.start()
                                        mediaPlayers.add(mediaPlayer)
                                    }
                                    rtmpDisplay.glInterface.addFilter(surfaceFilter)
                                    surfaceFilter.setScale(scales[i], scales[i])
                                    surfaceFilter.setPosition(xPos[i], yPos[i])
                                } else {
                                    val inputStream = contentResolver.openInputStream(uri)
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                                    inputStream?.close()
                                    
                                    if (bitmap != null) {
                                        if (useChromaKey) {
                                            val chromaFilter = com.pedro.encoder.input.gl.render.filters.ChromaFilterRender()
                                            rtmpDisplay.glInterface.addFilter(chromaFilter)
                                            chromaFilter.setImage(bitmap)
                                        } else {
                                            val imageFilter = com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender()
                                            rtmpDisplay.glInterface.addFilter(imageFilter)
                                            imageFilter.setImage(bitmap)
                                            imageFilter.setScale(scales[i], scales[i])
                                            imageFilter.setPosition(xPos[i], yPos[i])
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    updateNotification("Streaming to $streamUrl")
                    showOverlay()
                } else {
                    val errorMsg = if (!videoPrepared && !audioPrepared) "Video and Audio unsupported"
                                   else if (!videoPrepared) "Video resolution/fps unsupported"
                                   else "Audio settings unsupported"
                    Toast.makeText(this, "Error preparing stream: $errorMsg", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Example overlay implementation using a simple button
        val button = Button(this).apply {
            text = "Stop Stream"
            setOnClickListener {
                stopStream()
                stopSelf()
            }
        }
        
        overlayView = button

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        windowManager.addView(overlayView, params)
    }

    private fun stopStream() {
        if (isStreamingState.value) {
            rtmpDisplay.stopStream()
            isStreamingState.value = false
            streamStartTime.value = 0L
        }
        
        mediaPlayers.forEach { 
            it?.stop()
            it?.release() 
        }
        mediaPlayers.clear()
        
        if (overlayView != null) {
            windowManager.removeView(overlayView)
            overlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BGMI Streamer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ConnectChecker callbacks
    override fun onConnectionStarted(url: String) { }
    override fun onConnectionSuccess() {
        Toast.makeText(this, "Connection success", Toast.LENGTH_SHORT).show()
    }
    override fun onConnectionFailed(reason: String) {
        Toast.makeText(this, "Connection failed: $reason", Toast.LENGTH_SHORT).show()
        stopStream()
        stopSelf()
    }
    override fun onNewBitrate(bitrate: Long) { }
    override fun onDisconnect() {
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
    }
    override fun onAuthError() {
        Toast.makeText(this, "Auth error", Toast.LENGTH_SHORT).show()
    }
    override fun onAuthSuccess() { }
}
