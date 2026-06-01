package com.blurt.tracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blurt.tracker.data.AppRecord
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.ScreenEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val BlueCard = Color(0xFFE3F2FD)
private val GreenCard = Color(0xFFE8F5E9)
private val GrayCard = Color(0x33888888)
private val YellowChip = Color(0xFFFFF59D)
private val RedCard = Color(0xFFFFCDD2) // 预留：烂摊子记录

@Composable
fun TimelineScreen(vm: TimelineViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        DateHeader(
            dayStart = s.dayStart,
            label = if (s.dayStart == startOfToday()) "今天" else dateFmt.format(Date(s.dayStart)),
            onPrev = vm::goPrevDay,
            onNext = vm::goNextDay,
            onToday = vm::goToday,
        )
        StatsRow(s.stats)
        HorizontalDivider()
        if (s.items.isEmpty()) {
            EmptyHint()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(s.items, key = { it.time.toString() + it::class.simpleName }) { item ->
                    TimelineRow(item)
                }
            }
        }
    }
}

// ---------- Header ----------
@Composable
private fun DateHeader(
    dayStart: Long,
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val isToday = dayStart == startOfToday()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Text("◀") }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !isToday) { onToday() },
        )
        IconButton(
            onClick = onNext,
            enabled = !isToday,
        ) { Text(if (isToday) "▶" else "▶", color = if (isToday) Color.Gray else Color.Unspecified) }
    }
}

// ---------- Stats row ----------
@Composable
private fun StatsRow(stats: TimelineStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCell("亮屏次数", "${stats.screenOnCount}次", Modifier.weight(1f))
        StatCell("屏幕时间", formatHM(stats.screenTimeMs), Modifier.weight(1f))
        StatCell("使用App数", "${stats.appCount}个", Modifier.weight(1f))
        StatCell("位置变化", "${stats.locationChanges}处", Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(1.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

// ---------- Timeline row（左侧时刻 + 右侧卡片） ----------
@Composable
private fun TimelineRow(item: TLItem) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = timeFmt.format(Date(item.time)),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .width(52.dp)
                .padding(top = 10.dp),
            color = Color.Gray,
        )
        // 时间轴竖线
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(if (item is TLItem.Idle) idleHeightDp(item.durationMs) else 56.dp)
                .padding(vertical = 4.dp)
                .background(Color(0xFFBDBDBD)),
        )
        Spacer(Modifier.width(8.dp))
        when (item) {
            is TLItem.App -> AppCard(item.record)
            is TLItem.Loc -> LocCard(item.record)
            is TLItem.Screen -> ScreenChip(item.event)
            is TLItem.Idle -> IdleBlock(item)
        }
    }
}

// ---------- 卡片 ----------
@Composable
private fun AppCard(r: AppRecord) {
    var expanded by remember { mutableStateOf(false) }
    val durMin = ((r.endTime - r.startTime) / 60_000L).coerceAtLeast(1)
    ColoredCard(BlueCard, onClick = { expanded = !expanded }) {
        Text("📱 ${r.appName}", fontWeight = FontWeight.SemiBold)
        Text(formatMinutes(durMin), style = MaterialTheme.typography.bodySmall)
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 6.dp)) {
                Text("包名：${r.packageName}", style = MaterialTheme.typography.labelSmall)
                val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                Text(
                    "${timeFmt.format(Date(r.startTime))} → ${timeFmt.format(Date(r.endTime))}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun LocCard(r: LocationRecord) {
    var expanded by remember { mutableStateOf(false) }
    ColoredCard(GreenCard, onClick = { expanded = !expanded }) {
        Text("📍 到达 ${r.address}", fontWeight = FontWeight.SemiBold)
        AnimatedVisibility(expanded) {
            Text(
                "${"%.5f".format(r.latitude)}, ${"%.5f".format(r.longitude)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ScreenChip(e: ScreenEvent) {
    Surface(
        color = YellowChip,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Text(
            text = "🔓 ${e.eventType}手机",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun IdleBlock(item: TLItem.Idle) {
    val deep = item.durationMs >= TimelineViewModel.IDLE_DEEP_MS
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(idleHeightDp(item.durationMs))
            .background(GrayCard, RoundedCornerShape(8.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (deep) "💤 休息中 · ${formatHM(item.durationMs)}"
            else "休息中 · ${formatHM(item.durationMs)}",
            color = Color(0xFF555555),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColoredCard(
    bg: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("今天没有数据", color = Color.Gray)
    }
}

// ---------- 工具 ----------
private fun idleHeightDp(durMs: Long) = when {
    durMs < 30 * 60_000L -> 48.dp
    durMs < 2 * 3600_000L -> 80.dp
    durMs < 4 * 3600_000L -> 120.dp
    else -> 160.dp
}

private fun formatHM(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}分"
        m == 0L -> "${h}小时"
        else -> "${h}小时${m}分"
    }
}

private fun formatMinutes(min: Long): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}小时${m}分" else "${min}分钟"
}

private fun startOfToday(): Long {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}
