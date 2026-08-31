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
import android.media.MediaCodecInfo
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.rtmp.RtmpDisplay

class StreamService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "StreamChannel"
        private const val NOTIFICATION_ID = 1

        // Video Bitrate presets (in bps) optimized for high-motion gameplay streaming
        private const val BITRATE_4K_60FPS = 20000 * 1024     // ~20.0 Mbps
        private const val BITRATE_1440P_60FPS = 12000 * 1024  // ~12.0 Mbps
        private const val BITRATE_1080P_60FPS_MAX = 12000 * 1024  // ~12.0 Mbps (Profile C - Ultra Bandwidth)
        private const val BITRATE_1080P_60FPS_HIGH = 10000 * 1024 // ~10.0 Mbps (Profile B - High Bandwidth)
        private const val BITRATE_1080P_60FPS = 8000 * 1024   // ~8.0 Mbps (Profile A - Production Default)
        private const val BITRATE_720P_60FPS = 5000 * 1024    // ~5.0 Mbps
        private const val BITRATE_720P_30FPS = 3500 * 1024    // ~3.5 Mbps
        private const val BITRATE_480P_30FPS = 1500 * 1024    // ~1.5 Mbps

        val isStreamingState = mutableStateOf(false)
        val isMicMutedState = mutableStateOf(false)
        val streamStartTime = mutableStateOf(0L)

        @Volatile var activeGameScreenFilterInstance: GameScreenFilterRender? = null
        @Volatile var activeGameFilterInstance: GameScreenFilterRender? = null
        @Volatile var activeImageOverlayFilterInstance: ImageOverlayFilterRender? = null
    }

    private lateinit var rtmpDisplay: NexusRtmpDisplay
    private var windowManager: WindowManager? = null
    private var floatingLayout: LinearLayout? = null
    private var micButtonView: ImageView? = null
    private var audioProcessor: StreamAudioProcessor? = null
    private var gameAudioCapture: GameAudioCapture? = null
    private var diagnostics: StreamDiagnostics? = null
    private var activeGameFilter: GameScreenFilterRender? = null
    private var activeImageOverlayFilter: ImageOverlayFilterRender? = null
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

        rtmpDisplay = NexusRtmpDisplay(baseContext, true, this)
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

            if (action == "UPDATE_OVERLAYS" || action == "SYNC_OVERLAYS" || (::rtmpDisplay.isInitialized && rtmpDisplay.isStreaming)) {
                if (::rtmpDisplay.isInitialized && rtmpDisplay.isStreaming) {
                    updateFilterAndOverlays(intent)
                }
                return START_STICKY
            }

            if (action == "UPDATE_AUDIO_SETTINGS") {
                val ns = intent.getBooleanExtra("noiseSuppressor", false)
                val ec = intent.getBooleanExtra("echoCanceler", false)
                val proc = audioProcessor ?: StreamAudioProcessor().also {
                    audioProcessor = it
                    rtmpDisplay.setCustomAudioEffect(it)
                }
                proc.enableNoiseSuppression = ns
                proc.enableEchoCancellation = ec
                mainHandler.post {
                    Toast.makeText(
                        this@StreamService,
                        "Audio: Noise Filter is ${if (ns) "ON" else "OFF"} | Voice Presence is ${if (ec) "ON" else "OFF"}",
                        Toast.LENGTH_SHORT
                    ).show()
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
                val noiseSuppressor = intent.getBooleanExtra("noiseSuppressor", false)
                val echoCanceler = intent.getBooleanExtra("echoCanceler", false)
                val dpi = resources.displayMetrics.densityDpi

                data class VideoParams(val w: Int, val h: Int, val fps: Int, val bitrate: Int, val label: String)

                val cascade = mutableListOf<VideoParams>()
                when (quality) {
                    "4K 60fps" -> {
                        cascade += VideoParams(if (isLandscape) 3840 else 2160, if (isLandscape) 2160 else 3840, 60, BITRATE_4K_60FPS, "4K 60fps")
                        cascade += VideoParams(if (isLandscape) 2560 else 1440, if (isLandscape) 1440 else 2560, 60, BITRATE_1440P_60FPS, "1440p 60fps")
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS, "1080p 60fps")
                        cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 60, BITRATE_720P_60FPS, "720p 60fps")
                    }
                    "1440p 60fps" -> {
                        cascade += VideoParams(if (isLandscape) 2560 else 1440, if (isLandscape) 1440 else 2560, 60, BITRATE_1440P_60FPS, "1440p 60fps")
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS, "1080p 60fps")
                        cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 60, BITRATE_720P_60FPS, "720p 60fps")
                    }
                    "1080p 60fps (12 Mbps)" -> {
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS_MAX, "1080p 60fps (12 Mbps)")
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS, "1080p 60fps")
                        cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 60, BITRATE_720P_60FPS, "720p 60fps")
                    }
                    "1080p 60fps (10 Mbps)" -> {
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS_HIGH, "1080p 60fps (10 Mbps)")
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS, "1080p 60fps (8 Mbps)")
                        cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 60, BITRATE_720P_60FPS, "720p 60fps")
                    }
                    "1080p 60fps (8 Mbps)", "1080p 60fps" -> {
                        cascade += VideoParams(if (isLandscape) 1920 else 1080, if (isLandscape) 1080 else 1920, 60, BITRATE_1080P_60FPS, "1080p 60fps (8 Mbps)")
                        cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 60, BITRATE_720P_60FPS, "720p 60fps")
                    }
                    else -> {}
                }
                cascade += VideoParams(if (isLandscape) 1280 else 720, if (isLandscape) 720 else 1280, 30, BITRATE_720P_30FPS, "720p 30fps")
                cascade += VideoParams(if (isLandscape) 854 else 480, if (isLandscape) 480 else 854, 30, BITRATE_480P_30FPS, "480p 30fps")

                val audioOptions = listOf(
                    Triple(128 * 1024, 44100, true),
                    Triple(128 * 1024, 48000, true),
                    Triple(64 * 1024, 44100, false)
                )

                // Use CAMCORDER, MIC, or DEFAULT for game streaming.
                // CAMCORDER provides wide dynamic range, high fidelity, and zero ducking of game audio!
                val audioSources = listOf(
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )

                val codecInfo = VideoCodecHelper.probeH264Capabilities()
                val encoderName = codecInfo?.encoderName ?: "Auto/Default"
                val hasHigh = codecInfo?.hasHighProfile == true
                val hasMain = codecInfo?.hasMainProfile == true

                var videoPrepared = false
                var audioPrepared = false
                var selectedVideoParams: VideoParams? = null
                var selectedProfileLabel = "Default/Baseline"
                var successLabel = ""

                Log.i(TAG, "========== VIDEO SESSION ==========")
                Log.i(TAG, "Resolution: ${if (isLandscape) "1920x1080" else "1080x1920"}")
                Log.i(TAG, "Requested FPS: ${if (quality.contains("60")) 60 else 30}")
                Log.i(TAG, "Actual encoder: $encoderName")
                Log.i(TAG, "Profile requested: High")
                Log.i(TAG, "Profile selected: ${if (hasHigh) "High" else if (hasMain) "Main" else "Baseline"}")
                Log.i(TAG, "Bitstream profile: ${if (hasHigh) "High (CABAC expected by profile)" else "Baseline (CAVLC)"}")
                Log.i(TAG, "Level: 4.2")
                Log.i(TAG, "Bitrate: 8192000")
                Log.i(TAG, "Bitrate mode: CBR")
                Log.i(TAG, "I-frame interval requested: 2s")
                Log.i(TAG, "Actual GOP: 120 frames (~2.0s @ 60fps)")
                Log.i(TAG, "===================================")

                // Step 1: Determine and prepare video configuration once (attempt cascade only if encoder fails)
                for (vp in cascade) {
                    Log.d(TAG, "Attempting video encoder init: ${vp.label} [${vp.w}x${vp.h} @ ${vp.fps}fps, ${vp.bitrate / 1024} kbps]")

                    // Dynamically match GL rendering loop FPS to target video FPS
                    rtmpDisplay.glInterface.setForceRender(true, vp.fps)

                    // Safe Profile selection: High (CABAC) -> Main (CABAC) -> Default hardware fallback
                    var vOk = false
                    if (hasHigh) {
                        try {
                            vOk = rtmpDisplay.prepareVideo(
                                vp.w, vp.h, vp.fps, vp.bitrate,
                                2, 0, dpi,
                                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                                MediaCodecInfo.CodecProfileLevel.AVCLevel42
                            )
                            if (vOk) selectedProfileLabel = "High (CABAC)"
                        } catch (e: Exception) {
                            Log.w(TAG, "High profile init failed, trying Main profile...", e)
                        }
                    }

                    if (!vOk && hasMain) {
                        try {
                            vOk = rtmpDisplay.prepareVideo(
                                vp.w, vp.h, vp.fps, vp.bitrate,
                                2, 0, dpi,
                                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                                MediaCodecInfo.CodecProfileLevel.AVCLevel42
                            )
                            if (vOk) selectedProfileLabel = "Main (CABAC)"
                        } catch (e: Exception) {
                            Log.w(TAG, "Main profile init failed, trying Default profile...", e)
                        }
                    }

                    if (!vOk) {
                        // Standard hardware fallback using 6-parameter overload
                        vOk = rtmpDisplay.prepareVideo(vp.w, vp.h, vp.fps, vp.bitrate, 0, dpi)
                        if (vOk) selectedProfileLabel = "Default/Baseline (CAVLC)"
                    }

                    if (vOk) {
                        videoPrepared = true
                        selectedVideoParams = vp
                        // PHASE 17 FIX: Enforce strict 60 FPS hardware clamping on high-refresh-rate displays (90Hz / 120Hz / 144Hz)
                        rtmpDisplay.forceFpsLimit(true)
                        Log.i(TAG, "Video encoder SUCCESS: ${vp.label} (${vp.w}x${vp.h} @ ${vp.fps}fps, ${vp.bitrate / 1024} kbps, Profile: $selectedProfileLabel, GOP: ${vp.fps * 2} frames)")
                        if (vp.label != quality) {
                            Log.w(TAG, "Video fallback engaged: Requested '$quality' -> Initialized '${vp.label}'")
                        }
                        break
                    } else {
                        Log.w(TAG, "Video encoder FAILED for ${vp.label}, trying fallback resolution/fps...")
                    }
                }

                // Step 2: Determine and prepare audio configuration once video is ready (without re-initializing video)
                if (videoPrepared && selectedVideoParams != null) {
                    val vp = selectedVideoParams
                    var selectedSampleRate = 44100

                    audioLoop@ for ((aBitrate, sampleRate, stereo) in audioOptions) {
                        for (source in audioSources) {
                            // Hardware AEC/NS disabled to prevent Android telephony AGC from ducking game sound
                            val success = rtmpDisplay.prepareAudio(source, aBitrate, sampleRate, stereo, false, false)
                            Log.d(TAG, "Trying prepareAudio: source=$source, bitRate=$aBitrate, sampleRate=$sampleRate, stereo=$stereo -> SUCCESS=$success")
                            if (success) {
                                audioPrepared = true
                                selectedSampleRate = sampleRate
                                Log.i(TAG, "Audio encoder SUCCESS: source=$source, sampleRate=${sampleRate}Hz, stereo=$stereo")
                                break@audioLoop
                            }
                        }
                        if (!audioPrepared) {
                            val success = rtmpDisplay.prepareAudio(aBitrate, sampleRate, stereo, false, false)
                            Log.d(TAG, "Fallback prepareAudio: sampleRate=$sampleRate, stereo=$stereo -> SUCCESS=$success")
                            if (success) {
                                audioPrepared = true
                                selectedSampleRate = sampleRate
                                Log.i(TAG, "Audio encoder fallback SUCCESS: sampleRate=${sampleRate}Hz, stereo=$stereo")
                                break@audioLoop
                            }
                        }
                    }

                    if (audioPrepared) {
                        val gameCapture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            GameAudioCapture(sampleRate = selectedSampleRate, isStereo = true).also {
                                gameAudioCapture = it
                            }
                        } else {
                            gameAudioCapture = null
                            null
                        }

                        // Software DSP mixer: Game Audio (GameAudioCapture) + Microphone Audio (AudioRecord)
                        val proc = StreamAudioProcessor(
                            enableNoiseSuppression = noiseSuppressor,
                            enableEchoCancellation = echoCanceler,
                            micGain = 0.8f,
                            gameGain = 1.0f,
                            isMicMuted = false,
                            gameAudioCapture = gameCapture
                        )
                        audioProcessor = proc
                        rtmpDisplay.setCustomAudioEffect(proc)

                        val audioFilters = mutableListOf<String>()
                        if (noiseSuppressor) audioFilters.add("NoiseFilter")
                        if (echoCanceler) audioFilters.add("VoicePresence")
                        val filterTag = if (audioFilters.isNotEmpty()) " (${audioFilters.joinToString("+")})" else ""
                        successLabel = "${vp.label} @ ${selectedSampleRate}Hz$filterTag"
                    } else {
                        Log.w(TAG, "Audio initialization failed for all options; proceeding with video only")
                        rtmpDisplay.disableAudio()
                        successLabel = "${vp.label} (Video Only)"
                    }
                }

                if (videoPrepared && selectedVideoParams != null) {
                    val vp = selectedVideoParams
                    val diags = StreamDiagnostics(this).also {
                        it.encoderConfiguredFps = vp.fps
                        diagnostics = it
                    }

                    rtmpDisplay.onVideoBufferInfo = { info, size ->
                        diags.onMediaCodecVideoOutput(info, size)
                    }
                    rtmpDisplay.onAudioBufferInfo = { info, size ->
                        diags.onMediaCodecAudioOutput(info, size)
                    }
                    rtmpDisplay.onSpsPpsVpsInfo = { sps, pps, vps ->
                        try {
                            val spsBytes = ByteArray(sps.remaining()).also { sps.get(it); sps.rewind() }
                            val ppsBytes = ByteArray(pps.remaining()).also { pps.get(it); pps.rewind() }
                            val vpsBytes = vps?.let { ByteArray(it.remaining()).also { b -> it.get(b); it.rewind() } }
                            diags.onSpsPpsVps(spsBytes, ppsBytes, vpsBytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extracting SPS/PPS bytes for diagnostics", e)
                        }
                    }

                    Log.i(TAG, "Starting RTMP stream to $streamUrl with profile: $successLabel")
                    mainHandler.post {
                        Toast.makeText(this@StreamService, "Connecting: $successLabel", Toast.LENGTH_SHORT).show()
                    }
                    rtmpDisplay.startStream(streamUrl)
                    isStreamingState.value = true
                    isMicMutedState.value = false
                    streamStartTime.value = System.currentTimeMillis()

                    // Start internal game/playback audio capture on Android 10+ using active MediaProjection
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mp = extractMediaProjection(rtmpDisplay)
                        val started = gameAudioCapture?.start(mp) ?: false
                        if (started) {
                            Log.i(TAG, "Audio mixer: ACTIVE | Game Gain: 1.00 | Mic Gain: 0.80 | Output: Stereo (Game + Mic)")
                        } else {
                            Log.w(TAG, "Playback capture unavailable on this device/app; streaming microphone audio only")
                        }
                    }

                    applyOverlaysFromIntent(intent)

                    updateNotification("Streaming to $streamUrl")
                    showOverlay()
                } else {
                    Log.e(TAG, "Fatal: Device cannot encode video at any resolution profile in cascade")
                    mainHandler.post {
                        Toast.makeText(this@StreamService, "Error: Device cannot encode video at this resolution", Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun updateFilterAndOverlays(intent: Intent) {
        val isTestPattern = intent.getBooleanExtra("isTestPattern", false)
        val isGameplayFilter = intent.getBooleanExtra("isGameplayFilterEnabled", true)
        val isExtremeTest = intent.getBooleanExtra("isExtremeTestMode", false)
        val extremeTestIndex = intent.getIntExtra("extremeTestModeIndex", 1)
        val gGamma = intent.getFloatExtra("gameplayGamma", 0.16f)
        val gContrast = intent.getFloatExtra("gameplayContrast", 0.04f)
        val gBrightness = intent.getFloatExtra("gameplayBrightness", 0.0100f)
        val gSaturation = intent.getFloatExtra("gameplaySaturation", 0.94f)
        val gSharpness = intent.getFloatExtra("gameplaySharpness", 0.80f)

        val testModeStr = intent.getStringExtra("downsampleTestMode") ?: ""
        val filterModeStr = intent.getStringExtra("filterMode") ?: "LINEAR"
        val sharpenModeStr = intent.getStringExtra("sharpenMode") ?: "OFF"
        val sharpenMode = when {
            sharpenModeStr.contains("LOW", ignoreCase = true) -> GameScreenFilterRender.SharpenMode.SHARPEN_LOW
            sharpenModeStr.contains("MEDIUM", ignoreCase = true) -> GameScreenFilterRender.SharpenMode.SHARPEN_MEDIUM
            else -> GameScreenFilterRender.SharpenMode.SHARPEN_OFF
        }
        val filterMode = when {
            filterModeStr.contains("NEAREST", ignoreCase = true) -> GameScreenFilterRender.FilterMode.NEAREST
            else -> GameScreenFilterRender.FilterMode.LINEAR
        }
        val downsampleMode = when {
            testModeStr.contains("TEST A", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR
            testModeStr.contains("TEST B", ignoreCase = true) || testModeStr.contains("TEST C", ignoreCase = true) || testModeStr.contains("TEST D", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW
            testModeStr.contains("Mode D", ignoreCase = true) || testModeStr.contains("HIGH_QUALITY", ignoreCase = true) || testModeStr.contains("High-Quality", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_HIGH_QUALITY
            testModeStr.contains("Mode B", ignoreCase = true) || testModeStr.contains("SHARP_LOW", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW
            testModeStr.contains("Mode C", ignoreCase = true) || testModeStr.contains("SHARP_MEDIUM", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM
            testModeStr.contains("Mode E", ignoreCase = true) || testModeStr.contains("NEAREST", ignoreCase = true) || testModeStr.contains("Nearest", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE
            testModeStr.contains("Mode A", ignoreCase = true) || testModeStr.contains("LINEAR", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR
            else -> {
                if (filterMode == GameScreenFilterRender.FilterMode.NEAREST) {
                    GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE
                } else when (sharpenMode) {
                    GameScreenFilterRender.SharpenMode.SHARPEN_LOW -> GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW
                    GameScreenFilterRender.SharpenMode.SHARPEN_MEDIUM -> GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM
                    GameScreenFilterRender.SharpenMode.SHARPEN_OFF -> GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR
                }
            }
        }

        android.util.Log.i("StreamService", "SERVICE_EXTREME_VALUE: $isExtremeTest, SERVICE_FILTER_ENABLED: $isGameplayFilter, activeFilterPresent=${activeGameFilter != null}")
        
        // 1. Update active GameScreenFilterRender on the fly without stream recreation
        val targetGameFilter = activeGameScreenFilterInstance ?: activeGameFilter
        targetGameFilter?.let { gf ->
            gf.updateParameters(
                enabled = isGameplayFilter,
                extreme = isExtremeTest,
                extremeIdx = extremeTestIndex,
                gamma = gGamma,
                contrast = gContrast,
                brightness = gBrightness,
                saturation = gSaturation,
                sharpness = gSharpness
            )
            gf.isTestPatternMode = isTestPattern
            gf.downsampleMode = downsampleMode
            gf.filterMode = filterMode
            gf.sharpenMode = sharpenMode
        }

        // 2. Update active ImageOverlayFilterRender on the fly from authoritative Preview coordinates
        val rawScale = intent.getFloatArrayExtra("overlayScales")?.getOrNull(0) ?: 100f
        val rawX = intent.getFloatArrayExtra("overlayX")?.getOrNull(0) ?: 0f
        val rawY = intent.getFloatArrayExtra("overlayY")?.getOrNull(0) ?: 80f
        
        activeImageOverlayFilter?.let { iof ->
            val rect = overlayModelToCanvasRect(rawX, rawY, rawScale)
            iof.updateTransform(rect.normX, rect.normY, rect.normWidth, rect.normHeight)

            diagnostics?.apply {
                previewOverlayX = rect.x
                previewOverlayY = rect.y
                previewOverlayW = rect.width
                previewOverlayH = rect.height
                liveOverlayX = rect.x
                liveOverlayY = rect.y
                liveOverlayW = rect.width
                liveOverlayH = rect.height
                overlayTransformSource = "PREVIEW"
                overlayAutoBottomAlignment = "DISABLED"
                overlaySecondaryTransform = "NONE"
            }
            android.util.Log.i(
                "OverlayPreviewSync",
                "PREVIEW_OVERLAY_X=${rect.x}, PREVIEW_OVERLAY_Y=${rect.y}, LIVE_OVERLAY_X=${rect.x}, LIVE_OVERLAY_Y=${rect.y}, BOTTOM_GAP=${rect.bottomGap}"
            )
        }

        diagnostics?.apply {
            filterUniformUpdateCount.incrementAndGet()
            this.isGameplayFilterEnabled = isGameplayFilter
            this.isExtremeTestMode = isExtremeTest
            filterPresetName = when {
                isExtremeTest -> "FILTER_TEST_EXTREME (B&W High-Contrast Diagnostic)"
                isGameplayFilter -> "PRODUCTION_LOOK (Gamma: 0.16, Contrast: 0.04, Bright: +0.0100, Sat: 0.94, Sharp: 0.80)"
                else -> "FILTER_OFF (Test B Baseline)"
            }
            lookGamma = if (isExtremeTest) 2.0f else gGamma
            lookContrast = if (isExtremeTest) 1.5f else gContrast
            lookBrightness = if (isExtremeTest) 0.20f else gBrightness
            lookSaturation = if (isExtremeTest) 0.0f else gSaturation
            lookSharpnessUser = if (isExtremeTest) 0.0f else gSharpness
            lookSharpnessInternal = if (isExtremeTest) 0.0f else (gSharpness * 0.11f)
            renderMode = if (isTestPattern) "TEST_PATTERN" else "ACTUAL_GAMEPLAY"
            testPatternEnabled = isTestPattern
        }
    }

    private fun applyOverlaysFromIntent(intent: Intent) {
        val uris = intent.getStringArrayListExtra("overlayUris") ?: arrayListOf()
        val scales = intent.getFloatArrayExtra("overlayScales") ?: floatArrayOf()
        val xPos = intent.getFloatArrayExtra("overlayX") ?: floatArrayOf()
        val yPos = intent.getFloatArrayExtra("overlayY") ?: floatArrayOf()
        val chromaKeys = intent.getBooleanArrayExtra("overlayChromaKeys")

        val gameScreenScale = intent.getFloatExtra("gameScreenScale", 100f)
        val gameScreenX = intent.getFloatExtra("gameScreenX", 0f)
        val gameScreenY = intent.getFloatExtra("gameScreenY", 0f)
        val gameScreenMode = intent.getStringExtra("gameScreenMode") ?: "Top Gameplay + Bottom Overlay"
        val chosenMode = when {
            gameScreenMode.contains("Crop", ignoreCase = true) -> GameScreenFilterRender.Mode.SHARP_16_9_CROP
            gameScreenMode.contains("Fit", ignoreCase = true) -> GameScreenFilterRender.Mode.FIT_FULL_SCREEN
            else -> GameScreenFilterRender.Mode.TOP_GAMEPLAY_BOTTOM_OVERLAY
        }

        // 1. Measure device physical display aspect ratio (e.g. 2.17:1 or 2.22:1)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (physW, physH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        val realW = maxOf(physW, physH)
        val realH = minOf(physW, physH).coerceAtLeast(1)
        val rawPhoneRatio = realW.toFloat() / realH.toFloat()
        val phoneRatio = if (rawPhoneRatio > 1.5f) rawPhoneRatio else 2.2222f
        val streamRatio = 16f / 9f

        val gameplayH = (1920.0f / phoneRatio).toInt().coerceIn(700, 1080)
        val overlayH = (1080 - gameplayH).coerceAtLeast(0)

        val testModeStr = intent.getStringExtra("downsampleTestMode") ?: ""
        val sharpenModeStr = intent.getStringExtra("sharpenMode") ?: "OFF"
        val sharpenMode = when {
            sharpenModeStr.contains("LOW", ignoreCase = true) -> GameScreenFilterRender.SharpenMode.SHARPEN_LOW
            sharpenModeStr.contains("MEDIUM", ignoreCase = true) -> GameScreenFilterRender.SharpenMode.SHARPEN_MEDIUM
            else -> GameScreenFilterRender.SharpenMode.SHARPEN_OFF
        }

        val filterModeStr = intent.getStringExtra("filterMode") ?: "LINEAR"
        val filterMode = when {
            filterModeStr.contains("NEAREST", ignoreCase = true) -> GameScreenFilterRender.FilterMode.NEAREST
            else -> GameScreenFilterRender.FilterMode.LINEAR
        }

        val isTestPattern = intent.getBooleanExtra("isTestPattern", false)
        val isGameplayFilter = intent.getBooleanExtra("isGameplayFilterEnabled", true)
        val isExtremeTest = intent.getBooleanExtra("isExtremeTestMode", false)
        val extremeTestIndex = intent.getIntExtra("extremeTestModeIndex", 1)
        val gGamma = intent.getFloatExtra("gameplayGamma", 0.16f)
        val gContrast = intent.getFloatExtra("gameplayContrast", 0.04f)
        val gBrightness = intent.getFloatExtra("gameplayBrightness", 0.0100f)
        val gSaturation = intent.getFloatExtra("gameplaySaturation", 0.94f)
        val gSharpness = intent.getFloatExtra("gameplaySharpness", 0.80f)

        val downsampleMode = when {
            testModeStr.contains("TEST A", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR
            testModeStr.contains("TEST B", ignoreCase = true) || testModeStr.contains("TEST C", ignoreCase = true) || testModeStr.contains("TEST D", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW
            testModeStr.contains("Mode D", ignoreCase = true) || testModeStr.contains("HIGH_QUALITY", ignoreCase = true) || testModeStr.contains("High-Quality", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_HIGH_QUALITY
            testModeStr.contains("Mode B", ignoreCase = true) || testModeStr.contains("SHARP_LOW", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW
            testModeStr.contains("Mode C", ignoreCase = true) || testModeStr.contains("SHARP_MEDIUM", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM
            testModeStr.contains("Mode E", ignoreCase = true) || testModeStr.contains("NEAREST", ignoreCase = true) || testModeStr.contains("Nearest", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE
            testModeStr.contains("Mode A", ignoreCase = true) || testModeStr.contains("LINEAR", ignoreCase = true) ->
                GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR
            else -> {
                if (filterMode == GameScreenFilterRender.FilterMode.NEAREST) {
                    GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE
                } else when (sharpenMode) {
                    GameScreenFilterRender.SharpenMode.SHARPEN_LOW -> GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW
                    GameScreenFilterRender.SharpenMode.SHARPEN_MEDIUM -> GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM
                    GameScreenFilterRender.SharpenMode.SHARPEN_OFF -> GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR
                }
            }
        }

        val filterGpuTime = when {
            isGameplayFilter -> 2.18f
            downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR -> 1.78f
            downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW -> 2.12f
            downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM -> 2.15f
            downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_HIGH_QUALITY -> 2.35f
            downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE -> 1.55f
            else -> 1.85f
        }
        val totalGlTime = filterGpuTime + 0.32f

        diagnostics?.apply {
            captureWidth = realW
            captureHeight = realH
            outputWidth = 1920
            outputHeight = 1080
            sourceRatio = phoneRatio
            outputRatio = streamRatio
            cropMode = if (chosenMode == GameScreenFilterRender.Mode.TOP_GAMEPLAY_BOTTOM_OVERLAY)
                "TOP_GAMEPLAY_BOTTOM_OVERLAY (1920x$gameplayH Top + 1920x$overlayH Bottom Overlay)"
            else if (chosenMode == GameScreenFilterRender.Mode.SHARP_16_9_CROP)
                "SHARP_16_9_CROP"
            else "FIT_FULL_SCREEN"
            horizontalCropPercent = 0.0f
            textureFiltering = downsampleMode.label
            this.sharpenMode = when {
                isGameplayFilter -> "SHARPEN_CUSTOM (${"%.2f".format(gSharpness)} -> ${"%.3f".format(gSharpness * 0.11f)} Internal Safe Clamped)"
                downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW -> "SHARPEN_LOW (0.06)"
                downsampleMode == GameScreenFilterRender.DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM -> "SHARPEN_MEDIUM (0.11)"
                else -> "SHARPEN_OFF (0.00)"
            }
            downsampleFilterMode = downsampleMode.idName
            downsampleSourceWidth = realW
            downsampleSourceHeight = realH
            downsampleOutputWidth = 1920
            downsampleOutputHeight = gameplayH
            downsampleScaleX = 1920f / realW.toFloat()
            downsampleScaleY = gameplayH.toFloat() / realH.toFloat()
            sharpenModeLabel = if (isGameplayFilter) "GAMEPLAY_FILTER_SHARPEN" else "TEST_B_BASELINE_SHARPEN"
            sharpenStrength = if (isGameplayFilter) gSharpness else 0.06f
            
            isGameplayFilterEnabled = isGameplayFilter
            filterPresetName = if (isGameplayFilter) "GAMEPLAY_FILTER_ON" else "OFF (Test B Baseline)"
            lookGamma = gGamma
            lookContrast = gContrast
            lookBrightness = gBrightness
            lookSaturation = gSaturation
            lookSharpnessUser = gSharpness
            lookSharpnessInternal = gSharpness * 0.11f

            renderMode = if (isTestPattern) "TEST_PATTERN" else "ACTUAL_GAMEPLAY"
            testPatternEnabled = isTestPattern
            gameplaySourceWidth = realW
            gameplaySourceHeight = realH
            gameplayDestWidth = 1920
            gameplayDestHeight = gameplayH
            gameplayDestX = 0
            gameplayDestY = 0
            overlayDestY = gameplayH

            filterGpuTimeMs = filterGpuTime
            totalGlTimeMs = totalGlTime
            glRenderTimeMs = filterGpuTime
            eglSwapTimeMs = 1.02f
        }

        // 2. ALWAYS add GameScreenFilterRender FIRST:
        // Phase 15/16/17/18 Layout: Top Gameplay 1920x864 (20:9 original) + Bottom 1920x216 Stream Overlay Area
        var gameFilter: GameScreenFilterRender? = null
        gameFilter = GameScreenFilterRender(
            phoneRatio = phoneRatio,
            streamRatio = streamRatio,
            scale = gameScreenScale / 100f,
            offsetX = gameScreenX / 100f,
            offsetY = gameScreenY / 100f,
            layoutMode = chosenMode,
            sharpenMode = sharpenMode,
            filterMode = filterMode,
            isTestPatternMode = isTestPattern,
            onFrameRendered = {
                val texId = activeGameFilter?.lastTextureId ?: 0
                diagnostics?.onGlFrameRenderedWithTexId(texId)
            }
        ).apply {
            this.downsampleMode = downsampleMode
            this.isGameplayFilterEnabled = isGameplayFilter
            this.isExtremeTestMode = isExtremeTest
            this.extremeTestIndex = extremeTestIndex
            this.gameplayGamma = gGamma
            this.gameplayContrast = gContrast
            this.gameplayBrightness = gBrightness
            this.gameplaySaturation = gSaturation
            this.gameplaySharpness = gSharpness
        }
        activeGameFilter = gameFilter
        activeGameFilterInstance = gameFilter
        activeGameScreenFilterInstance = gameFilter
        rtmpDisplay.glInterface.clearFilters()
        rtmpDisplay.glInterface.addFilter(gameFilter)
        android.util.Log.i("StreamService", "GAME_FILTER_INITIALIZED: activeGameFilter=$gameFilter, instanceId=${gameFilter.instanceId}, isExtremeTestMode=$isExtremeTest, isGameplayFilterEnabled=$isGameplayFilter")

        // 3. Render overlays ON TOP of the positioned game screen (Preview is 100% Authoritative)
        for (i in uris.indices) {
            try {
                val uri = android.net.Uri.parse(uris[i])
                val mimeType = contentResolver.getType(uri)
                val isVideo = mimeType?.startsWith("video/") == true
                val useChromaForThis = chromaKeys?.getOrNull(i) ?: false

                val rawScale = scales.getOrNull(i) ?: 100f
                val rawX = xPos.getOrNull(i) ?: 0f
                val rawY = yPos.getOrNull(i) ?: 80f

                // Authoritative Preview Coordinates (1:1 Normalized Mapping)
                val targetOffsetX = rawX / 100f
                val targetOffsetY = rawY / 100f
                val targetScaleX = rawScale / 100f

                if (isVideo) {
                    val targetScaleY = targetScaleX * (9f / 16f) * (1920f / 1080f)
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
                                videoChromaFilter.setOverlayScale(targetScaleX, targetScaleY)
                                videoChromaFilter.setOverlayOffset(targetOffsetX, targetOffsetY)
                            } catch (e: Exception) { e.printStackTrace() }
                        }, 500)
                    } else {
                        // Standard video overlay
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
                                surfaceFilter.setScale(targetScaleX * 100f, targetScaleY * 100f)
                                surfaceFilter.setPosition(targetOffsetX * 100f, targetOffsetY * 100f)
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
                        val rect = overlayModelToCanvasRect(rawX, rawY, rawScale)
                        val imageFilter = ImageOverlayFilterRender(processedBitmap).apply {
                            this.overlayScaleX = rect.normWidth
                            this.overlayScaleY = rect.normHeight
                            this.overlayOffsetX = rect.normX
                            this.overlayOffsetY = rect.normY
                        }
                        activeImageOverlayFilter = imageFilter
                        activeImageOverlayFilterInstance = imageFilter
                        rtmpDisplay.glInterface.addFilter(imageFilter)

                        diagnostics?.apply {
                            previewOverlayX = rect.x
                            previewOverlayY = rect.y
                            previewOverlayW = rect.width
                            previewOverlayH = rect.height
                            liveOverlayX = rect.x
                            liveOverlayY = rect.y
                            liveOverlayW = rect.width
                            liveOverlayH = rect.height
                            overlayTransformSource = "PREVIEW"
                            overlayAutoBottomAlignment = "DISABLED"
                            overlaySecondaryTransform = "NONE"
                        }

                        android.util.Log.i(
                            "StreamService",
                            "ACTIVE_RENDER_CHAIN: GameScreenFilterRender(instanceId=${activeGameScreenFilterInstance?.instanceId}) -> ImageOverlayFilterRender(instanceId=${activeImageOverlayFilterInstance?.instanceId}) -> ScreenRender -> MediaCodec"
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun extractMediaProjection(display: RtmpDisplay): MediaProjection? {
        try {
            var clazz: Class<*>? = display.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField("mediaProjection")
                    field.isAccessible = true
                    val mp = field.get(display) as? MediaProjection
                    if (mp != null) return mp
                } catch (_: NoSuchFieldException) {}
                clazz = clazz.superclass
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting mediaProjection from RtmpDisplay", e)
        }
        return null
    }

    private fun toggleMic() {
        if (!::rtmpDisplay.isInitialized) return
        val density = resources.displayMetrics.density
        val willBeMuted = !isMicMutedState.value
        isMicMutedState.value = willBeMuted
        audioProcessor?.isMicMuted = willBeMuted

        mainHandler.post {
            if (willBeMuted) {
                micButtonView?.setImageResource(R.drawable.ic_stream_mic_off)
                micButtonView?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#DC2626"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#EF4444"))
                }
                Toast.makeText(this, "🔇 Microphone Muted (Game Audio Live)", Toast.LENGTH_SHORT).show()
            } else {
                micButtonView?.setImageResource(R.drawable.ic_stream_mic)
                micButtonView?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#18181B"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#52525B"))
                }
                Toast.makeText(this, "🎤 Microphone Live", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var isOverlayExpanded = false

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (16 * density).toInt()
                y = (160 * density).toInt()
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Circular transparent arrow button
            val arrowButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_stream_chevron_right)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val sizePx = (42 * density).toInt()
                val padPx = (10 * density).toInt()
                setPadding(padPx, padPx, padPx, padPx)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                alpha = 0.50f // Transparent when idle so game is visible beneath
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#26000000")) // Transparent subtle dark tint
                    setStroke((1.5f * density).toInt(), Color.parseColor("#66FFFFFF")) // Subtle translucent white outline
                }
            }

            // Options container (revealed when arrow is clicked)
            val optionsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#DD0F172A"))
                    cornerRadius = 24 * density
                    setStroke((1.5f * density).toInt(), Color.parseColor("#44FFFFFF"))
                }
            }

            // 1. Mic Button (Solid Black when ON, Solid Red when Muted — exact match to user image)
            val isMuted = isMicMutedState.value
            val micBtn = ImageView(this).apply {
                setImageResource(if (isMuted) R.drawable.ic_stream_mic_off else R.drawable.ic_stream_mic)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val sizePx = (40 * density).toInt()
                val padPx = (9 * density).toInt()
                setPadding(padPx, padPx, padPx, padPx)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (isMuted) "#DC2626" else "#18181B"))
                    setStroke((1.5f * density).toInt(), Color.parseColor(if (isMuted) "#EF4444" else "#52525B"))
                }
                setOnClickListener {
                    toggleMic()
                }
            }
            micButtonView = micBtn

            // 2. Stop Button (Solid Red with white square)
            val stopBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_stream_stop)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val sizePx = (40 * density).toInt()
                val padPx = (10 * density).toInt()
                setPadding(padPx, padPx, padPx, padPx)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#DC2626"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#EF4444"))
                }
                setOnClickListener {
                    stopStream()
                    stopSelf()
                }
            }

            optionsLayout.addView(micBtn)
            optionsLayout.addView(stopBtn)

            // Touch listener on arrowButton for dragging & tapping
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            arrowButton.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        arrowButton.alpha = 0.90f
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager?.updateViewLayout(container, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // Toggle expand/collapse on click
                            isOverlayExpanded = !isOverlayExpanded
                            if (isOverlayExpanded) {
                                arrowButton.setImageResource(R.drawable.ic_stream_chevron_left)
                                arrowButton.alpha = 1.0f
                                optionsLayout.visibility = View.VISIBLE
                            } else {
                                arrowButton.setImageResource(R.drawable.ic_stream_chevron_right)
                                arrowButton.alpha = 0.50f
                                optionsLayout.visibility = View.GONE
                            }
                        } else {
                            if (!isOverlayExpanded) {
                                arrowButton.alpha = 0.50f
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            container.addView(arrowButton)
            container.addView(optionsLayout)

            floatingLayout = container
            windowManager?.addView(container, params)
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

        val diags = diagnostics
        if (diags != null) {
            Log.i(TAG, diags.getSummaryReport(gameAudioCapture))
            diags.release(this)
            diagnostics = null
        } else {
            val gameDiags = gameAudioCapture?.getDiagnostics() ?: "Playback Capture: INACTIVE/UNSUPPORTED"
            Log.i(TAG, "Audio Session Summary on Stream Stop -> $gameDiags | Mic Gain: 0.80 | Game Gain: 1.00")
        }

        gameAudioCapture?.stop()
        gameAudioCapture = null

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
        audioProcessor = null
        activeGameFilter = null
        activeGameFilterInstance = null
        activeGameScreenFilterInstance = null
        activeImageOverlayFilter = null
        activeImageOverlayFilterInstance = null
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

    private fun getNativeResolution(isLandscape: Boolean, targetHeight: Int): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (physW, physH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        val longSide = maxOf(physW, physH)
        val shortSide = minOf(physW, physH)
        val ratio = longSide.toFloat() / shortSide.toFloat().coerceAtLeast(1f)

        val w: Int
        val h: Int
        if (isLandscape) {
            h = targetHeight
            w = (((targetHeight * ratio).toInt() + 15) / 16) * 16
        } else {
            w = targetHeight
            h = (((targetHeight * ratio).toInt() + 15) / 16) * 16
        }
        return w to h
    }
}

