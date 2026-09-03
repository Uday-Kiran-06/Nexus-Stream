package com.example.bgmistreamer

import android.util.Log

/**
 * OverlayRect: Authoritative 1920x1080 broadcast canvas rectangle representation
 * ensuring 100% mathematical parity between Preview and Live Renderers.
 */
data class OverlayRect(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float
) {
    // Backwards-compatible coordinate aliases
    val x: Float get() = leftPx
    val y: Float get() = topPx
    val width: Float get() = widthPx
    val height: Float get() = heightPx

    val rightPx: Float get() = leftPx + widthPx
    val bottomPx: Float get() = topPx + heightPx
    val bottom: Float get() = bottomPx

    val leftGap: Float get() = leftPx.coerceAtLeast(0f)
    val rightGap: Float get() = (1920f - rightPx).coerceAtLeast(0f)
    val bottomGap: Float get() = (1080f - bottomPx).coerceAtLeast(0f)

    // Normalized coordinates for shaders and persistence (1920x1080 canvas)
    // Unclamped to allow rendering overlays positioned partially outside canvas
    val normX: Float get() = leftPx / 1920f
    val normY: Float get() = topPx / 1080f
    val normWidth: Float get() = widthPx / 1920f
    val normHeight: Float get() = heightPx / 1080f
}

typealias OverlayCanvasRect = OverlayRect

fun overlayModelToCanvasRect(
    xPercent: Float,
    yPercent: Float,
    scalePercent: Float,
    aspectRatio: Float = 16f / 9f // Default to true 16:9 media bounds (1920x1080 broadcast canvas)
): OverlayRect {
    val widthPx = (scalePercent / 100f) * 1920f
    val heightPx = if (aspectRatio > 0.001f) {
        widthPx / aspectRatio
    } else {
        (scalePercent / 100f) * 1080f
    }
    // Movement bounds allow overlay to move partially or completely off-canvas:
    // minX = -overlayWidth, maxX = canvasWidth (1920)
    // minY = -overlayHeight, maxY = canvasHeight (1080)
    val minLeftPx = -widthPx
    val maxLeftPx = 1920f
    val minTopPx = -heightPx
    val maxTopPx = 1080f
    val leftPx = ((xPercent / 100f) * 1920f).coerceIn(minLeftPx, maxLeftPx)
    val topPx = ((yPercent / 100f) * 1080f).coerceIn(minTopPx, maxTopPx)
    return OverlayRect(leftPx, topPx, widthPx, heightPx)
}

/**
 * Cleanly removes green background from a Bitmap, converting green pixels to transparent alpha
 * with edge despill clamping.
 */
fun removeGreenScreen(source: android.graphics.Bitmap): android.graphics.Bitmap {
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

/**
 * Calculates a proportional, aspect-ratio-preserving ContentRect inside an OverlayRect (contain fit).
 * Guarantees contentWidth / contentHeight == sourceWidth / sourceHeight (or sourceAspect).
 *
 * Preserves user positioning/anchoring:
 * - Vertical: Top, Center, or Bottom (contentBottom = overlayBottom when anchored to the bottom)
 * - Horizontal: Left, Center, or Right
 */
fun calculateContentRect(
    overlayRect: OverlayRect,
    sourceAspect: Float,
    verticalAlignment: Float = 1.0f,   // Default to bottom-anchored
    horizontalAlignment: Float = 0.5f // Default to centered horizontally
): OverlayRect {
    if (sourceAspect <= 0.001f || overlayRect.widthPx <= 0f || overlayRect.heightPx <= 0f) {
        return overlayRect
    }

    val overlayAspect = overlayRect.widthPx / overlayRect.heightPx

    val (contentW, contentH) = if (sourceAspect > overlayAspect) {
        // Limited by overlay width
        val w = overlayRect.widthPx
        val h = w / sourceAspect
        w to h
    } else {
        // Limited by overlay height
        val h = overlayRect.heightPx
        val w = h * sourceAspect
        w to h
    }

    val availX = (overlayRect.widthPx - contentW).coerceAtLeast(0f)
    val availY = (overlayRect.heightPx - contentH).coerceAtLeast(0f)

    // Preserve anchor alignment:
    val vAlign = if (Math.abs(overlayRect.bottomPx - 1080f) <= 2f) 1.0f else if (Math.abs(overlayRect.topPx) <= 2f) 0.0f else verticalAlignment
    val hAlign = if (Math.abs(overlayRect.leftPx) <= 2f) 0.0f else if (Math.abs(overlayRect.rightPx - 1920f) <= 2f) 1.0f else horizontalAlignment

    val contentLeft = overlayRect.leftPx + (availX * hAlign)
    val contentTop = overlayRect.topPx + (availY * vAlign)

    return OverlayRect(contentLeft, contentTop, contentW, contentH)
}

fun calculateContentRect(
    overlayRect: OverlayRect,
    sourceWidth: Float,
    sourceHeight: Float,
    verticalAlignment: Float = 1.0f,
    horizontalAlignment: Float = 0.5f
): OverlayRect {
    val aspect = if (sourceHeight > 0f) sourceWidth / sourceHeight else 16f / 9f
    return calculateContentRect(overlayRect, aspect, verticalAlignment, horizontalAlignment)
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
    @Volatile var isLargeScreenQualityBoost: Boolean = false
    @Volatile var isTestPatternMode: Boolean = false

    fun updateFromViewModel(vm: StreamViewModel) {
        isLargeScreenQualityBoost = vm.isLargeScreenQualityBoostEnabled.value
        isGameplayFilterEnabled = vm.isGameplayFilterEnabled.value
        isExtremeTestMode = false
        extremeTestIndex = 1
        gameplayGamma = vm.gameplayGamma.value
        gameplayContrast = vm.gameplayContrast.value
        gameplayBrightness = vm.gameplayBrightness.value
        gameplaySaturation = vm.gameplaySaturation.value
        gameplaySharpness = vm.gameplaySharpness.value
        isTestPatternMode = false

        Log.i(
            "FILTER_LIVE_UPDATE",
            "qualityBoost=$isLargeScreenQualityBoost, enabled=$isGameplayFilterEnabled, gamma=$gameplayGamma, contrast=$gameplayContrast, brightness=$gameplayBrightness, saturation=$gameplaySaturation, sharpness=$gameplaySharpness, extremeTest=$extremeTestIndex"
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
            sharpness = gameplaySharpness,
            qualityBoost = isLargeScreenQualityBoost
        )
    }
}
