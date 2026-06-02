package com.blurt.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.util.AppSegment
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 颜色规则 =====
private data class AppPalette(val fill: Color, val border: Color)

private fun paletteFor(pkg: String, appName: String): AppPalette {
    val key = "${pkg.lowercase()} ${appName.lowercase()}"
    fun has(vararg ks: String) = ks.any { key.contains(it) }
    return when {
        has("wechat", "tencent.mm", "qq", "weibo", "telegram", "微信", "通讯") ->
            AppPalette(Color(0xFFE3F2FD), Color(0xFF64B5F6))
        has("youtube", "tiktok", "bilibili", "douyin", "netflix", "music", "spotify", "抖音", "视频", "音乐") ->
            AppPalette(Color(0xFFFFF3E0), Color(0xFFFFB74D))
        has("chrome", "browser", "edge", "firefox", "docs", "kindle", "notion", "obsidian", "学习", "阅读", "doc") ->
            AppPalette(Color(0xFFE8F5E9), Color(0xFF81C784))
        has("androidstudio", "idea", "code", "vscode", "termux", "git", "dev") ->
            AppPalette(Color(0xFFF3E5F5), Color(0xFFBA68C8))
        else -> AppPalette(Color(0xFFF5F5F5), Color(0xFFBDBDBD))
    }
}

private val IdleGray = Color(0x4D9E9E9E)
private val LocationDot = Color(0xFF4CAF50)
private val NowLineColor = Color(0xFFE53935)

// 时间轴布局常量
private val SCALE_WIDTH = 48.dp
private val LINE_X = 56.dp
private val EVENT_LEFT_PADDING = 8.dp
private val RIGHT_PADDING = 12.dp
private const val MIN_HOUR_HEIGHT = 32f
private const val MAX_HOUR_HEIGHT = 240f
private const val DEFAULT_HOUR_HEIGHT = 64f

// 用于泳道布局
private data class PositionedSegment(
    val seg: AppSegment,
    val lane: Int,
    val totalLanes: Int,
)

/**
 * 给 segments 分配「泳道」(lane)：同一泳道内的段在时间上不重叠。
 * 贪心算法：按 startTime 排序，每个段放进第一个空闲泳道；都不空则新开。
 */
private fun assignLanes(segments: List<AppSegment>): List<PositionedSegment> {
    val laneEnds = mutableListOf<Long>()
    val sorted = segments.sortedBy { it.startTime }
    val firstPass = mutableListOf<Pair<AppSegment, Int>>()
    for (seg in sorted) {
        var assigned = -1
        for (i in laneEnds.indices) {
            if (laneEnds[i] <= seg.startTime) {
                laneEnds[i] = seg.endTime
                assigned = i
                break
            }
        }
        if (assigned == -1) {
            laneEnds.add(seg.endTime)
            assigned = laneEnds.size - 1
        }
        firstPass.add(seg to assigned)
    }
    val total = laneEnds.size.coerceAtLeast(1)
    return firstPass.map { (s, l) -> PositionedSegment(s, l, total) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(vm: TimelineViewModel = viewModel()) {
    val segments by vm.appSegments.collectAsState()
    val locations by vm.locationRecords.collectAsState()
    val screens by vm.screenEvents.collectAsState()
    val summary by vm.todaySummary.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    var sheetSegment by remember { mutableStateOf<AppSegment?>(null) }
    var sheetLocation by remember { mutableStateOf<LocationRecord?>(null) }
    val sheetState = rememberModalBottomSheetState()

    Column(Modifier.fillMaxSize()) {
        StatsRow(summary)
        HorizontalDivider()
        TimelineCanvas(
            segments = segments,
            locations = locations,
            screens = screens,
            onSegmentClick = { sheetSegment = it },
            onLocationClick = { sheetLocation = it },
        )
    }

    sheetSegment?.let { seg ->
        ModalBottomSheet(onDismissRequest = { sheetSegment = null }, sheetState = sheetState) {
            SegmentDetail(seg, locations)
        }
    }
    sheetLocation?.let { loc ->
        ModalBottomSheet(onDismissRequest = { sheetLocation = null }, sheetState = sheetState) {
            LocationDetail(loc)
        }
    }
}

// =====================  Stats Row  =====================

@Composable
private fun StatsRow(s: TodaySummary) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCell("亮屏次数", "${s.screenOnCount}次", Modifier.weight(1f))
        StatCell("屏幕总时长", formatHM(s.totalScreenTimeMs), Modifier.weight(1f))
        StatCell("使用App数", "${s.appCount}个", Modifier.weight(1f))
        StatCell("位置变化", "${s.locationCount}处", Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(1.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

// =====================  Canvas Timeline  =====================

@Composable
private fun TimelineCanvas(
    segments: List<AppSegment>,
    locations: List<LocationRecord>,
    screens: List<ScreenEvent>,
    onSegmentClick: (AppSegment) -> Unit,
    onLocationClick: (LocationRecord) -> Unit,
) {
    val scroll = rememberScrollState()
    val totalHours = TimelineViewModel.TIMELINE_END_HOUR - TimelineViewModel.TIMELINE_START_HOUR
    val dayStart = TimelineViewModel.startOfToday()
    val zeroMs = dayStart + TimelineViewModel.TIMELINE_START_HOUR * 3600_000L

    // 双指缩放：调整每小时高度
    var hourHeightDp by remember { mutableFloatStateOf(DEFAULT_HOUR_HEIGHT) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        hourHeightDp = (hourHeightDp * zoomChange).coerceIn(MIN_HOUR_HEIGHT, MAX_HOUR_HEIGHT)
    }

    val totalHeight = (hourHeightDp * totalHours).dp

    // 当前时间，每分钟刷新
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000)
        }
    }

    // 启动滚到当前时间附近
    LaunchedEffect(hourHeightDp) {
        val nowY = msToDpValue(nowMs - zeroMs, hourHeightDp)
        val target = (nowY - 200f).coerceAtLeast(0f).toInt()
        scroll.scrollTo(target)
    }

    // 泳道分配
    val positioned = remember(segments) { assignLanes(segments) }
    val maxLanes = positioned.maxOfOrNull { it.totalLanes } ?: 1

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformState)
            .verticalScroll(scroll),
    ) {
        val plotWidth = maxWidth - LINE_X - EVENT_LEFT_PADDING - RIGHT_PADDING
        val laneGap = 4.dp
        val laneWidth = (plotWidth - laneGap * (maxLanes - 1)) / maxLanes

        Box(
            Modifier.fillMaxWidth().height(totalHeight),
        ) {
            TimeScaleCanvas(totalHours, hourHeightDp)

            // 息屏灰块（占满整个泳道区域宽度）
            screenIdlesFromEvents(screens, zeroMs).forEach { (s, e) ->
                IdleBlock(
                    startMs = s, endMs = e, zeroMs = zeroMs,
                    hourHeightDp = hourHeightDp,
                    width = plotWidth,
                )
            }

            // App 段卡片（按泳道）
            positioned.forEach { ps ->
                val xOff = LINE_X + EVENT_LEFT_PADDING +
                    (laneWidth + laneGap) * ps.lane
                AppSegmentCard(
                    ps = ps,
                    zeroMs = zeroMs,
                    hourHeightDp = hourHeightDp,
                    xOffset = xOff,
                    width = laneWidth,
                ) { onSegmentClick(ps.seg) }
            }

            // 位置点
            locations.forEach { loc ->
                LocationDotMark(loc, zeroMs, hourHeightDp) { onLocationClick(loc) }
            }

            // 当前时间红线
            NowLine(nowMs = nowMs, zeroMs = zeroMs, hourHeightDp = hourHeightDp)
        }
    }
}

@Composable
private fun TimeScaleCanvas(totalHours: Int, hourHeightDp: Float) {
    val labelColor = Color.Gray
    val lineColor = Color(0xFFE0E0E0)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val hourPx = hourHeightDp.dp.toPx()
        val linePx = LINE_X.toPx()

        // 中间竖线
        drawLine(
            color = lineColor,
            start = Offset(linePx, 0f),
            end = Offset(linePx, size.height),
            strokeWidth = 1.dp.toPx(),
        )

        // 每小时刻度横线
        val tickW = 8.dp.toPx()
        for (h in 0..totalHours) {
            val y = h * hourPx
            drawLine(
                color = lineColor,
                start = Offset(linePx - tickW / 2f, y),
                end = Offset(linePx + tickW / 2f, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // 小时文字标签
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
                isAntiAlias = true
            }
            for (h in 0..totalHours) {
                val hourLabel = "%02d:00".format(TimelineViewModel.TIMELINE_START_HOUR + h)
                val y = h * hourPx + paint.textSize / 2f
                canvas.nativeCanvas.drawText(hourLabel, 4f, y, paint)
            }
        }
    }
}

@Composable
private fun AppSegmentCard(
    ps: PositionedSegment,
    zeroMs: Long,
    hourHeightDp: Float,
    xOffset: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val seg = ps.seg
    val yDp = msToDpValue(seg.startTime - zeroMs, hourHeightDp).dp
    val heightDp = msToDpValue(seg.durationMs, hourHeightDp).dp.coerceAtLeast(24.dp)
    val palette = paletteFor(seg.packageName, seg.appName)

    Box(
        modifier = Modifier
            .offset(x = xOffset, y = yDp)
            .width(width)
            .height(heightDp)
            .background(palette.fill, RoundedCornerShape(8.dp))
            .drawBehind {
                drawRoundRect(
                    color = palette.border,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
            }
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
            Text(
                "📱 ${seg.appName}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (heightDp >= 36.dp) {
                Text(
                    formatHM(seg.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray,
                )
            }
        }
    }
}

@Composable
private fun IdleBlock(
    startMs: Long,
    endMs: Long,
    zeroMs: Long,
    hourHeightDp: Float,
    width: androidx.compose.ui.unit.Dp,
) {
    val yDp = msToDpValue(startMs - zeroMs, hourHeightDp).dp.coerceAtLeast(0.dp)
    val heightDp = msToDpValue(endMs - startMs, hourHeightDp).dp
    Box(
        modifier = Modifier
            .offset(x = LINE_X + EVENT_LEFT_PADDING, y = yDp)
            .width(width)
            .height(heightDp)
            .background(IdleGray, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (endMs - startMs >= 30 * 60_000L) {
            Text("💤 ${formatHM(endMs - startMs)}",
                style = MaterialTheme.typography.labelMedium, color = Color.DarkGray)
        }
    }
}

@Composable
private fun LocationDotMark(
    loc: LocationRecord,
    zeroMs: Long,
    hourHeightDp: Float,
    onClick: () -> Unit,
) {
    val yDp = msToDpValue(loc.timestamp - zeroMs, hourHeightDp).dp - 4.dp
    Box(
        modifier = Modifier
            .offset(x = LINE_X - 4.dp, y = yDp)
            .width(8.dp).height(8.dp)
            .background(LocationDot, RoundedCornerShape(50))
            .clickable { onClick() },
    )
}

@Composable
private fun NowLine(nowMs: Long, zeroMs: Long, hourHeightDp: Float) {
    val yDp = msToDpValue(nowMs - zeroMs, hourHeightDp).dp
    if (yDp < 0.dp) return
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(
        modifier = Modifier
            .offset(y = yDp)
            .fillMaxWidth()
            .height(1.dp)
            .background(NowLineColor),
    )
    Box(
        modifier = Modifier
            .offset(x = 2.dp, y = yDp - 8.dp)
            .background(NowLineColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(timeFmt.format(Date(nowMs)), color = Color.White,
            style = MaterialTheme.typography.labelSmall)
    }
}

// =====================  Bottom sheet details  =====================

@Composable
private fun SegmentDetail(seg: AppSegment, locations: List<LocationRecord>) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val matching = locations.firstOrNull { it.timestamp in seg.startTime..seg.endTime }
        ?: locations.minByOrNull { kotlin.math.abs(it.timestamp - seg.startTime) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("📱 ${seg.appName}", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(seg.packageName, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Text("使用时长：${formatHM(seg.durationMs)}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "${timeFmt.format(Date(seg.startTime))} → ${timeFmt.format(Date(seg.endTime))}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (matching != null) {
            Spacer(Modifier.height(8.dp))
            Text("在这段时间你在：${matching.address}",
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LocationDetail(loc: LocationRecord) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showCoord by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("📍 ${loc.address.ifEmpty { "未知位置" }}",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(timeFmt.format(Date(loc.timestamp)),
            style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (showCoord)
                "坐标 ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
            else "查看坐标",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF1976D2),
            modifier = Modifier.clickable { showCoord = !showCoord },
        )
        Spacer(Modifier.height(20.dp))
    }
}

// =====================  helpers  =====================

/** ms 转 DP 值（不带 .dp 单位），用于 offset/height 计算 */
private fun msToDpValue(ms: Long, hourHeightDp: Float): Float {
    return (ms / 3600_000f) * hourHeightDp
}

private fun screenIdlesFromEvents(
    events: List<ScreenEvent>,
    zeroMs: Long,
): List<Pair<Long, Long>> {
    val out = mutableListOf<Pair<Long, Long>>()
    var lastOff: Long? = null
    val sorted = events.sortedBy { it.timestamp }
    for (e in sorted) {
        when (e.eventType) {
            "息屏" -> lastOff = e.timestamp
            "亮屏", "解锁" -> {
                lastOff?.let { off ->
                    if (e.timestamp - off >= 5 * 60_000L) out += off to e.timestamp
                }
                lastOff = null
            }
        }
    }
    lastOff?.let { off ->
        val now = System.currentTimeMillis()
        if (now - off >= 5 * 60_000L) out += off to now
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
