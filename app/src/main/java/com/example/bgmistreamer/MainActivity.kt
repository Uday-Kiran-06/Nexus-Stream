package com.example.bgmistreamer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.bgmistreamer.theme.BGMIStreamerTheme

import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import android.util.Log

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
        
    Thread {
        try {
            Log.d("REFLECT_DUMP", "--- BaseFilterRender ---")
            BaseFilterRender::class.java.declaredFields.forEach { Log.d("REFLECT_DUMP", "Field: " + it.name + " type: " + it.type.name) }
            BaseFilterRender::class.java.declaredMethods.forEach { Log.d("REFLECT_DUMP", "Method: " + it.name) }
            Log.d("REFLECT_DUMP", "--- SurfaceFilterRender ---")
            SurfaceFilterRender::class.java.declaredFields.forEach { Log.d("REFLECT_DUMP", "Field: " + it.name + " type: " + it.type.name) }
            SurfaceFilterRender::class.java.declaredMethods.forEach { Log.d("REFLECT_DUMP", "Method: " + it.name) }
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }.start()

    enableEdgeToEdge()
    setContent {
      BGMIStreamerTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }
}
