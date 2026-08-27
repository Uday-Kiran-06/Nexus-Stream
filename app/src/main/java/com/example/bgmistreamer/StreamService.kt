package com.example.bgmistreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.rtmp.RtmpDisplay

class StreamService : Service(), ConnectChecker {

    companion object {
        private const val CHANNEL_ID = "StreamChannel"
        private const val NOTIFICATION_ID = 1

        val isStreamingState = mutableStateOf(false)
        val isMicMutedState = mutableStateOf(false)
        val streamStartTime = mutableStateOf(0L)
    }

    private lateinit var rtmpDisplay: RtmpDisplay
    private var windowManager: WindowManager? = null
    private var floatingLayout: LinearLayout? = null
    private var micButtonView: Button? = null
    private var streamUrl: String = ""
    private val mediaPlayers = mutableListOf<MediaPlayer?>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = createNotification("Initializing Stream Service...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        rtmpDisplay = RtmpDisplay(baseContext, true, this)
        rtmpDisplay.glInterface.setForceRender(true, 30)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action

            if (action == "STOP") {
                stopStream()
                stopSelf()
                return START_NOT_STICKY
            }

            if (action == "TOGGLE_MIC") {
                toggleMic()
                return START_STICKY
            }

            if (action == "UPDATE_OVERLAYS") {
                if (::rtmpDisplay.isInitialized && rtmpDisplay.isStreaming) {
                    rtmpDisplay.glInterface.clearFilters()
                    mediaPlayers.forEach {
                        try { it?.stop() } catch (_: Exception) {}
                        it?.release()
                    }
                    mediaPlayers.clear()
                    applyOverlaysFromIntent(intent)
                }
                return START_STICKY
            }

            streamUrl = intent.getStringExtra("url") ?: ""
            val resultCode = intent.getIntExtra("resultCode", android.app.Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")

            if (resultCode == android.app.Activity.RESULT_OK && data != null && !rtmpDisplay.isStreaming) {
                rtmpDisplay.setIntentResult(resultCode, data)

                rtmpDisplay.setMediaProjectionCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        mainHandler.post {
                            Toast.makeText(this@StreamService, "Screen capture stopped", Toast.LENGTH_SHORT).show()
                        }
                        stopStream()
                        stopSelf()
                    }
                })

                val quality = intent.getStringExtra("quality") ?: "720p 30fps"
                val isLandscape = intent.getBooleanExtra("isLandscape", true)
                val dpi = resources.displayMetrics.densityDpi

                data class VideoParams(val w: Int, val h: Int, val fps: Int, val bitrate: Int, val label: String)

                val cascade = mutableListOf<VideoParams>()
                when (quality) {
                    "4K 60fps" -> {
                        cascade += VideoParams(if (isLandscape) 3840 else 2160, if (isLandscape) 2160 else 3840, 60, 15000 * 1024, "4K 60fps")
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, 4000 * 1024, "1080p 60fps")
                    }
                    "1440p 60fps" -> {
                        cascade += VideoParams(if (isLandscape) 2560 else 1440, if (isLandscape) 1440 else 2560, 60, 9000 * 1024, "1440p 60fps")
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, 4000 * 1024, "1080p 60fps")
                    }
                    "1080p 60fps" -> {
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, 4000 * 1024, "1080p 60fps")
                    }
                    else -> {}
                }
                cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 30, 2000 * 1024, "720p 30fps")
                cascade += VideoParams(if (isLandscape) 854 else 480, if (isLandscape) 480 else 854, 30, 1000 * 1024, "480p 30fps")

                val audioOptions = listOf(
                    Triple(128 * 1024, 44100, true),
                    Triple(128 * 1024, 48000, true),
                    Triple(64 * 1024, 44100, false)
                )

                var videoPrepared = false
                var audioPrepared = false
                var successLabel = ""

                outer@ for (vp in cascade) {
                    for ((aBitrate, sampleRate, stereo) in audioOptions) {
                        val vOk = rtmpDisplay.prepareVideo(vp.w, vp.h, vp.fps, vp.bitrate, 0, dpi)
                        val aOk = rtmpDisplay.prepareAudio(aBitrate, sampleRate, stereo, false, false)
                        if (vOk && aOk) {
                            videoPrepared = true
                            audioPrepared = true
                            successLabel = "${vp.label} @ ${sampleRate}Hz"
                            break@outer
                        } else if (vOk && !aOk) {
                            videoPrepared = true
                            audioPrepared = false
                            rtmpDisplay.disableAudio()
                            successLabel = "${vp.label} (Video Only)"
                            break@outer
                        }
                        try { rtmpDisplay.stopStream() } catch (_: Exception) {}
                    }
                }

                if (videoPrepared) {
                    mainHandler.post {
                        Toast.makeText(this@StreamService, "Connecting: $successLabel", Toast.LENGTH_SHORT).show()
                    }
                    rtmpDisplay.startStream(streamUrl)
                    isStreamingState.value = true
                    isMicMutedState.value = false
                    streamStartTime.value = System.currentTimeMillis()

                    applyOverlaysFromIntent(intent)

                    updateNotification("Streaming to $streamUrl")
                    showOverlay()
                } else {
                    mainHandler.post {
                        Toast.makeText(this@StreamService, "Error: Device cannot encode video at this resolution", Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun applyOverlaysFromIntent(intent: Intent) {
        val uris = intent.getStringArrayListExtra("overlayUris") ?: return
        val scales = intent.getFloatArrayExtra("overlayScales") ?: return
        val xPos = intent.getFloatArrayExtra("overlayX") ?: return
        val yPos = intent.getFloatArrayExtra("overlayY") ?: return
        val chromaKeys = intent.getBooleanArrayExtra("overlayChromaKeys")

        for (i in uris.indices) {
            try {
                val uri = android.net.Uri.parse(uris[i])
                val mimeType = contentResolver.getType(uri)
                val isVideo = mimeType?.startsWith("video/") == true
                val useChromaForThis = chromaKeys?.getOrNull(i) ?: false

                if (isVideo) {
                    if (useChromaForThis) {
                        // Video with GPU Chroma Key shader (green screen removed)
                        val videoChromaFilter = VideoChromaFilterRender { surfaceTexture ->
                            val mediaPlayer = MediaPlayer.create(baseContext, uri)
                            mediaPlayer?.setSurface(Surface(surfaceTexture))
                            mediaPlayer?.isLooping = true
                            mediaPlayer?.start()
                            mediaPlayers.add(mediaPlayer)
                        }
                        videoChromaFilter.setSensitive(0.40f)
                        rtmpDisplay.glInterface.addFilter(videoChromaFilter)

                        mainHandler.postDelayed({
                            try {
                                videoChromaFilter.setOverlayScale(
                                    scales[i] / 100f,
                                    scales[i] / 100f
                                )
                                videoChromaFilter.setOverlayOffset(
                                    xPos[i] / 100f,
                                    yPos[i] / 100f
                                )
                            } catch (e: Exception) { e.printStackTrace() }
                        }, 500)
                    } else {
                        // Standard video overlay (BaseObjectFilterRender takes percentage 0f..100f)
                        val surfaceFilter = SurfaceFilterRender { surfaceTexture ->
                            val mediaPlayer = MediaPlayer.create(baseContext, uri)
                            mediaPlayer?.setSurface(Surface(surfaceTexture))
                            mediaPlayer?.isLooping = true
                            mediaPlayer?.start()
                            mediaPlayers.add(mediaPlayer)
                        }
                        rtmpDisplay.glInterface.addFilter(surfaceFilter)
                        mainHandler.postDelayed({
                            try {
                                surfaceFilter.setScale(scales[i], scales[i])
                                surfaceFilter.setPosition(xPos[i], yPos[i])
                            } catch (e: Exception) { e.printStackTrace() }
                        }, 500)
                    }
                } else {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (bitmap != null) {
                        // If chroma key is requested, cleanly remove green from the image bitmap
                        val processedBitmap = if (useChromaForThis) removeGreenScreen(bitmap) else bitmap
                        val imageFilter = com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender()
                        rtmpDisplay.glInterface.addFilter(imageFilter)
                        mainHandler.postDelayed({
                            try {
                                imageFilter.setImage(processedBitmap)
                                // setScale & setPosition take percentage 0f..100f
                                imageFilter.setScale(scales[i], scales[i])
                                imageFilter.setPosition(xPos[i], yPos[i])
                            } catch (e: Exception) { e.printStackTrace() }
                        }, 500)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleMic() {
        if (!::rtmpDisplay.isInitialized) return
        if (rtmpDisplay.isAudioMuted) {
            rtmpDisplay.enableAudio()
            isMicMutedState.value = false
            mainHandler.post {
                micButtonView?.text = "🎤 Mic"
                Toast.makeText(this, "🎤 Microphone Unmuted (ON)", Toast.LENGTH_SHORT).show()
            }
        } else {
            rtmpDisplay.disableAudio()
            isMicMutedState.value = true
            mainHandler.post {
                micButtonView?.text = "🔇 Muted"
                Toast.makeText(this, "🔇 Microphone Muted (OFF)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 12, 16, 12)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EE1E1E2E"))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#55FFFFFF"))
                }
            }

            val micBtn = Button(this).apply {
                text = if (isMicMutedState.value) "🔇 Muted" else "🎤 Mic"
                textSize = 12f
                setPadding(16, 6, 16, 6)
                setOnClickListener {
                    toggleMic()
                }
            }
            micButtonView = micBtn

            val stopBtn = Button(this).apply {
                text = "🔴 Stop"
                textSize = 12f
                setPadding(16, 6, 16, 6)
                setOnClickListener {
                    stopStream()
                    stopSelf()
                }
            }

            layout.addView(micBtn)
            layout.addView(stopBtn)

            floatingLayout = layout

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 80
            params.y = 80

            windowManager?.addView(layout, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopStream() {
        if (isStreamingState.value) {
            try { rtmpDisplay.stopStream() } catch (_: Exception) {}
            isStreamingState.value = false
            isMicMutedState.value = false
            streamStartTime.value = 0L
        }

        mediaPlayers.forEach {
            try { it?.stop() } catch (_: Exception) {}
            it?.release()
        }
        mediaPlayers.clear()

        try {
            floatingLayout?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingLayout = null
        micButtonView = null
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
            .setContentTitle("Nexus Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onConnectionStarted(url: String) {
        mainHandler.post {
            Toast.makeText(this@StreamService, "Connecting to stream server...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionSuccess() {
        mainHandler.post {
            Toast.makeText(this@StreamService, "Connected! You are now LIVE.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        mainHandler.post {
            Toast.makeText(this@StreamService, "Connection failed: $reason", Toast.LENGTH_LONG).show()
        }
        stopStream()
        stopSelf()
    }

    override fun onNewBitrate(bitrate: Long) { }

    override fun onDisconnect() {
        mainHandler.post {
            Toast.makeText(this@StreamService, "Stream disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthError() {
        mainHandler.post {
            Toast.makeText(this@StreamService, "Auth error: Check your Stream Key in Settings", Toast.LENGTH_LONG).show()
        }
        stopStream()
        stopSelf()
    }

    override fun onAuthSuccess() { }

    private fun removeGreenScreen(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val output = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val width = output.width
        val height = output.height
        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color shr 24) and 0xFF
            if (a == 0) continue
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val maxRb = maxOf(r, b)
            // Green screen cutoff: if green exceeds max of red/blue by 18+ units
            if (g > maxRb && (g - maxRb) > 18) {
                pixels[i] = 0 // transparent
            } else if (g > maxRb) {
                // Despill edges: clamp green so no green fringe or white glow
                pixels[i] = (a shl 24) or (r shl 16) or (maxRb shl 8) or b
            }
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}
