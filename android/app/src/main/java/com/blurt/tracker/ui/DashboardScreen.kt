package com.blurt.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blurt.tracker.data.AppRecord
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.util.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val desktop by vm.desktop.collectAsState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("烂摊子 · 今日") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            DesktopStatusCard(
                state = desktop,
                onRetest = { vm.pingNow() },
                onIpChanged = { vm.onDesktopIpChanged() },
            )
            Spacer(Modifier.height(12.dp))

            SummaryCard(totalMillis = state.totalScreenMillis, onClear = { vm.clearToday() })
            Spacer(Modifier.height(12.dp))

            if (state.itemsByHour.isEmpty()) {
                Text(
                    "今天还没有数据。\n请确认前台服务已启动，并授予了所需权限。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.itemsByHour, key = { it.first }) { (hour, items) ->
                        HourBlock(hour = hour, items = items, timeFmt = timeFmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopStatusCard(
    state: DesktopUiState,
    onRetest: () -> Unit,
    onIpChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var showEdit by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("🖥️ 游戏本", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.ip ?: "未配置",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 12.dp),
                )
                StatusBadge(state.status)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showEdit = true }) { Text("修改IP") }
                Button(onClick = onRetest) { Text("重新测试") }
            }
        }
    }

    if (showEdit) {
        EditIpDialog(
            initialIp = state.ip ?: "",
            onDismiss = { showEdit = false },
            onSave = { ip ->
                Config.setDesktopIp(ctx, ip)
                showEdit = false
                onIpChanged()
            },
        )
    }
}

@Composable
private fun StatusBadge(status: DesktopStatus) {
    val (text, color) = when (status) {
        is DesktopStatus.Online -> "✅ 在线 ${status.latencyMs}ms" to Color(0xFF2E7D32)
        is DesktopStatus.Offline -> "❌ 离线 (${status.reason})" to Color(0xFFC62828)
        DesktopStatus.Pinging -> "… 测试中" to Color(0xFF555555)
        DesktopStatus.Unknown -> "… 等待测试" to Color(0xFF555555)
        DesktopStatus.NotConfigured -> "⚠️ 未配置 IP" to Color(0xFFB26A00)
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium)
}

@Composable
private fun EditIpDialog(
    initialIp: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var ip by remember { mutableStateOf(initialIp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改电脑 IP") },
        text = {
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("Tailscale IP") },
                    placeholder = { Text("例如 100.64.x.x") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ip.isNotBlank(),
                onClick = { onSave(ip) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SummaryCard(totalMillis: Long, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("今日总屏幕时长", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(formatDuration(totalMillis), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClear) { Text("清除今日数据") }
        }
    }
}

@Composable
private fun HourBlock(
    hour: Int,
    items: List<TimelineItem>,
    timeFmt: SimpleDateFormat,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "%02d:00".format(hour),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                TimelineRow(item, timeFmt)
            }
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, timeFmt: SimpleDateFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeFmt.format(Date(item.timestamp)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp),
        )
        when (item) {
            is TimelineItem.App -> AppRow(item.record)
            is TimelineItem.Loc -> LocRow(item.record)
        }
    }
}

@Composable
private fun AppRow(r: AppRecord) {
    val durMin = ((r.endTime - r.startTime) / 60_000L).coerceAtLeast(1)
    Text(
        text = "📱 App   ${r.appName} · ${formatMinutes(durMin)}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun LocRow(r: LocationRecord) {
    Text(
        text = "📍 位置   ${r.address}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}

private fun formatMinutes(min: Long): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}小时${m}分钟" else "${min}分钟"
}
