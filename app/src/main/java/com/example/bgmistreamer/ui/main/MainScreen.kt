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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.bgmistreamer.StreamService
import com.example.bgmistreamer.StreamViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Extension for high-visibility dashed borders in studio preview
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp = 0.dp,
    dashLength: Dp = 6.dp,
    gapLength: Dp = 6.dp
) = this.drawBehind {
    val stroke = Stroke(
        width = width.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f)
    )
    if (cornerRadius.value > 0f) {
        drawRoundRect(
            color = color,
            style = stroke,
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        )
    } else {
        drawRect(
            color = color,
            style = stroke
        )
    }
}

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
    val selectedId by viewModel.selectedElementId
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

            putExtra("gameScreenScale", viewModel.gameScreenScalePercent.value)
            putExtra("gameScreenX", viewModel.gameScreenXPercent.value)
            putExtra("gameScreenY", viewModel.gameScreenYPercent.value)
        }
        context.startService(intent)
    }

    // Automatically sync overlay & game screen layout changes with live stream in real-time
    LaunchedEffect(
        viewModel.overlays.map { "${it.id}_${it.xPercent}_${it.yPercent}_${it.scalePercent}_${it.chromaKey}" },
        viewModel.gameScreenScalePercent.value,
        viewModel.gameScreenXPercent.value,
        viewModel.gameScreenYPercent.value
    ) {
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
            val fullUrl = if (cleanKey.isNotEmpty()) "$cleanBase/$cleanKey" else cleanBase

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

                putExtra("gameScreenScale", viewModel.gameScreenScalePercent.value)
                putExtra("gameScreenX", viewModel.gameScreenXPercent.value)
                putExtra("gameScreenY", viewModel.gameScreenYPercent.value)
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
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Text(
                text = "Preview: 16:9 • Mobile Screen: 2.17:1 • Overlays: 16:9",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            // 16:9 Canvas Layout Editor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B1120))
                    .border(2.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                    .clipToBounds()
                    .onGloballyPositioned { coordinates ->
                        canvasSize = coordinates.size
                    }
            ) {
                if (canvasSize.width > 0 && canvasSize.height > 0) {
                    // 1. Mobile Game Screen Preview (Exact 2.17:1 Aspect Ratio)
                    val GAME_SCREEN_ASPECT_RATIO = 2.17f
                    val gameWidthPx = (viewModel.gameScreenScalePercent.value / 100f * canvasSize.width).roundToInt()
                    val gameHeightPx = (gameWidthPx / GAME_SCREEN_ASPECT_RATIO).roundToInt()
                    val gameOffsetXPx = ((viewModel.gameScreenXPercent.value / 100f) * canvasSize.width).roundToInt()
                        .coerceIn(0, (canvasSize.width - gameWidthPx).coerceAtLeast(0))
                    val gameOffsetYPx = ((viewModel.gameScreenYPercent.value / 100f) * canvasSize.height).roundToInt()
                        .coerceIn(0, (canvasSize.height - gameHeightPx).coerceAtLeast(0))

                    val isGameSelected = selectedId == "GAME_SCREEN"

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(gameOffsetXPx, gameOffsetYPx) }
                            .size(
                                width = with(density) { gameWidthPx.toDp() },
                                height = with(density) { gameHeightPx.toDp() }
                            )
                            .background(Color(0xFF1E293B))
                            .dashedBorder(
                                width = if (isGameSelected) 2.5.dp else 1.5.dp,
                                color = if (isGameSelected) Color(0xFF00E5FF) else Color(0x7700E5FF),
                                cornerRadius = 6.dp
                            )
                            .pointerInput("game_screen_drag") {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    viewModel.selectElement("GAME_SCREEN")
                                    val newX = ((gameOffsetXPx + pan.x) / canvasSize.width * 100f).coerceIn(0f, 100f)
                                    val newY = ((gameOffsetYPx + pan.y) / canvasSize.height * 100f).coerceIn(0f, 100f)
                                    val newScale = (viewModel.gameScreenScalePercent.value * zoom).coerceIn(20f, 100f)
                                    viewModel.updateGameScreenPosition(newX, newY)
                                    viewModel.updateGameScreenScale(newScale)
                                }
                            }
                            .pointerInput("game_screen_tap") {
                                detectTapGestures {
                                    viewModel.selectElement("GAME_SCREEN")
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "📱 Mobile Game Screen (2.17:1)",
                                color = if (isGameSelected) Color(0xFF00E5FF) else Color(0xCCFFFFFF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                "Native mobile ratio • Drag to reposition",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }

                        // Badge
                        Surface(
                            shape = RoundedCornerShape(bottomEnd = 6.dp),
                            color = if (isGameSelected) Color(0xFF00E5FF) else Color(0x6600E5FF),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Text(
                                "2.17:1",
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 2. Overlays Previews (Exact 16:9 Aspect Ratio with Dashed Border)
                    viewModel.overlays.forEachIndexed { index, overlay ->
                        val overlayWidthPx = (overlay.scalePercent / 100f * canvasSize.width).roundToInt()
                        val overlayHeightPx = (overlayWidthPx * 9f / 16f).roundToInt()

                        val offsetXPx = ((overlay.xPercent / 100f) * canvasSize.width).roundToInt()
                            .coerceIn(0, (canvasSize.width - overlayWidthPx).coerceAtLeast(0))
                        val offsetYPx = ((overlay.yPercent / 100f) * canvasSize.height).roundToInt()
                            .coerceIn(0, (canvasSize.height - overlayHeightPx).coerceAtLeast(0))

                        val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
                        val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
                        val isOverlaySelected = selectedId == overlay.id

                        val imageRequest = coil.request.ImageRequest.Builder(context)
                            .data(Uri.parse(overlay.uri))
                            .decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                            .build()

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(offsetXPx, offsetYPx) }
                                .size(width = overlayWidthDp, height = overlayHeightDp)
                                .dashedBorder(
                                    width = if (isOverlaySelected) 2.5.dp else 1.5.dp,
                                    color = if (isOverlaySelected) Color(0xFFFFD600) else Color(0x99FFD600),
                                    cornerRadius = 6.dp
                                )
                                .pointerInput(overlay.id) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        viewModel.selectElement(overlay.id)
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
                                        onTap = { viewModel.selectElement(overlay.id) },
                                        onDoubleTap = { viewModel.removeOverlay(overlay.id) }
                                    )
                                }
                        ) {
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = "Overlay",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            Surface(
                                shape = RoundedCornerShape(bottomEnd = 6.dp),
                                color = if (isOverlaySelected) Color(0xFFFFD600) else Color(0x66FFD600),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Text(
                                    "OVERLAY ${index + 1}",
                                    color = Color.Black,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "💡 Tap any section to select • Drag to move • Pinch to scale",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Precision Adjuster Panel (Manual Position & Scale Sliders)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedId == "GAME_SCREEN") "📱 Adjust Game Screen" else {
                                val idx = viewModel.overlays.indexOfFirst { it.id == selectedId }
                                if (idx != -1) "🖼️ Adjust Overlay ${idx + 1}" else "⚙️ Element Controls"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedId == "GAME_SCREEN") Color(0xFF00E5FF) else Color(0xFFFFD600),
                            modifier = Modifier.weight(1f)
                        )

                        AssistChip(
                            onClick = {
                                if (selectedId == "GAME_SCREEN") {
                                    if (viewModel.overlays.isNotEmpty()) {
                                        viewModel.selectElement(viewModel.overlays.first().id)
                                    }
                                } else {
                                    viewModel.selectElement("GAME_SCREEN")
                                }
                            },
                            label = {
                                Text(if (selectedId == "GAME_SCREEN") "Edit Overlays" else "Edit Game Screen")
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedId == "GAME_SCREEN") {
                        // Game Screen sliders
                        Text("Size / Scale: ${viewModel.gameScreenScalePercent.value.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = viewModel.gameScreenScalePercent.value,
                            onValueChange = { viewModel.updateGameScreenScale(it) },
                            valueRange = 20f..100f
                        )

                        Text("Horizontal Position (X): ${viewModel.gameScreenXPercent.value.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = viewModel.gameScreenXPercent.value,
                            onValueChange = { viewModel.updateGameScreenPosition(it, viewModel.gameScreenYPercent.value) },
                            valueRange = 0f..100f
                        )

                        Text("Vertical Position (Y): ${viewModel.gameScreenYPercent.value.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = viewModel.gameScreenYPercent.value,
                            onValueChange = { viewModel.updateGameScreenPosition(viewModel.gameScreenXPercent.value, it) },
                            valueRange = 0f..100f
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateGameScreenPosition(0f, 0f)
                                    viewModel.updateGameScreenScale(100f)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Top (Fit Width)", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    viewModel.updateGameScreenPosition(0f, 9f)
                                    viewModel.updateGameScreenScale(100f)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Center", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    viewModel.updateGameScreenPosition(0f, 18f)
                                    viewModel.updateGameScreenScale(100f)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Bottom", fontSize = 11.sp) }
                        }
                    } else {
                        val currentOverlay = viewModel.overlays.find { it.id == selectedId }
                        if (currentOverlay != null) {
                            Text("Size / Scale: ${currentOverlay.scalePercent.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = currentOverlay.scalePercent,
                                onValueChange = { viewModel.updateOverlayScale(currentOverlay.id, it) },
                                valueRange = 5f..100f
                            )

                            Text("Horizontal Position (X): ${currentOverlay.xPercent.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = currentOverlay.xPercent,
                                onValueChange = { viewModel.updateOverlayPosition(currentOverlay.id, it, currentOverlay.yPercent) },
                                valueRange = 0f..100f
                            )

                            Text("Vertical Position (Y): ${currentOverlay.yPercent.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = currentOverlay.yPercent,
                                onValueChange = { viewModel.updateOverlayPosition(currentOverlay.id, currentOverlay.xPercent, it) },
                                valueRange = 0f..100f
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.updateOverlayPosition(currentOverlay.id, currentOverlay.xPercent, 0f) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Top", fontSize = 11.sp) }

                                OutlinedButton(
                                    onClick = {
                                        val centeredX = (50f - currentOverlay.scalePercent / 2f).coerceAtLeast(0f)
                                        val centeredY = (50f - currentOverlay.scalePercent / 2f).coerceAtLeast(0f)
                                        viewModel.updateOverlayPosition(currentOverlay.id, centeredX, centeredY)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Center", fontSize = 11.sp) }

                                OutlinedButton(
                                    onClick = {
                                        val bottomY = (100f - currentOverlay.scalePercent).coerceAtLeast(0f)
                                        viewModel.updateOverlayPosition(currentOverlay.id, currentOverlay.xPercent, bottomY)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Bottom", fontSize = 11.sp) }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateOverlayPosition(currentOverlay.id, 0f, 0f)
                                        viewModel.updateOverlayScale(currentOverlay.id, 100f)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Fit", fontSize = 11.sp) }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "🟢 Chroma Key (Green Screen)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Switch(
                                    checked = currentOverlay.chromaKey,
                                    onCheckedChange = { viewModel.updateOverlayChromaKey(currentOverlay.id, it) }
                                )
                            }
                        } else {
                            Text("Tap any overlay on the preview canvas or in the list below to select and adjust it.", color = Color.Gray, fontSize = 12.sp)
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
