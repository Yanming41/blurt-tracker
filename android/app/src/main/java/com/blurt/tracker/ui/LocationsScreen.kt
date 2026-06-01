package com.blurt.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.padding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationsScreen(vm: TimelineViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val locs = s.items.filterIsInstance<TLItem.Loc>().map { it.record }

    if (locs.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) { Text("今天还没有位置记录", Modifier.padding(24.dp)) }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(locs, key = { it.id }) { r ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("📍 ${r.address}", style = MaterialTheme.typography.titleSmall)
                    Text(timeFmt.format(Date(r.timestamp)), style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${"%.5f".format(r.latitude)}, ${"%.5f".format(r.longitude)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
