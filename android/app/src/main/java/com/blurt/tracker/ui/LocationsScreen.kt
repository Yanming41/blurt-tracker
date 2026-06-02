package com.blurt.tracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.blurt.tracker.data.LocationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationsScreen(vm: TimelineViewModel = viewModel()) {
    val locs by vm.locationRecords.collectAsState()

    if (locs.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📍", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(8.dp))
            Text("今天还没有位置记录", color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(
                "位置每 15 分钟自动采样一次",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
        }
        return
    }

    // 按地址聚合连续相同点（"在同一个地方待了多久"）
    val groups = remember(locs) { groupConsecutive(locs) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.first().id }) { group -> LocationGroupCard(group) }
    }
}

@Composable
private fun LocationGroupCard(group: List<LocationRecord>) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val first = group.first()
    val last = group.last()
    val durationMs = (last.timestamp - first.timestamp).coerceAtLeast(0L)
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📍", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        first.address.ifEmpty { "未知位置" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (group.size == 1)
                            timeFmt.format(Date(first.timestamp))
                        else
                            "${timeFmt.format(Date(first.timestamp))} - ${timeFmt.format(Date(last.timestamp))}  ·  停留 ${formatStay(durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp)) {
                    Text(
                        "采样 ${group.size} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "坐标 ${"%.5f".format(first.latitude)}, ${"%.5f".format(first.longitude)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                }
            }
        }
    }
}

/** 将连续相同地址的记录聚合（用 address 去重） */
private fun groupConsecutive(records: List<LocationRecord>): List<List<LocationRecord>> {
    if (records.isEmpty()) return emptyList()
    val out = mutableListOf<MutableList<LocationRecord>>()
    var current = mutableListOf(records.first())
    for (r in records.drop(1)) {
        if (r.address == current.last().address) current.add(r)
        else {
            out.add(current); current = mutableListOf(r)
        }
    }
    out.add(current)
    return out
}

private fun formatStay(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}分钟"
        m == 0L -> "${h}小时"
        else -> "${h}小时${m}分"
    }
}
