package com.blurt.tracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blurt.tracker.service.TrackerService
import com.blurt.tracker.service.WatchdogWorker
import com.blurt.tracker.ui.DashboardScreen
import com.blurt.tracker.util.Config
import com.blurt.tracker.util.Heartbeat
import com.blurt.tracker.util.PermissionHelper
import com.blurt.tracker.util.PingClient
import com.blurt.tracker.util.PingResult
import kotlinx.coroutines.launch

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
    val ctx = LocalContext.current
    var hasUsage by remember { mutableStateOf(PermissionHelper.hasUsageStatsPermission(ctx)) }
    var hasLoc by remember { mutableStateOf(PermissionHelper.hasLocationPermission(ctx)) }
    var hasNotif by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(ctx)) }
    var hasDesktop by remember { mutableStateOf(Config.isConfigured(ctx)) }

    // 同时申请 FINE + COARSE：Android 12+ 系统弹的对话框会让用户选「精确 / 大致」
    val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLoc = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasNotif = granted }

    // 切回应用时刷新一次（用户从设置页返回）
    LaunchedEffect(Unit) {
        hasUsage = PermissionHelper.hasUsageStatsPermission(ctx)
    }

    val allGranted = hasUsage && hasLoc && hasNotif

    LaunchedEffect(allGranted) {
        if (allGranted) {
            Heartbeat.setExpected(ctx, true)
            TrackerService.start(ctx)
            WatchdogWorker.schedule(ctx)
        }
    }

    when {
        !allGranted -> PermissionScreen(
            hasUsage = hasUsage,
            hasLoc = hasLoc,
            hasNotif = hasNotif,
            onRequestUsage = { PermissionHelper.openUsageAccessSettings(ctx) },
            onRefreshUsage = { hasUsage = PermissionHelper.hasUsageStatsPermission(ctx) },
            onRequestLoc = {
                locLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onRequestNotif = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else hasNotif = true
            },
            onRequestBattery = { PermissionHelper.requestIgnoreBatteryOptimizations(ctx) },
        )
        !hasDesktop -> ConnectDesktopScreen(
            onConnected = {
                hasDesktop = true
            },
        )
        else -> DashboardScreen()
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
                label = "2. 位置权限（精确）",
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

@Composable
private fun ConnectDesktopScreen(onConnected: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(Config.getDesktopIp(ctx) ?: "") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestResult?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("4. 连接电脑", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("填入电脑端的 Tailscale IP，确保电脑端服务已启动。",
                style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = ip,
                onValueChange = {
                    ip = it.trim()
                    result = null
                },
                label = { Text("Tailscale IP") },
                placeholder = { Text("例如 100.64.x.x") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = ip.isNotBlank() && !testing,
                    onClick = {
                        testing = true
                        result = null
                        scope.launch {
                            val r = PingClient.ping(ip)
                            result = when (r) {
                                is PingResult.Ok -> TestResult.Ok(r.latencyMs)
                                is PingResult.Error -> TestResult.Fail(r.message)
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "测试中…" else "测试连接") }
            }

            when (val r = result) {
                is TestResult.Ok -> {
                    Text(
                        "✅ 连接成功，电脑端在线（${r.latencyMs}ms）",
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Button(onClick = {
                        Config.setDesktopIp(ctx, ip)
                        onConnected()
                    }) { Text("保存并进入主界面") }
                }
                is TestResult.Fail -> Text(
                    "❌ 连接失败，请检查 IP 或 Tailscale（${r.reason}）",
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                null -> Unit
            }
        }
    }
}

private sealed interface TestResult {
    data class Ok(val latencyMs: Long) : TestResult
    data class Fail(val reason: String) : TestResult
}
