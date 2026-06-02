package com.blurt.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blurt.tracker.util.AppUsageInfo
import com.blurt.tracker.util.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(vm: TimelineViewModel = viewModel()) {
    val ctx = LocalContext.current
    val summary by vm.todaySummary.collectAsState()
    val segments by vm.appSegments.collectAsState()

    var top5 by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var weekly by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    LaunchedEffect(segments) {
        if (UsageStatsHelper.hasUsageStatsPermission(ctx)) {
            top5 = withContext(Dispatchers.IO) {
                UsageStatsHelper.getTodayAppUsage(ctx).take(5)
            }
            weekly = withContext(Dispatchers.IO) { computeWeeklyTotals(ctx) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("📊 今日 / 近 7 天 统计", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)

        // ----- Today summary numbers -----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BigStat("亮屏", "${summary.screenOnCount}次")
            BigStat("屏幕", formatHM(summary.totalScreenTimeMs))
            BigStat("App", "${summary.appCount}")
            BigStat("位置", "${summary.locationCount}")
        }

        // ----- Top 5 bars -----
        Text("Top 5 应用", style = MaterialTheme.typography.titleMedium)
        if (top5.isEmpty()) {
            Text("暂无数据", color = Color.Gray)
        } else {
            val maxMs = top5.first().totalTimeMs.coerceAtLeast(1)
            top5.forEach { info ->
                HorizontalBar(info, maxMs)
            }
        }

        // ----- 7-day line chart -----
        Text("过去 7 天屏幕时间（小时）", style = MaterialTheme.typography.titleMedium)
        if (weekly.isEmpty()) {
            Text("暂无数据", color = Color.Gray)
        } else {
            SevenDayLineChart(weekly)
        }
    }
}

@Composable
private fun BigStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
private fun HorizontalBar(info: AppUsageInfo, maxMs: Long) {
    val pct = info.totalTimeMs.toFloat() / maxMs
    val barColor = Color(0xFF64B5F6)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                info.appName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(formatHM(info.totalTimeMs), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color(0xFFE0E0E0), RoundedCornerShape(5.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(10.dp)
                    .background(barColor, RoundedCornerShape(5.dp)),
            )
        }
    }
}

@Composable
private fun SevenDayLineChart(data: List<Pair<String, Long>>) {
    val maxMs = (data.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(top = 8.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val padLeft = 32f
            val padBottom = 32f
            val padTop = 8f
            val padRight = 8f
            val plotW = size.width - padLeft - padRight
            val plotH = size.height - padBottom - padTop
            val axisColor = Color(0xFFBDBDBD)
            val lineColor = Color(0xFF1976D2)

            // 轴
            drawLine(axisColor,
                start = Offset(padLeft, padTop),
                end = Offset(padLeft, padTop + plotH),
                strokeWidth = 1f)
            drawLine(axisColor,
                start = Offset(padLeft, padTop + plotH),
                end = Offset(padLeft + plotW, padTop + plotH),
                strokeWidth = 1f)

            val n = data.size
            if (n == 0) return@Canvas
            val dx = if (n > 1) plotW / (n - 1) else 0f
            val toY = { ms: Long ->
                padTop + plotH - (ms.toFloat() / maxMs) * plotH
            }

            // 折线
            val path = Path()
            data.forEachIndexed { i, (_, ms) ->
                val x = padLeft + i * dx
                val y = toY(ms)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f))

            // 点
            data.forEachIndexed { i, (_, ms) ->
                drawCircle(lineColor, radius = 4f, center = Offset(padLeft + i * dx, toY(ms)))
            }

            // X 轴标签
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = axisColor.toArgb()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }
                data.forEachIndexed { i, (label, _) ->
                    canvas.nativeCanvas.drawText(
                        label, padLeft + i * dx - 14f,
                        padTop + plotH + 16f, paint,
                    )
                }
                // Y 轴最大值标签
                val maxHours = maxMs / 3600_000.0
                canvas.nativeCanvas.drawText("%.1fh".format(maxHours), 2f, padTop + 8f, paint)
                canvas.nativeCanvas.drawText("0", 2f, padTop + plotH, paint)
            }
        }
    }
}

private fun computeWeeklyTotals(ctx: android.content.Context): List<Pair<String, Long>> {
    val usm = ctx.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
        as android.app.usage.UsageStatsManager
    val out = mutableListOf<Pair<String, Long>>()
    val labelFmt = SimpleDateFormat("MM/dd", Locale.getDefault())

    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    for (i in 6 downTo 0) {
        val dayStart = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -i)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 3600_000L
        val stats = usm.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY, dayStart, dayEnd,
        ) ?: continue
        val total = stats.sumOf {
            // 排除本身 + 系统包
            if (it.packageName == ctx.packageName) 0L
            else it.totalTimeInForeground.coerceAtLeast(0L)
        }
        out += labelFmt.format(Date(dayStart)) to total
    }
    return out
}

private fun formatHM(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
