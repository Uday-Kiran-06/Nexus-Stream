package com.example.bgmistreamer

import android.util.Log

/**
 * OverlayCanvasRect: Authoritative 1920x1080 canvas rectangle representation
 * ensuring 100% mathematical parity between Preview and Live Renderers.
 */
data class OverlayCanvasRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val bottom: Float get() = y + height
    val bottomGap: Float get() = (1080f - bottom).coerceAtLeast(0f)
    val normX: Float get() = (x / 1920f).coerceIn(0f, 1f)
    val normY: Float get() = (y / 1080f).coerceIn(0f, 1f)
    val normWidth: Float get() = (width / 1920f).coerceIn(0f, 1f)
    val normHeight: Float get() = (height / 1080f).coerceIn(0f, 1f)
}

fun overlayModelToCanvasRect(xPercent: Float, yPercent: Float, scalePercent: Float): OverlayCanvasRect {
    val canvasW = (scalePercent / 100f) * 1920f
    val canvasH = canvasW * (9f / 16f)
    val maxCanvasX = (1920f - canvasW).coerceAtLeast(0f)
    val maxCanvasY = (1080f - canvasH).coerceAtLeast(0f)
    val canvasX = ((xPercent / 100f) * 1920f).coerceIn(0f, maxCanvasX)
    val canvasY = ((yPercent / 100f) * 1080f).coerceIn(0f, maxCanvasY)
    return OverlayCanvasRect(canvasX, canvasY, canvasW, canvasH)
}

/**
 * StreamFilterState:
 * Thread-safe singleton providing zero-latency live synchronization of
 * gameplay color/sharpness filter uniforms and preview overlay transforms
 * directly to the active OpenGL rendering thread without restarting the stream.
 */
object StreamFilterState {
    @Volatile var isGameplayFilterEnabled: Boolean = true
    @Volatile var isExtremeTestMode: Boolean = false
    @Volatile var extremeTestIndex: Int = 1
    @Volatile var gameplayGamma: Float = 0.16f
    @Volatile var gameplayContrast: Float = 0.04f
    @Volatile var gameplayBrightness: Float = 0.0100f
    @Volatile var gameplaySaturation: Float = 0.94f
    @Volatile var gameplaySharpness: Float = 0.80f
    @Volatile var isTestPatternMode: Boolean = false

    fun updateFromViewModel(vm: StreamViewModel) {
        isGameplayFilterEnabled = vm.isGameplayFilterEnabled.value
        isExtremeTestMode = vm.isExtremeFilterTestEnabled.value
        extremeTestIndex = vm.extremeFilterTestIndex.value
        gameplayGamma = vm.gameplayGamma.value
        gameplayContrast = vm.gameplayContrast.value
        gameplayBrightness = vm.gameplayBrightness.value
        gameplaySaturation = vm.gameplaySaturation.value
        gameplaySharpness = vm.gameplaySharpness.value
        isTestPatternMode = vm.isTestPatternEnabled.value

        Log.i(
            "FILTER_LIVE_UPDATE",
            "enabled=$isGameplayFilterEnabled, gamma=$gameplayGamma, contrast=$gameplayContrast, brightness=$gameplayBrightness, saturation=$gameplaySaturation, sharpness=$gameplaySharpness, extremeTest=$extremeTestIndex"
        )

        // Push directly to authoritative active GameScreenFilterRender instance without restarting stream
        (StreamService.activeGameScreenFilterInstance ?: StreamService.activeGameFilterInstance)?.updateParameters(
            enabled = isGameplayFilterEnabled,
            extreme = isExtremeTestMode,
            extremeIdx = extremeTestIndex,
            gamma = gameplayGamma,
            contrast = gameplayContrast,
            brightness = gameplayBrightness,
            saturation = gameplaySaturation,
            sharpness = gameplaySharpness
        )
    }
}
