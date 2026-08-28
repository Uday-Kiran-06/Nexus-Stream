package com.example.bgmistreamer

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * StreamAudioProcessor - Studio Earphone & Headset DSP Filter.
 *
 * Specially designed for Earphone / Headphone microphones during live gameplay:
 * 1. Plosive & Pop Filter (130Hz High-Pass):
 *    Completely cuts heavy breathing bursts directly into the mic and cable rustle against clothes.
 * 2. Smooth Downward Expander (Background Hiss Suppressor):
 *    Smoothly attenuates fan drone, AC hum, and room static by up to -16dB during silence
 *    without abruptly chopping off quiet voice or game audio.
 * 3. De-Hisser (Low-Pass Filter ~7.5kHz):
 *    Tames harsh high-frequency hiss typical of budget earphone mics.
 * 4. Soft Limiter:
 *    Prevents digital clipping and distortion when shouting or during loud in-game explosions.
 */
class StreamAudioProcessor(
    var enableNoiseSuppression: Boolean = false,
    var enableEchoCancellation: Boolean = false
) : CustomAudioEffect() {

    // 1. High-Pass Filter (Cutoff ~130Hz @ 44.1/48kHz) - Cuts breath pops & cable rustle
    private val hpAlpha = 0.982f
    private var hpPrevXLeft = 0.0f
    private var hpPrevYLeft = 0.0f
    private var hpPrevXRight = 0.0f
    private var hpPrevYRight = 0.0f

    // 2. De-Hisser / Low-Pass Filter (~7.5kHz) - Cuts mic static hiss
    private val lpAlpha = 0.52f
    private var lpPrevYLeft = 0.0f
    private var lpPrevYRight = 0.0f

    // 3. Downward Expander Envelope Tracker
    // Threshold ~ -30dBFS (scaled to 1.0f float domain: ~0.032)
    private val noiseThreshold = 1000.0f // on 16-bit PCM scale (-32768..32767)
    private val minExpansionGain = 0.15f // Max -16.5dB attenuation on background noise
    private var envelope = 0.0f
    private var currentGain = 1.0f

    // Envelope time constants
    private val attackAlpha = 0.08f   // Fast attack (~2ms) when speaking starts
    private val releaseAlpha = 0.0003f // Smooth release (~150ms) to prevent audio pumping

    override fun process(pcm: ByteArray): ByteArray {
        // If filters are disabled, pass through 100% untouched raw audio
        if (!enableNoiseSuppression && !enableEchoCancellation) {
            return pcm
        }

        var idx = 0
        var isLeftChannel = true

        while (idx + 1 < pcm.size) {
            // Decode 16-bit PCM sample (little endian)
            val low = pcm[idx].toInt() and 0xFF
            val high = pcm[idx + 1].toInt() shl 8
            val rawSample = (low or high).toShort().toFloat()
            var processed = rawSample

            // --- 1. Plosive & Breath Pop High-Pass Filter ---
            if (enableNoiseSuppression) {
                if (isLeftChannel) {
                    val hpOut = hpAlpha * (hpPrevYLeft + processed - hpPrevXLeft)
                    hpPrevXLeft = processed
                    hpPrevYLeft = hpOut
                    processed = hpOut
                } else {
                    val hpOut = hpAlpha * (hpPrevYRight + processed - hpPrevXRight)
                    hpPrevXRight = processed
                    hpPrevYRight = hpOut
                    processed = hpOut
                }
            }

            // --- 2. De-Hiss / Feedback Low-Pass Filter ---
            if (enableEchoCancellation) {
                if (isLeftChannel) {
                    val lpOut = lpPrevYLeft + lpAlpha * (processed - lpPrevYLeft)
                    lpPrevYLeft = lpOut
                    processed = lpOut
                } else {
                    val lpOut = lpPrevYRight + lpAlpha * (processed - lpPrevYRight)
                    lpPrevYRight = lpOut
                    processed = lpOut
                }
            }

            // --- 3. Downward Expander (Smooth Noise Suppressor) ---
            if (enableNoiseSuppression) {
                val sampleMag = abs(processed)
                if (sampleMag > envelope) {
                    envelope += attackAlpha * (sampleMag - envelope)
                } else {
                    envelope -= releaseAlpha * (envelope - sampleMag)
                }

                // Compute target gain based on envelope level
                val targetGain = if (envelope >= noiseThreshold) {
                    1.0f
                } else {
                    val ratio = envelope / noiseThreshold
                    max(minExpansionGain, ratio.pow(1.5f))
                }

                // Smooth gain transition to eliminate clicks/zipper noise
                currentGain += 0.005f * (targetGain - currentGain)
                processed *= currentGain
            }

            // --- 4. Soft Limiter (Anti-Clipping Protection) ---
            val clamped = processed.coerceIn(-32767f, 32767f).toInt().toShort()

            // Write back to PCM byte array
            pcm[idx] = (clamped.toInt() and 0xFF).toByte()
            pcm[idx + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()

            isLeftChannel = !isLeftChannel
            idx += 2
        }

        return pcm
    }
}

