package com.blurt.tracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blurt.tracker.MainActivity
import com.blurt.tracker.data.AppRecord
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.util.Heartbeat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class TrackerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appJob: Job? = null
    private var locJob: Job? = null
    private val pingServer = PingServer(PING_PORT)

    private val db by lazy { TrackerDatabase.get(this) }
    private val dao by lazy { db.trackerDao() }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val usm by lazy {
        getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        Heartbeat.beat(this) // 启动即心跳
        startAppLoop()
        startLocationLoop()
        pingServer.safeStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Heartbeat.beat(this)
        return START_STICKY
    }

    /** 用户从最近任务里把App划掉时触发。系统不会调用 onDestroy。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServiceAliveNotifier.notifyKilled(this, reason = "你从最近任务里把App滑掉了")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pingServer.safeStop()
        // 如果用户期望服务在跑，但服务被销毁了 -> 发通知
        if (Heartbeat.isExpected(this)) {
            ServiceAliveNotifier.notifyKilled(this, reason = "系统回收了后台服务")
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Foreground ----------
    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "烂摊子追踪",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "后台记录App使用和位置" }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("烂摊子正在记录中 🗑️")
            .setContentText("点击查看今日数据")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- App usage loop ----------
    private fun startAppLoop() {
        appJob?.cancel()
        appJob = scope.launch {
            while (true) {
                Heartbeat.beat(this@TrackerService) // 每次循环都心跳
                runCatching { sampleForegroundApp() }
                delay(60_000)
            }
        }
    }

    private suspend fun sampleForegroundApp() {
        val now = System.currentTimeMillis()
        val start = now - 90_000L

        // Walk UsageEvents to find the most recent MOVE_TO_FOREGROUND.
        val events = usm.queryEvents(start, now)
        val ev = UsageEvents.Event()
        var lastPkg: String? = null
        var lastTs: Long = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp >= lastTs) {
                lastPkg = ev.packageName
                lastTs = ev.timeStamp
            }
        }
        val pkg = lastPkg ?: return
        if (pkg == packageName) return // 忽略自己

        val appName = resolveAppName(pkg)
        val latest = dao.getLatestAppRecord()
        // 合并：同App且与上一段结束时间相距不超过90秒
        if (latest != null && latest.packageName == pkg && (now - latest.endTime) <= 90_000L) {
            dao.updateAppRecord(latest.copy(endTime = now))
        } else {
            dao.insertAppRecord(
                AppRecord(
                    appName = appName,
                    packageName = pkg,
                    startTime = now,
                    endTime = now,
                ),
            )
        }
    }

    private fun resolveAppName(pkg: String): String {
        val pm = packageManager
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    // ---------- Location loop ----------
    @SuppressLint("MissingPermission")
    private fun startLocationLoop() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        locJob?.cancel()
        locJob = scope.launch {
            while (true) {
                runCatching { sampleLocation() }
                delay(5 * 60_000)
            }
        }
    }

    /**
     * 智能切换策略：
     *  1. 先用 BALANCED（不开 GPS，~10mA）拿一个粗略位置
     *  2. 跟上一条记录比，距离 > MOVE_THRESHOLD_M 说明用户在动
     *     → 再用 HIGH_ACCURACY（开 GPS）拿一次精准位置覆盖
     *  3. 没动 / 没有历史记录 / GPS 拿不到 → 用 BALANCED 的结果
     */
    @SuppressLint("MissingPermission")
    private suspend fun sampleLocation() {
        // 第一步：粗略
        val coarse = fetchLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY) ?: return

        // 第二步：判断要不要升级到精准
        val previous = dao.getLatestLocationRecord()
        val moved = previous == null ||
            distanceMeters(previous.latitude, previous.longitude,
                coarse.latitude, coarse.longitude) > MOVE_THRESHOLD_M

        val final = if (moved) {
            fetchLocation(Priority.PRIORITY_HIGH_ACCURACY) ?: coarse
        } else {
            coarse
        }

        val address = reverseGeocode(final.latitude, final.longitude)
        dao.insertLocationRecord(
            LocationRecord(
                latitude = final.latitude,
                longitude = final.longitude,
                address = address,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(priority: Int): Location? {
        val cts = CancellationTokenSource()
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                fused.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) cont.resumeWith(Result.success(loc))
                        else cont.resumeWith(Result.success(null))
                    }
                    .addOnFailureListener { _ -> cont.resumeWith(Result.success(null)) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    private fun reverseGeocode(lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull() ?: return "未知位置"
            val name = a.subLocality
                ?: a.locality
                ?: a.thoroughfare
                ?: a.adminArea
                ?: a.featureName
                ?: "未知位置"
            "${name}附近"
        } catch (e: Exception) {
            "位置解析失败"
        }
    }

    companion object {
        private const val CHANNEL_ID = "tracker_fg"
        private const val NOTIF_ID = 1001
        const val PING_PORT = 8000

        /** 两次采样相距超过这个距离（米）就判定用户在移动 → 升级到精准定位 */
        private const val MOVE_THRESHOLD_M = 50f

        fun start(context: Context) {
            val i = Intent(context, TrackerService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }
    }
}
