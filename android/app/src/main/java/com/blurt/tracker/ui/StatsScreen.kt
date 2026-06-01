package com.blurt.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun StatsScreen(vm: TimelineViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("📊 今日统计", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        StatLine("亮屏次数", "${s.stats.screenOnCount} 次")
        StatLine("屏幕总时长", formatHMS(s.stats.screenTimeMs))
        StatLine("使用 App 数", "${s.stats.appCount} 个")
        StatLine("位置变化", "${s.stats.locationChanges} 处")
        Text("（更详细的图表统计待开发）",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatHMS(ms: Long): String {
    val total = ms / 60_000L
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分"
}
