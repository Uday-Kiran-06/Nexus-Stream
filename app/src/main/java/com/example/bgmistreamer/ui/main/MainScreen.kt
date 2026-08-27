package com.example.bgmistreamer.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.bgmistreamer.StreamService
import com.example.bgmistreamer.StreamViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: StreamViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    
    // Media Picker (Image + Video)
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addOverlay(it.toString())
        }
    }

    val isStreaming by StreamService.isStreamingState
    var durationText by remember { mutableStateOf("00:00:00") }
    
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            while (true) {
                val elapsed = System.currentTimeMillis() - StreamService.streamStartTime.value
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                val hours = (elapsed / (1000 * 60 * 60))
                durationText = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000)
            }
        }
    }

    // Permission for Screen Capture
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = "START_STREAM"
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("url", viewModel.rtmpUrl.value + viewModel.streamKey.value)
                putExtra("quality", viewModel.selectedQuality.value)
                putExtra("isLandscape", viewModel.isLandscapeOrientation.value)
                putExtra("chromaKey", viewModel.isChromaKeyEnabled.value)
                
                // Pack overlay URIs and configurations
                val uris = arrayListOf<String>()
                val scales = FloatArray(viewModel.overlays.size)
                val xPos = FloatArray(viewModel.overlays.size)
                val yPos = FloatArray(viewModel.overlays.size)
                
                viewModel.overlays.forEachIndexed { index, overlay ->
                    uris.add(overlay.uri)
                    scales[index] = overlay.scalePercent
                    xPos[index] = overlay.xPercent
                    yPos[index] = overlay.yPercent
                }
                
                putStringArrayListExtra("overlayUris", uris)
                putExtra("overlayScales", scales)
                putExtra("overlayX", xPos)
                putExtra("overlayY", yPos)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Live Studio",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // 16:9 Canvas Layout Editor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .clipToBounds()
                    .onGloballyPositioned { coordinates ->
                        canvasSize = coordinates.size
                    }
            ) {
                // Base Game Screen placeholder
                Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Text("Game Screen", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                
                // Overlays
                viewModel.overlays.forEach { overlay ->
                    // Calculate absolute positions from percentage dynamically
                    val offsetX = if (canvasSize.width > 0) (overlay.xPercent / 100f * canvasSize.width) else 0f
                    val offsetY = if (canvasSize.height > 0) (overlay.yPercent / 100f * canvasSize.height) else 0f
                    val scale = overlay.scalePercent / 100f * 3f // 3x multiplier for visibility in small canvas
                    
                    val imageRequest = coil.request.ImageRequest.Builder(context)
                        .data(Uri.parse(overlay.uri))
                        .decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                        .build()
                        
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "Overlay",
                        modifier = Modifier
                            .graphicsLayer(
                                translationX = offsetX,
                                translationY = offsetY,
                                scaleX = scale,
                                scaleY = scale
                            )
                            .size(100.dp) // Base size, scaled up/down
                            .pointerInput(overlay.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    if (canvasSize.width > 0 && canvasSize.height > 0) {
                                        val newXPercent = ((offsetX + pan.x) / canvasSize.width) * 100f
                                        val newYPercent = ((offsetY + pan.y) / canvasSize.height) * 100f
                                        val newScalePercent = ((scale * zoom) / 3f) * 100f
                                        
                                        viewModel.updateOverlayPosition(overlay.id, newXPercent, newYPercent)
                                        viewModel.updateOverlayScale(overlay.id, newScalePercent)
                                    }
                                }
                            }
                            .pointerInput(overlay.id + "_tap") {
                                detectTapGestures(
                                    onDoubleTap = {
                                        viewModel.removeOverlay(overlay.id)
                                    }
                                )
                            }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tip: Pinch to scale. Drag to move. Double-tap to delete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            // List of overlays with controls
            if (viewModel.overlays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Active Overlays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    viewModel.overlays.forEachIndexed { index, overlay ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Overlay ${index + 1}", modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateOverlayPosition(overlay.id, 0f, 0f)
                                    viewModel.updateOverlayScale(overlay.id, 100f)
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("Fit Screen")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateOverlayPosition(overlay.id, 50f, 50f)
                                    viewModel.updateOverlayScale(overlay.id, 30f)
                                }
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { mediaPickerLauncher.launch(arrayOf("image/*", "video/*")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Add Media Overlay (Image/Video)", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Streaming Control
            if (isStreaming) {
                Button(
                    onClick = {
                        val intent = Intent(context, StreamService::class.java).apply { action = "STOP" }
                        context.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(durationText, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                // Go Live Button
                Button(
                    onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(intent)
                        } else if (viewModel.rtmpUrl.value.isBlank() || viewModel.streamKey.value.isBlank()) {
                            Toast.makeText(context, "Please configure RTMP URL and Stream Key first", Toast.LENGTH_LONG).show()
                        } else {
                            val mediaProjectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("GO LIVE", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = {
                    val intent = Intent(context, StreamService::class.java).apply {
                        action = "STOP"
                    }
                    context.startService(intent)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("End Broadcast", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
