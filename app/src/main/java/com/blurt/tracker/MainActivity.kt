package com.blurt.tracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blurt.tracker.service.TrackerService
import com.blurt.tracker.ui.DashboardScreen
import com.blurt.tracker.util.PermissionHelper

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hasUsage by remember { mutableStateOf(PermissionHelper.hasUsageStatsPermission(ctx)) }
    var hasLoc by remember { mutableStateOf(PermissionHelper.hasLocationPermission(ctx)) }
    var hasNotif by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(ctx)) }

    val activity = ctx as ComponentActivity

    val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLoc = granted }

    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasNotif = granted }

    // 切回应用时刷新一次（用户从设置页返回）
    LaunchedEffect(Unit) {
        hasUsage = PermissionHelper.hasUsageStatsPermission(ctx)
    }

    val allGranted = hasUsage && hasLoc && hasNotif

    LaunchedEffect(allGranted) {
        if (allGranted) TrackerService.start(ctx)
    }

    if (!allGranted) {
        PermissionScreen(
            hasUsage = hasUsage,
            hasLoc = hasLoc,
            hasNotif = hasNotif,
            onRequestUsage = {
                PermissionHelper.openUsageAccessSettings(ctx)
            },
            onRefreshUsage = {
                hasUsage = PermissionHelper.hasUsageStatsPermission(ctx)
            },
            onRequestLoc = {
                locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            onRequestNotif = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    hasNotif = true
                }
            },
            onRequestBattery = {
                PermissionHelper.requestIgnoreBatteryOptimizations(ctx)
            },
        )
    } else {
        DashboardScreen()
    }
}

@Composable
private fun PermissionScreen(
    hasUsage: Boolean,
    hasLoc: Boolean,
    hasNotif: Boolean,
    onRequestUsage: () -> Unit,
    onRefreshUsage: () -> Unit,
    onRequestLoc: () -> Unit,
    onRequestNotif: () -> Unit,
    onRequestBattery: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("欢迎使用 烂摊子追踪器", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("我们需要以下权限才能工作：", style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))

            PermRow(
                label = "1. 使用情况访问",
                granted = hasUsage,
                actionText = "前往系统设置授予",
                onClick = onRequestUsage,
            )
            if (!hasUsage) {
                Button(onClick = onRefreshUsage) { Text("我已授予，刷新状态") }
            }

            PermRow(
                label = "2. 位置权限（粗略）",
                granted = hasLoc,
                actionText = "申请权限",
                onClick = onRequestLoc,
            )

            PermRow(
                label = "3. 通知权限",
                granted = hasNotif,
                actionText = "申请权限",
                onClick = onRequestNotif,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "国产系统（小米/华为/OPPO等）请到电池设置中关闭对本App的优化，否则后台可能被杀。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRequestBattery) { Text("关闭电池优化") }
        }
    }
}

@Composable
private fun PermRow(
    label: String,
    granted: Boolean,
    actionText: String,
    onClick: () -> Unit,
) {
    Column {
        Text(text = if (granted) "$label ✅" else "$label ❌",
            style = MaterialTheme.typography.titleMedium)
        if (!granted) {
            Button(onClick = onClick) { Text(actionText) }
        }
    }
}
