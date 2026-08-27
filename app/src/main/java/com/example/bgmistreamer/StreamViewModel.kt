package com.example.bgmistreamer

import android.app.Application
import android.content.Context
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
    
    val qualities = listOf("720p 30fps", "1080p 60fps", "1440p 60fps", "4K 60fps")
    val selectedQuality = mutableStateOf(prefs.getString("selectedQuality", qualities[1]) ?: qualities[1])
    
    val isLandscapeOrientation = mutableStateOf(prefs.getBoolean("isLandscapeOrientation", true))
    
    val isChromaKeyEnabled = mutableStateOf(prefs.getBoolean("isChromaKeyEnabled", false))
    
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
    
    fun saveSettings() {
        prefs.edit().apply {
            putString("rtmpUrl", rtmpUrl.value)
            putString("streamKey", streamKey.value)
            putString("selectedQuality", selectedQuality.value)
            putBoolean("isLandscapeOrientation", isLandscapeOrientation.value)
            putBoolean("isChromaKeyEnabled", isChromaKeyEnabled.value)
            
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
}
