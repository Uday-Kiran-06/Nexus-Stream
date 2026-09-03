package com.example.bgmistreamer

/**
 * StreamQualityPreset:
 * Defines stable internal quality identifiers and explicit resolution,
 * framerate, and bitrate targets to prevent UI display string mismatches
 * and eliminate accidental 720p downgrades.
 */
enum class StreamQualityPreset(
    val id: String,
    val displayLabel: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int
) {
    QUALITY_1080P60_10(
        id = "QUALITY_1080P60_10",
        displayLabel = "1080p 60fps (10 Mbps - Recommended)",
        width = 1920,
        height = 1080,
        fps = 60,
        bitrateBps = 10_000_000
    ),
    QUALITY_1080P60_8(
        id = "QUALITY_1080P60_8",
        displayLabel = "1080p 60fps (8 Mbps)",
        width = 1920,
        height = 1080,
        fps = 60,
        bitrateBps = 8_192_000
    ),
    QUALITY_720P30(
        id = "QUALITY_720P30",
        displayLabel = "720p 30fps (Mobile Data)",
        width = 1280,
        height = 720,
        fps = 30,
        bitrateBps = 3_500_000
    );

    companion object {
        val DEFAULT = QUALITY_1080P60_10

        fun fromIdOrLabel(value: String?): StreamQualityPreset {
            if (value.isNullOrBlank()) return DEFAULT
            // 1. Direct ID match
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) }?.let { return it }
            // 2. Exact display label match
            entries.firstOrNull { it.displayLabel.equals(value, ignoreCase = true) }?.let { return it }
            // 3. Fallback matching for legacy or custom strings
            return when {
                value.contains("10 Mbps", ignoreCase = true) || value.contains("10Mbps", ignoreCase = true) || value.contains("10.0 Mbps", ignoreCase = true) || value.contains("10M", ignoreCase = true) -> QUALITY_1080P60_10
                value.contains("8 Mbps", ignoreCase = true) || value.contains("8Mbps", ignoreCase = true) || value.contains("1080", ignoreCase = true) -> QUALITY_1080P60_8
                value.contains("720", ignoreCase = true) -> QUALITY_720P30
                else -> DEFAULT
            }
        }
    }
}
