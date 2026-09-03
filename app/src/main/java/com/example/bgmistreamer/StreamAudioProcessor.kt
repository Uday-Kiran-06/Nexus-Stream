package com.example.bgmistreamer

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * StreamAudioProcessor - High-Fidelity Dual-Source Audio Mixer & DSP Engine.
 *
 * Implements clean, isolated signal paths:
 * 1. GAME AUDIO (from GameAudioCapture):
 *    - Independent Game Gain scaling (Default: 1.0)
 *    - Bypasses all microphone DSP filters (no ducking, no voice gating, no low-passing)
 *
 * 2. MICROPHONE AUDIO (from AudioRecord / MicrophoneManager):
 *    - Independent Mic Gain scaling (Default: 0.8 / 80%, range: 0.0x..2.0x)
 *    - Plosive & Rumble High-Pass Filter (< 65Hz HPF, applied strictly to mic samples)
 *    - Real-Time Voice Compressor (Threshold: ~ -18 dBFS, Ratio: 3:1, Attack: 5ms, Release: 100ms)
 *    - Controlled Voice Makeup Gain (~ +11 dB / 3.5x linear)
 *    - Clean software mute support (game audio continues uninterrupted when mic is muted)
 *
 * 3. PCM SOFTWARE MIXER:
 *    - Sums Game PCM + Mic PCM sample-by-sample (stereo interleaved)
 *    - Converts mono microphone samples across both L and R channels if required
 *    - Zero-allocation inner loop for maximum real-time performance
 *    - Anti-Clipping Soft Limiter with smooth exponential headroom saturation above 32000
 *    - Periodic Level Diagnostics (raw & processed Peak/RMS in dBFS)
 */
class StreamAudioProcessor(
    var enableNoiseSuppression: Boolean = false,
    var enableEchoCancellation: Boolean = false,
    @Volatile var micGain: Float = 0.8f,
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

    // Voice Compressor parameters:
    // Threshold: -18 dBFS on 16-bit PCM scale (32767 * 10^(-18/20) ≈ 4115.0)
    // Ratio: 3:1 (gain reduction above threshold = (env / threshold)^(-2/3))
    // Attack: 5 ms (alpha ≈ 0.0045 @ 44.1k/48k)
    // Release: 100 ms (alpha ≈ 0.00022 @ 44.1k/48k)
    // Makeup Gain: 3.5x (~ +10.9 dB)
    private val compThreshold = 4115.0f
    private val compAlphaAtt = 0.0045f
    private val compAlphaRel = 0.00022f
    private val compMakeupGain = 3.5f
    private var compEnv = 0.0f

    // Soft limiter parameters (threshold at 32000 on 16-bit signed scale, with 767 headroom)
    private val limitThreshold = 32000.0f
    private val headroomRange = 767.0f

    // Rolling buffer for reading continuous Game PCM chunks
    private var currentChunk: ByteArray? = null
    private var chunkOffset = 0

    // Microphone Level Diagnostics (Accumulators for Peak and RMS calculations)
    private var diagSampleCount = 0
    private var diagRawPeak = 0.0f
    private var diagRawSumSq = 0.0
    private var diagProcPeak = 0.0f
    private var diagProcSumSq = 0.0

    override fun process(pcmBuffer: ByteArray): ByteArray {
        var idx = 0
        var isLeftChannel = true

        val activeMicGain = if (isMicMuted) 0.0f else micGain.coerceIn(0.0f, 2.0f)
        val activeGameGain = gameGain.coerceIn(0.0f, 3.0f)
        val capture = gameAudioCapture

        while (idx + 1 < pcmBuffer.size) {
            // 1. Decode Microphone 16-bit signed PCM sample (little-endian)
            val micLow = pcmBuffer[idx].toInt() and 0xFF
            val micHigh = pcmBuffer[idx + 1].toInt() shl 8
            val rawMicSample = (micLow or micHigh).toShort().toFloat()
            var processedMic = rawMicSample

            // Diagnostics: Track raw microphone sample metrics
            val rawAbs = abs(rawMicSample)
            if (rawAbs > diagRawPeak) diagRawPeak = rawAbs
            diagRawSumSq += (rawMicSample.toDouble() * rawMicSample.toDouble())

            // 2. Microphone DSP Chain
            if (activeMicGain > 0.0f) {
                // (a) Sub-bass rumble HPF filter
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

                // (b) Manual User Microphone Gain (0.0x .. 2.0x, Default 0.8x)
                processedMic *= activeMicGain

                // (c) Voice Compressor & Envelope Follower (linked L/R)
                val sampleAbs = abs(processedMic)
                if (sampleAbs > compEnv) {
                    compEnv += compAlphaAtt * (sampleAbs - compEnv)
                } else {
                    compEnv += compAlphaRel * (sampleAbs - compEnv)
                }

                val compGain = if (compEnv > compThreshold) {
                    val ratioOver = compEnv / compThreshold
                    1.0f / ratioOver.pow(0.6667f)
                } else {
                    1.0f
                }

                // (d) Apply compression + controlled voice makeup gain
                processedMic = processedMic * compGain * compMakeupGain
            } else {
                processedMic = 0.0f
            }

            // Diagnostics: Track processed microphone metrics
            val procAbs = abs(processedMic)
            if (procAbs > diagProcPeak) diagProcPeak = procAbs
            diagProcSumSq += (processedMic.toDouble() * processedMic.toDouble())
            diagSampleCount++

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

        // Periodic Level Diagnostics (Log approximately once every 1 second without per-sample overhead)
        if (diagSampleCount >= 48000) {
            val countD = diagSampleCount.toDouble()
            val rawRms = sqrt(diagRawSumSq / countD).toFloat()
            val procRms = sqrt(diagProcSumSq / countD).toFloat()

            val rawPeakDb = if (diagRawPeak > 0f) 20.0f * log10(diagRawPeak / 32767.0f) else -96.0f
            val rawRmsDb = if (rawRms > 0f) 20.0f * log10(rawRms / 32767.0f) else -96.0f
            val procPeakDb = if (diagProcPeak > 0f) 20.0f * log10(diagProcPeak / 32767.0f) else -96.0f
            val procRmsDb = if (procRms > 0f) 20.0f * log10(procRms / 32767.0f) else -96.0f

            android.util.Log.d(
                "StreamAudioProcessor",
                "MIC_LEVEL: rawPeak=${"%.1f".format(rawPeakDb)}dBFS rawRms=${"%.1f".format(rawRmsDb)}dBFS gain=${"%.2f".format(activeMicGain)} processedPeak=${"%.1f".format(procPeakDb)}dBFS processedRms=${"%.1f".format(procRmsDb)}dBFS"
            )

            diagSampleCount = 0
            diagRawPeak = 0.0f
            diagRawSumSq = 0.0
            diagProcPeak = 0.0f
            diagProcSumSq = 0.0
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





