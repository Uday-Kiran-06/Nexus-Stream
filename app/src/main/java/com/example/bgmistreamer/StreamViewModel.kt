package com.example.bgmistreamer

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class OverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    var xPercent: Float = 25f,
    var yPercent: Float = 25f,
    var scalePercent: Float = 30f,
    var chromaKey: Boolean = false
)

class StreamViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("StreamPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    val rtmpUrl = mutableStateOf(prefs.getString("rtmpUrl", "rtmp://a.rtmp.youtube.com/live2/") ?: "")
    val streamKey = mutableStateOf(prefs.getString("streamKey", "") ?: "")
    
    val qualityPresets = listOf(
        StreamQualityPreset.QUALITY_1080P60_10,
        StreamQualityPreset.QUALITY_1080P60_8,
        StreamQualityPreset.QUALITY_720P30
    )
    val qualities = qualityPresets.map { it.displayLabel }

    val selectedQualityPreset = mutableStateOf(
        StreamQualityPreset.fromIdOrLabel(
            prefs.getString("selectedQualityId", null) ?: prefs.getString("selectedQuality", null)
        )
    )
    val selectedQuality = mutableStateOf(selectedQualityPreset.value.displayLabel)

    fun selectQualityPreset(preset: StreamQualityPreset) {
        selectedQualityPreset.value = preset
        selectedQuality.value = preset.displayLabel
        saveSettings()
    }

    fun selectQualityByLabel(label: String) {
        val preset = StreamQualityPreset.fromIdOrLabel(label)
        selectQualityPreset(preset)
    }

    val isLargeScreenQualityBoostEnabled = mutableStateOf(prefs.getBoolean("largeScreenQualityBoost", false))
    val micVolumePercent = mutableStateOf(prefs.getFloat("micVolumePercent", 80f).coerceIn(0f, 200f))
    
    // Gameplay Color & Sharpness Filter
    companion object {
        const val GAMEPLAY_GAMMA_DEFAULT = 0.16f
        const val GAMEPLAY_CONTRAST_DEFAULT = 0.04f
        const val GAMEPLAY_BRIGHTNESS_DEFAULT = 0.0100f
        const val GAMEPLAY_SATURATION_DEFAULT = 0.94f
        const val GAMEPLAY_SHARPNESS_DEFAULT = 0.80f
    }

    val isGameplayFilterEnabled = mutableStateOf(prefs.getBoolean("isGameplayFilterEnabled", true))
    val gameplayGamma = mutableStateOf(prefs.getFloat("gameplayGamma", GAMEPLAY_GAMMA_DEFAULT))
    val gameplayContrast = mutableStateOf(prefs.getFloat("gameplayContrast", GAMEPLAY_CONTRAST_DEFAULT))
    val gameplayBrightness = mutableStateOf(prefs.getFloat("gameplayBrightness", GAMEPLAY_BRIGHTNESS_DEFAULT))
    val gameplaySaturation = mutableStateOf(prefs.getFloat("gameplaySaturation", GAMEPLAY_SATURATION_DEFAULT))
    val gameplaySharpness = mutableStateOf(prefs.getFloat("gameplaySharpness", GAMEPLAY_SHARPNESS_DEFAULT))

    val isLandscapeOrientation = mutableStateOf(prefs.getBoolean("isLandscapeOrientation", true))
    
    val isChromaKeyEnabled = mutableStateOf(prefs.getBoolean("isChromaKeyEnabled", false))
    
    // Audio processing filters
    val isNoiseSuppressorEnabled = mutableStateOf(prefs.getBoolean("isNoiseSuppressorEnabled", false))
    val isEchoCancelerEnabled = mutableStateOf(prefs.getBoolean("isEchoCancelerEnabled", false))

    // Selected element for fine manual adjustment: "GAME_SCREEN" or an overlay ID
    val selectedElementId = mutableStateOf<String?>("GAME_SCREEN")

    // Mobile Game Screen preview placement
    val gameScreenXPercent = mutableStateOf(prefs.getFloat("gameScreenX", 0f))
    val gameScreenYPercent = mutableStateOf(prefs.getFloat("gameScreenY", 0f))
    val gameScreenScalePercent = mutableStateOf(prefs.getFloat("gameScreenScale", 100f))

    val overlays = mutableStateListOf<OverlayItem>()
    
    init {
        loadOverlays()
    }
    
    fun selectElement(id: String?) {
        selectedElementId.value = id
    }

    fun resetGameplayFilterDefaults() {
        isGameplayFilterEnabled.value = true
        gameplayGamma.value = GAMEPLAY_GAMMA_DEFAULT
        gameplayContrast.value = GAMEPLAY_CONTRAST_DEFAULT
        gameplayBrightness.value = GAMEPLAY_BRIGHTNESS_DEFAULT
        gameplaySaturation.value = GAMEPLAY_SATURATION_DEFAULT
        gameplaySharpness.value = GAMEPLAY_SHARPNESS_DEFAULT
        onFilterChanged()
    }

    fun updateGameScreenPosition(x: Float, y: Float) {
        gameScreenXPercent.value = x.coerceIn(0f, 100f)
        gameScreenYPercent.value = y.coerceIn(0f, 100f)
        prefs.edit()
            .putFloat("gameScreenX", gameScreenXPercent.value)
            .putFloat("gameScreenY", gameScreenYPercent.value)
            .apply()
    }

    fun updateGameScreenScale(scale: Float) {
        gameScreenScalePercent.value = scale.coerceIn(20f, 100f)
        prefs.edit().putFloat("gameScreenScale", gameScreenScalePercent.value).apply()
    }
    
    fun updateMicVolume(percent: Float) {
        val clamped = percent.coerceIn(0f, 200f)
        micVolumePercent.value = clamped
        prefs.edit().putFloat("micVolumePercent", clamped).apply()
        if (StreamService.isStreamingState.value) {
            try {
                val app = getApplication<Application>()
                val intent = Intent(app, StreamService::class.java).apply {
                    action = "UPDATE_AUDIO_SETTINGS"
                    putExtra("micVolumePercent", clamped)
                    putExtra("noiseSuppressor", isNoiseSuppressorEnabled.value)
                    putExtra("echoCanceler", isEchoCancelerEnabled.value)
                }
                app.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveSettings() {
        prefs.edit().apply {
            putString("rtmpUrl", rtmpUrl.value)
            putString("streamKey", streamKey.value)
            putString("selectedQualityId", selectedQualityPreset.value.id)
            putString("selectedQuality", selectedQuality.value)
            putBoolean("largeScreenQualityBoost", isLargeScreenQualityBoostEnabled.value)
            putFloat("micVolumePercent", micVolumePercent.value)
            putBoolean("isGameplayFilterEnabled", isGameplayFilterEnabled.value)
            putFloat("gameplayGamma", gameplayGamma.value)
            putFloat("gameplayContrast", gameplayContrast.value)
            putFloat("gameplayBrightness", gameplayBrightness.value)
            putFloat("gameplaySaturation", gameplaySaturation.value)
            putFloat("gameplaySharpness", gameplaySharpness.value)
            putBoolean("isLandscapeOrientation", isLandscapeOrientation.value)
            putBoolean("isChromaKeyEnabled", isChromaKeyEnabled.value)
            putBoolean("isNoiseSuppressorEnabled", isNoiseSuppressorEnabled.value)
            putBoolean("isEchoCancelerEnabled", isEchoCancelerEnabled.value)
            
            val overlaysJson = gson.toJson(overlays)
            putString("overlays", overlaysJson)
        }.apply()
    }
    
    private fun loadOverlays() {
        val overlaysJson = prefs.getString("overlays", null)
        if (overlaysJson != null) {
            try {
                val type = object : TypeToken<List<OverlayItem>>() {}.type
                val savedOverlays: List<OverlayItem> = gson.fromJson(overlaysJson, type)
                overlays.addAll(savedOverlays)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun addOverlay(uri: String) {
        overlays.add(OverlayItem(uri = uri))
        saveSettings()
    }
    
    fun removeOverlay(id: String) {
        overlays.removeAll { it.id == id }
        saveSettings()
    }
    
    fun updateOverlayPosition(id: String, x: Float, y: Float) {
        val index = overlays.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = overlays[index]
            overlays[index] = item.copy(xPercent = x, yPercent = y)
            saveSettings()
        }
    }
    
    fun updateOverlayScale(id: String, scale: Float) {
        val index = overlays.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = overlays[index]
            overlays[index] = item.copy(scalePercent = scale)
            saveSettings()
        }
    }
    fun updateOverlayChromaKey(id: String, enabled: Boolean) {
        val index = overlays.indexOfFirst { it.id == id }
        if (index != -1) {
            overlays[index] = overlays[index].copy(chromaKey = enabled)
            saveSettings()
        }
    }

    fun onFilterChanged() {
        saveSettings()
        StreamFilterState.updateFromViewModel(this)
        if (StreamService.isStreamingState.value) {
            try {
                val app = getApplication<Application>()
                val intent = Intent(app, StreamService::class.java).apply {
                    action = "UPDATE_OVERLAYS"
                    val uris = arrayListOf<String>()
                    val scales = FloatArray(overlays.size)
                    val xPos = FloatArray(overlays.size)
                    val yPos = FloatArray(overlays.size)
                    val chromaKeys = BooleanArray(overlays.size)

                    overlays.forEachIndexed { index, overlay ->
                        uris.add(overlay.uri)
                        scales[index] = overlay.scalePercent
                        xPos[index] = overlay.xPercent
                        yPos[index] = overlay.yPercent
                        chromaKeys[index] = overlay.chromaKey
                    }

                    putStringArrayListExtra("overlayUris", uris)
                    putExtra("overlayScales", scales)
                    putExtra("overlayX", xPos)
                    putExtra("overlayY", yPos)
                    putExtra("overlayChromaKeys", chromaKeys)

                    putExtra("gameScreenScale", gameScreenScalePercent.value)
                    putExtra("gameScreenX", gameScreenXPercent.value)
                    putExtra("gameScreenY", gameScreenYPercent.value)
                    putExtra("isGameplayFilterEnabled", isGameplayFilterEnabled.value)
                    putExtra("isLargeScreenQualityBoost", isLargeScreenQualityBoostEnabled.value)
                    putExtra("micVolumePercent", micVolumePercent.value)
                    putExtra("gameplayGamma", gameplayGamma.value)
                    putExtra("gameplayContrast", gameplayContrast.value)
                    putExtra("gameplayBrightness", gameplayBrightness.value)
                    putExtra("gameplaySaturation", gameplaySaturation.value)
                    putExtra("gameplaySharpness", gameplaySharpness.value)
                }
                app.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
