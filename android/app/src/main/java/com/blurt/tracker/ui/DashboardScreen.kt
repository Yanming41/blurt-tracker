package com.blurt.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blurt.tracker.data.MoodEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页 = 情绪/烂摊子记录入口 + 今日已写条目列表
 */
@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val moods by vm.todayMoods.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("🗑️ 把烂摊子扔进来", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "随手记一下此刻的情绪、念头、未完成的破事。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("现在感觉…") },
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = input.isNotBlank(),
            onClick = {
                vm.addMood(input.trim())
                input = ""
            },
        ) { Text("扔进去") }

        Spacer(Modifier.height(20.dp))
        Text("今日 ${moods.size} 条", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(moods, key = { it.id }) { entry -> MoodRow(entry) }
        }
    }
}

@Composable
private fun MoodRow(entry: MoodEntry) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(timeFmt.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Spacer(Modifier.height(2.dp))
            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
