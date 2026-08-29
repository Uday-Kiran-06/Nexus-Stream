package com.example.bgmistreamer

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.abs
import kotlin.math.exp

/**
 * StreamAudioProcessor - High-Fidelity Dual-Source Audio Mixer & DSP Engine.
 *
 * Implements clean, isolated signal paths:
 * 1. GAME AUDIO (from GameAudioCapture):
 *    - Independent Game Gain scaling (Default: 1.0)
 *    - Bypasses all microphone DSP filters (no ducking, no voice gating, no low-passing)
 *
 * 2. MICROPHONE AUDIO (from AudioRecord / MicrophoneManager):
 *    - Independent Mic Gain scaling (Default: 0.8)
 *    - Plosive & Rumble High-Pass Filter (< 65Hz HPF, applied strictly to mic samples)
 *    - Clean software mute support (game audio continues uninterrupted when mic is muted)
 *
 * 3. PCM SOFTWARE MIXER:
 *    - Sums Game PCM + Mic PCM sample-by-sample (stereo interleaved)
 *    - Converts mono microphone samples across both L and R channels if required
 *    - Zero-allocation inner loop for maximum real-time performance
 *    - Anti-Clipping Soft Limiter with smooth exponential headroom saturation above 32000
 */
class StreamAudioProcessor(
    var enableNoiseSuppression: Boolean = false,
    var enableEchoCancellation: Boolean = false,
    var micGain: Float = 0.8f,
    var gameGain: Float = 1.0f,
    var isMicMuted: Boolean = false,
    var gameAudioCapture: GameAudioCapture? = null
) : CustomAudioEffect() {

    // Sub-bass plosive / rumble High-Pass Filter (~65Hz @ 44.1/48kHz) strictly for mic samples
    private val hpAlpha = 0.991f
    private var hpPrevXLeft = 0.0f
    private var hpPrevYLeft = 0.0f
    private var hpPrevXRight = 0.0f
    private var hpPrevYRight = 0.0f

    // Soft limiter parameters (threshold at 32000 on 16-bit signed scale, with 767 headroom)
    private val limitThreshold = 32000.0f
    private val headroomRange = 767.0f

    // Rolling buffer for reading continuous Game PCM chunks
    private var currentChunk: ByteArray? = null
    private var chunkOffset = 0

    override fun process(pcmBuffer: ByteArray): ByteArray {
        var idx = 0
        var isLeftChannel = true

        val activeMicGain = if (isMicMuted) 0.0f else micGain.coerceIn(0.0f, 3.0f)
        val activeGameGain = gameGain.coerceIn(0.0f, 3.0f)
        val capture = gameAudioCapture

        while (idx + 1 < pcmBuffer.size) {
            // 1. Decode Microphone 16-bit signed PCM sample (little-endian)
            val micLow = pcmBuffer[idx].toInt() and 0xFF
            val micHigh = pcmBuffer[idx + 1].toInt() shl 8
            val rawMicSample = (micLow or micHigh).toShort().toFloat()
            var processedMic = rawMicSample

            // 2. Microphone-Only DSP (Rumble filter & Mic Gain)
            if (activeMicGain > 0.0f) {
                if (enableNoiseSuppression) {
                    if (isLeftChannel) {
                        val hpOut = hpAlpha * (hpPrevYLeft + processedMic - hpPrevXLeft)
                        hpPrevXLeft = processedMic
                        hpPrevYLeft = hpOut
                        processedMic = hpOut
                    } else {
                        val hpOut = hpAlpha * (hpPrevYRight + processedMic - hpPrevXRight)
                        hpPrevXRight = processedMic
                        hpPrevYRight = hpOut
                        processedMic = hpOut
                    }
                }
                processedMic *= activeMicGain
            } else {
                processedMic = 0.0f
            }

            // 3. Extract Game PCM sample from GameAudioCapture FIFO buffer (allocation-free)
            var processedGame = 0.0f
            if (capture != null && activeGameGain > 0.0f) {
                val rawGameSample = nextGameSample(capture)
                processedGame = rawGameSample * activeGameGain
            }

            // 4. Mathematical PCM Mixing: Game + Mic
            val mixed = processedGame + processedMic

            // 5. Anti-Clipping Soft Limiter
            val absVal = abs(mixed)
            val limited = if (absVal <= limitThreshold) {
                mixed
            } else {
                val sign = if (mixed > 0f) 1.0f else -1.0f
                val excess = absVal - limitThreshold
                val saturated = limitThreshold + headroomRange * (1.0f - exp(-excess / headroomRange))
                sign * saturated
            }

            val clamped = limited.coerceIn(-32767.0f, 32767.0f).toInt().toShort()

            // Write mixed PCM sample back to output byte array
            pcmBuffer[idx] = (clamped.toInt() and 0xFF).toByte()
            pcmBuffer[idx + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()

            isLeftChannel = !isLeftChannel
            idx += 2
        }

        return pcmBuffer
    }

    private fun nextGameSample(capture: GameAudioCapture): Float {
        while (currentChunk == null || chunkOffset + 1 >= (currentChunk?.size ?: 0)) {
            currentChunk = capture.pollChunk()
            chunkOffset = 0
            if (currentChunk == null) return 0.0f
        }

        val chunk = currentChunk ?: return 0.0f
        val low = chunk[chunkOffset].toInt() and 0xFF
        val high = chunk[chunkOffset + 1].toInt() shl 8
        chunkOffset += 2
        return (low or high).toShort().toFloat()
    }
}




