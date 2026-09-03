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
                    
                    viewModel.qualityPresets.forEach { preset ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = viewModel.selectedQualityPreset.value == preset,
                                onClick = { 
                                    viewModel.selectQualityPreset(preset)
                                }
                            )
                            Text(
                                text = preset.displayLabel, 
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (viewModel.selectedQualityPreset.value == preset) 
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Large Screen Quality Boost",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Improves GPU reconstruction and detail retention for large laptop and desktop displays. Uses additional GPU processing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.isLargeScreenQualityBoostEnabled.value,
                            onCheckedChange = {
                                viewModel.isLargeScreenQualityBoostEnabled.value = it
                                viewModel.onFilterChanged()
                            }
                        )
                    }
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
                    Text("Stream Orientation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                }
            }
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Chroma Key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Green screen removal for stream overlays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            // Gameplay Color & Visual Enhancer
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Gameplay Color Enhancer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Hardware GPU color grading & sharpness",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        // Section: Color
                        Text(
                            "Color Tuning",
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
                            enabled = isEnabled,
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
                            enabled = isEnabled,
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
                            enabled = isEnabled,
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
                            enabled = isEnabled,
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
                            enabled = isEnabled,
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
                        "🎙️ Microphone Volume & Audio Filters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 1. Microphone Volume Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Microphone Volume",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${viewModel.micVolumePercent.value.toInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = viewModel.micVolumePercent.value,
                        onValueChange = {
                            viewModel.updateMicVolume(it.toInt().toFloat())
                        },
                        valueRange = 0f..200f,
                        steps = 39, // 5% step intervals (0, 5, 10, ... 200)
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0% (Silent)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("80% (Default)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("100% (1.0x)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("200% (2.0x)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Noise Suppression
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
                                        putExtra("micVolumePercent", viewModel.micVolumePercent.value)
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

                    // 3. Voice Clarity
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
                                        putExtra("micVolumePercent", viewModel.micVolumePercent.value)
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
