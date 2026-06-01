package com.blurt.tracker.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class Tab(val label: String, val icon: String) {
    Dashboard("主页", "🗑️"),
    Timeline("时间轴", "📅"),
    Locations("位置", "📍"),
    Stats("统计", "📊"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var current by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Text(tab.icon) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // 让每个子页自己处理 padding
        when (current) {
            Tab.Dashboard -> Padded(padding) { DashboardScreen() }
            Tab.Timeline -> Padded(padding) { TimelineScreen() }
            Tab.Locations -> Padded(padding) { LocationsScreen() }
            Tab.Stats -> Padded(padding) { StatsScreen() }
        }
    }
}

@Composable
private fun Padded(padding: PaddingValues, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
    ) { content() }
}
