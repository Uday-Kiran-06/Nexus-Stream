package com.example.bgmistreamer

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GameAudioCapture:
 * Native Android 10+ (API 29+) internal system & game playback audio capture.
 *
 * Uses AudioPlaybackCaptureConfiguration with the active MediaProjection to capture
 * pristine digital internal game sound (even when headphones are connected).
 * Captured PCM samples are pushed to a thread-safe FIFO buffer to be mixed with
 * microphone audio in StreamAudioProcessor.
 */
class GameAudioCapture(
    val sampleRate: Int = 44100,
    val isStereo: Boolean = true
) {
    companion object {
        private const val TAG = "GameAudioCapture"
        private const val MAX_BUFFERED_CHUNKS = 12 // ~250ms maximum buffer to prevent latency accumulation
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Thread-safe FIFO queue of raw PCM byte chunks
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()

    // Real-time diagnostic tracking
    val underrunCount = AtomicLong(0L)
    val overflowCount = AtomicLong(0L)
    val capturedChunksCount = AtomicLong(0L)

    val isCapturing: Boolean
        get() = isRunning.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @SuppressLint("MissingPermission")
    fun start(mediaProjection: MediaProjection?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Playback capture unavailable: Requires Android 10+ (Current API: ${Build.VERSION.SDK_INT})")
            return false
        }
        if (mediaProjection == null) {
            Log.w(TAG, "Playback capture unavailable: MediaProjection is null")
            return false
        }

        stop()

        try {
            val channelMask = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            val bufferSize = maxOf(minBufSize * 2, 4096)

            // Strictly USAGE_GAME and USAGE_MEDIA (USAGE_UNKNOWN removed)
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Game AudioRecord initialization failed")
                record.release()
                return false
            }

            record.startRecording()
            audioRecord = record
            isRunning.set(true)
            underrunCount.set(0L)
            overflowCount.set(0L)
            capturedChunksCount.set(0L)

            Log.i(TAG, "Playback capture: AVAILABLE | Game audio: $sampleRate Hz / ${if (isStereo) "stereo" else "mono"}")

            captureThread = Thread({
                val readBuffer = ByteArray(bufferSize / 2)
                while (isRunning.get()) {
                    val readBytes = record.read(readBuffer, 0, readBuffer.size)
                    if (readBytes > 0) {
                        val chunk = readBuffer.copyOf(readBytes)
                        pcmQueue.offer(chunk)
                        capturedChunksCount.incrementAndGet()
                        while (pcmQueue.size > MAX_BUFFERED_CHUNKS) {
                            pcmQueue.poll() // Drop oldest chunk if mixer consumer is lagging
                            overflowCount.incrementAndGet()
                        }
                    } else if (readBytes == AudioRecord.ERROR_INVALID_OPERATION || readBytes == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "Game AudioRecord read error: $readBytes")
                        break
                    }
                }
            }, "GameAudioCaptureThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting game audio playback capture", e)
            stop()
            return false
        }
    }

    /**
     * Polls the next chunk of game audio PCM bytes.
     */
    fun pollChunk(): ByteArray? {
        val chunk = pcmQueue.poll()
        if (chunk == null && isRunning.get()) {
            underrunCount.incrementAndGet()
        }
        return chunk
    }

    fun getDiagnostics(): String {
        return "Game Capture: ${if (isCapturing) "ACTIVE" else "INACTIVE"} | Captured Chunks: ${capturedChunksCount.get()} | Underruns: ${underrunCount.get()} | Overflows: ${overflowCount.get()} | Queue Size: ${pcmQueue.size}"
    }

    fun stop() {
        isRunning.set(false)
        try {
            captureThread?.interrupt()
            captureThread?.join(500)
        } catch (_: Exception) {}
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        pcmQueue.clear()
        Log.i(TAG, "Game audio capture stopped & released. Final stats: ${getDiagnostics()}")
    }
}

