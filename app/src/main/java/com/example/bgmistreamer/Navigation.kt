package com.example.bgmistreamer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bgmistreamer.ui.main.MainScreen
import com.example.bgmistreamer.ui.main.SettingsScreen

@Composable
fun MainNavigation() {
    val viewModel: StreamViewModel = viewModel()
    var selectedTabIndex by remember { mutableStateOf(0) }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Text("▶") },
                    label = { Text("Live Studio") }
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Text("⚙") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTabIndex == 0) {
                MainScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            } else {
                SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
