package com.example.bgmistreamer.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.bgmistreamer.StreamService
import com.example.bgmistreamer.StreamViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: StreamViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
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
    val isMicMuted by StreamService.isMicMutedState
    var durationText by remember { mutableStateOf("00:00:00") }

    fun syncOverlaysWithService() {
        if (!StreamService.isStreamingState.value) return
        val intent = Intent(context, StreamService::class.java).apply {
            action = "UPDATE_OVERLAYS"
            val uris = arrayListOf<String>()
            val scales = FloatArray(viewModel.overlays.size)
            val xPos = FloatArray(viewModel.overlays.size)
            val yPos = FloatArray(viewModel.overlays.size)
            val chromaKeys = BooleanArray(viewModel.overlays.size)

            viewModel.overlays.forEachIndexed { index, overlay ->
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
        }
        context.startService(intent)
    }

    // Automatically sync overlay changes (add, remove, move, resize, chroma) with live stream in real-time
    LaunchedEffect(viewModel.overlays.map { "${it.id}_${it.xPercent}_${it.yPercent}_${it.scalePercent}_${it.chromaKey}" }) {
        if (isStreaming) {
            delay(150)
            syncOverlaysWithService()
        }
    }

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
            val cleanBase = viewModel.rtmpUrl.value.trim().trimEnd('/')
            val cleanKey = viewModel.streamKey.value.trim().trimStart('/')
            val fullUrl = if (cleanKey.isEmpty()) cleanBase else "$cleanBase/$cleanKey"

            val intent = Intent(context, StreamService::class.java).apply {
                action = "START_STREAM"
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("url", fullUrl)
                putExtra("quality", viewModel.selectedQuality.value)
                putExtra("isLandscape", viewModel.isLandscapeOrientation.value)
                putExtra("chromaKey", viewModel.isChromaKeyEnabled.value)

                val uris = arrayListOf<String>()
                val scales = FloatArray(viewModel.overlays.size)
                val xPos = FloatArray(viewModel.overlays.size)
                val yPos = FloatArray(viewModel.overlays.size)
                val chromaKeys = BooleanArray(viewModel.overlays.size)

                viewModel.overlays.forEachIndexed { index, overlay ->
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
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // Audio Permission Launcher (Required for microphone capture during stream)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val mediaProjectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
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
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(top = 16.dp)
                .verticalScroll(rememberScrollState()),
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
            // overlayCanvas is a NON-scrollable Box so gestures work correctly
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

                // Overlays — using offset() so touch hit area matches visual position
                if (canvasSize.width > 0 && canvasSize.height > 0) {
                    viewModel.overlays.forEach { overlay ->
                        // Overlay size is a % of canvas width
                        val overlayWidthPx = (overlay.scalePercent / 100f * canvasSize.width).roundToInt()
                        val overlayHeightPx = (overlay.scalePercent / 100f * canvasSize.height).roundToInt()

                        // Position: xPercent/yPercent is top-left corner as % of canvas
                        val offsetXPx = ((overlay.xPercent / 100f) * canvasSize.width).roundToInt()
                            .coerceIn(0, (canvasSize.width - overlayWidthPx).coerceAtLeast(0))
                        val offsetYPx = ((overlay.yPercent / 100f) * canvasSize.height).roundToInt()
                            .coerceIn(0, (canvasSize.height - overlayHeightPx).coerceAtLeast(0))

                        val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
                        val overlayHeightDp = with(density) { overlayHeightPx.toDp() }

                        val imageRequest = coil.request.ImageRequest.Builder(context)
                            .data(Uri.parse(overlay.uri))
                            .decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                            .build()

                        AsyncImage(
                            model = imageRequest,
                            contentDescription = "Overlay",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .offset { IntOffset(offsetXPx, offsetYPx) }
                                .size(width = overlayWidthDp, height = overlayHeightDp)
                                .pointerInput(overlay.id) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newXPercent = ((offsetXPx + pan.x) / canvasSize.width * 100f)
                                            .coerceIn(0f, 100f)
                                        val newYPercent = ((offsetYPx + pan.y) / canvasSize.height * 100f)
                                            .coerceIn(0f, 100f)
                                        val newScale = (overlay.scalePercent * zoom)
                                            .coerceIn(5f, 100f)
                                        viewModel.updateOverlayPosition(overlay.id, newXPercent, newYPercent)
                                        viewModel.updateOverlayScale(overlay.id, newScale)
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
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Drag to move • Pinch to scale • Double-tap to delete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Active overlay list with quick controls
            if (viewModel.overlays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Active Overlays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    viewModel.overlays.forEachIndexed { index, overlay ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Overlay ${index + 1}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.updateOverlayPosition(overlay.id, 0f, 0f)
                                            viewModel.updateOverlayScale(overlay.id, 100f)
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) { Text("Fit") }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.updateOverlayPosition(overlay.id, 25f, 25f)
                                            viewModel.updateOverlayScale(overlay.id, 30f)
                                        }
                                    ) { Text("Reset") }
                                    IconButton(onClick = { viewModel.removeOverlay(overlay.id) }) {
                                        Text("✕", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                // Chroma Key toggle (only for images — green screen removal)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "🟢 Chroma Key (Green Screen)",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Switch(
                                        checked = overlay.chromaKey,
                                        onCheckedChange = { viewModel.updateOverlayChromaKey(overlay.id, it) }
                                    )
                                }
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
                Text("Add Media Overlay (Image / Video)", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Streaming Control
            if (isStreaming) {
                // Microphone Live Toggle Button
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(context, StreamService::class.java).apply { action = "TOGGLE_MIC" }
                        context.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isMicMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = if (isMicMuted) "🔇 Microphone: MUTED  (Tap to Unmute)" else "🎤 Microphone: LIVE  (Tap to Mute)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMicMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val intent = Intent(context, StreamService::class.java).apply { action = "STOP" }
                        context.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("🔴  $durationText  — Tap to Stop", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                Button(
                    onClick = {
                        if (viewModel.rtmpUrl.value.isBlank() || viewModel.streamKey.value.isBlank()) {
                            Toast.makeText(context, "Go to Settings → enter RTMP URL and Stream Key first", Toast.LENGTH_LONG).show()
                            return@Button
                        }

                        // Check Overlay permission (optional, helpful for floating stop button)
                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(intent)
                            Toast.makeText(context, "Optional: Grant 'Display over other apps' for floating controls", Toast.LENGTH_SHORT).show()
                        }

                        // Check microphone permission
                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (!hasAudioPerm) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        }
    }
}
