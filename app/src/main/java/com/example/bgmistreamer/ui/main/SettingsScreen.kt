package com.example.bgmistreamer.ui.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bgmistreamer.StreamService
import com.example.bgmistreamer.StreamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: StreamViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(top = 24.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Broadcast Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    OutlinedTextField(
                        value = viewModel.rtmpUrl.value,
                        onValueChange = { 
                            viewModel.rtmpUrl.value = it 
                            viewModel.saveSettings()
                        },
                        label = { Text("RTMP Server URL") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        )
                    )
                    
                    OutlinedTextField(
                        value = viewModel.streamKey.value,
                        onValueChange = { 
                            viewModel.streamKey.value = it 
                            viewModel.saveSettings()
                        },
                        label = { Text("Stream Key") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        )
                    )
                }
            }
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Stream Quality",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    viewModel.qualities.forEach { quality ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = viewModel.selectedQuality.value == quality,
                                onClick = { 
                                    viewModel.selectedQuality.value = quality 
                                    viewModel.saveSettings()
                                }
                            )
                            Text(
                                text = quality, 
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (viewModel.selectedQuality.value == quality) 
                                        MaterialTheme.colorScheme.onSurface 
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Stream Orientation", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Portrait", modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.isLandscapeOrientation.value,
                            onCheckedChange = { 
                                viewModel.isLandscapeOrientation.value = it 
                                viewModel.saveSettings()
                            }
                        )
                        Text(text = "Landscape", modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Experimental", style = MaterialTheme.typography.titleMedium)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Chroma Key", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("Green screen removal for overlays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = viewModel.isChromaKeyEnabled.value,
                        onCheckedChange = { 
                            viewModel.isChromaKeyEnabled.value = it 
                            viewModel.saveSettings()
                        }
                    )
                }
            }

            // Phase 17 — Controlled Downsampling Filter A/B/C/D/E Test
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "🔬 Phase 17 Downsampling Filter Test",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text(
                        "Select Downsampling Mode (2400x1080 -> 1920x864):",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    viewModel.downsampleTestModes.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = viewModel.selectedDownsampleTestMode.value == mode,
                                onClick = {
                                    viewModel.selectedDownsampleTestMode.value = mode
                                    viewModel.saveSettings()
                                }
                            )
                            Text(
                                text = mode,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (viewModel.selectedDownsampleTestMode.value == mode)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "📐 GPU Resolution Test Pattern",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Procedural 1px/2px/Nyquist/diagonal pattern directly on GPU (Step 3)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.isTestPatternEnabled.value,
                            onCheckedChange = {
                                viewModel.isTestPatternEnabled.value = it
                                viewModel.saveSettings()
                            }
                        )
                    }
                }
            }

            // ==========================================
            // FILTER SETTINGS SECTION (Phase 18C Clean Layout)
            // ==========================================
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // Header with Enable Filter ON / OFF Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "FILTER SETTINGS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Enable Filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Switch(
                            checked = viewModel.isGameplayFilterEnabled.value,
                            onCheckedChange = {
                                viewModel.isGameplayFilterEnabled.value = it
                                viewModel.onFilterChanged()
                            }
                        )
                    }

                    val isEnabled = viewModel.isGameplayFilterEnabled.value

                    if (com.example.bgmistreamer.BuildConfig.DEBUG && isEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (viewModel.isExtremeFilterTestEnabled.value)
                                    MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            "🧪 FORENSIC PROOF TEST (Extreme)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (viewModel.isExtremeFilterTestEnabled.value)
                                                MaterialTheme.colorScheme.onErrorContainer
                                            else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Hard-codes gameplay region to solid proof color to verify active encoder render path in YouTube.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (viewModel.isExtremeFilterTestEnabled.value)
                                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = viewModel.isExtremeFilterTestEnabled.value,
                                        onCheckedChange = {
                                            viewModel.isExtremeFilterTestEnabled.value = it
                                            viewModel.onFilterChanged()
                                        }
                                    )
                                }

                                if (viewModel.isExtremeFilterTestEnabled.value) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val modes = listOf(1 to "TEST 1 (RED)", 2 to "TEST 2 (BLUE)", 3 to "TEST 3 (GREEN)")
                                        modes.forEach { (idx, label) ->
                                            val isSelected = viewModel.extremeFilterTestIndex.value == idx
                                            FilledTonalButton(
                                                onClick = {
                                                    viewModel.extremeFilterTestIndex.value = idx
                                                    viewModel.onFilterChanged()
                                                },
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        // Section: Color
                        Text(
                            "Color",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // 1. Gamma Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Gamma",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "%.2f".format(viewModel.gameplayGamma.value),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = viewModel.gameplayGamma.value,
                            onValueChange = {
                                viewModel.gameplayGamma.value = (it * 100).toInt() / 100f
                                viewModel.onFilterChanged()
                            },
                            valueRange = 0.0f..0.40f,
                            enabled = isEnabled && !viewModel.isExtremeFilterTestEnabled.value,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        // 2. Contrast Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Contrast",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "%.2f".format(viewModel.gameplayContrast.value),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = viewModel.gameplayContrast.value,
                            onValueChange = {
                                viewModel.gameplayContrast.value = (it * 100).toInt() / 100f
                                viewModel.onFilterChanged()
                            },
                            valueRange = -0.10f..0.20f,
                            enabled = isEnabled && !viewModel.isExtremeFilterTestEnabled.value,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        // 3. Brightness Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Brightness",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "%.4f".format(viewModel.gameplayBrightness.value),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = viewModel.gameplayBrightness.value,
                            onValueChange = {
                                viewModel.gameplayBrightness.value = (it * 10000).toInt() / 10000f
                                viewModel.onFilterChanged()
                            },
                            valueRange = -0.05f..0.05f,
                            enabled = isEnabled && !viewModel.isExtremeFilterTestEnabled.value,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        // 4. Saturation Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Saturation",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "%.2f".format(viewModel.gameplaySaturation.value),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = viewModel.gameplaySaturation.value,
                            onValueChange = {
                                viewModel.gameplaySaturation.value = (it * 100).toInt() / 100f
                                viewModel.onFilterChanged()
                            },
                            valueRange = 0.50f..1.50f,
                            enabled = isEnabled && !viewModel.isExtremeFilterTestEnabled.value,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        // Section: Sharpness
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Sharpness",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Sharpness",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "%.2f".format(viewModel.gameplaySharpness.value),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = viewModel.gameplaySharpness.value,
                            onValueChange = {
                                viewModel.gameplaySharpness.value = (it * 100).toInt() / 100f
                                viewModel.onFilterChanged()
                            },
                            valueRange = 0.0f..1.0f,
                            enabled = isEnabled && !viewModel.isExtremeFilterTestEnabled.value,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        // Divider
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )

                        // Current Values Summary Table
                        Text(
                            "Current Values",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            @Composable
                            fun ValueItem(label: String, value: String) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }

                            ValueItem("Gamma", "%.2f".format(viewModel.gameplayGamma.value))
                            ValueItem("Contrast", "%.2f".format(viewModel.gameplayContrast.value))
                            ValueItem("Brightness", "%.4f".format(viewModel.gameplayBrightness.value))
                            ValueItem("Saturation", "%.2f".format(viewModel.gameplaySaturation.value))
                            ValueItem("Sharpness", "%.2f".format(viewModel.gameplaySharpness.value))
                        }

                        // Divider
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )

                        // Reset to Default Button
                        OutlinedButton(
                            onClick = {
                                viewModel.resetGameplayFilterDefaults()
                                android.widget.Toast.makeText(
                                    context,
                                    "Filter settings reset to defaults",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Reset to Default", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Audio Processing & Microphone Filters
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "🎙️ Audio Processing Filters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 1. Noise Suppression
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Microphone Noise Filter", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Gentle plosive & rumble filter for mic without affecting game sound", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = viewModel.isNoiseSuppressorEnabled.value,
                            onCheckedChange = {
                                viewModel.isNoiseSuppressorEnabled.value = it
                                viewModel.saveSettings()
                                if (StreamService.isStreamingState.value) {
                                    val intent = Intent(context, StreamService::class.java).apply {
                                        action = "UPDATE_AUDIO_SETTINGS"
                                        putExtra("noiseSuppressor", it)
                                        putExtra("echoCanceler", viewModel.isEchoCancelerEnabled.value)
                                    }
                                    context.startService(intent)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Voice Clarity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Voice Clarity Boost", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Subtle vocal presence boost without ducking or muffling game sound", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = viewModel.isEchoCancelerEnabled.value,
                            onCheckedChange = {
                                viewModel.isEchoCancelerEnabled.value = it
                                viewModel.saveSettings()
                                if (StreamService.isStreamingState.value) {
                                    val intent = Intent(context, StreamService::class.java).apply {
                                        action = "UPDATE_AUDIO_SETTINGS"
                                        putExtra("noiseSuppressor", viewModel.isNoiseSuppressorEnabled.value)
                                        putExtra("echoCanceler", it)
                                    }
                                    context.startService(intent)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
