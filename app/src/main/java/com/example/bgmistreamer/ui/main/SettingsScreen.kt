package com.example.bgmistreamer.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bgmistreamer.StreamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: StreamViewModel, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(top = 24.dp)
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
                modifier = Modifier.fillMaxWidth()
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
        }
    }
}
