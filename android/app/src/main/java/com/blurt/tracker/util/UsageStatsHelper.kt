package com.blurt.tracker.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.blurt.tracker.data.ScreenEvent
import java.util.Calendar

/** 今日某 App 的使用汇总 */
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeMs: Long,
    val lastUsed: Long,
    val firstUsed: Long,
)

/** 单次 App 启动/退出事件 */
data class AppEvent(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    /** "打开" 或 "关闭" */
    val eventType: String,
    val timestamp: Long,
)

/** 配对后的连续使用时间段 */
data class AppSegment(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
)

object UsageStatsHelper {

    /** 系统 App / 输入法 / 桌面等，过滤掉 */
    private val SYSTEM_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.android.settings",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.phone",
        "com.android.dialer",
        "com.android.contacts",
        "com.android.providers.calendar",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "android",
    )

    /** 至少使用 1 分钟才算 */
    private const val MIN_USAGE_MS = 60_000L

    // ---------- 权限 ----------

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ---------- 读取 ----------

    /** 给定时间区间内每个 App 的使用汇总。 */
    fun getAppUsageBetween(context: Context, start: Long, end: Long): List<AppUsageInfo> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return getAppUsageInner(context, usm, start, end)
    }

    /** 今日 0 点到现在 - 旧入口，保留向后兼容 */
    fun getTodayAppUsage(context: Context): List<AppUsageInfo> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfToday()
        val now = System.currentTimeMillis()
        return getAppUsageInner(context, usm, start, now)
    }

    private fun getAppUsageInner(
        context: Context,
        usm: UsageStatsManager,
        start: Long,
        end: Long,
    ): List<AppUsageInfo> {
        val now = end

        // queryUsageStats 在某些 ROM 上不返回 firstTimeUsed，所以我们用 events 自己算
        val byPkg = mutableMapOf<String, MutableList<AppEvent>>()
        val rawEvents = queryEvents(usm, start, now)
        for (e in rawEvents) {
            byPkg.getOrPut(e.packageName) { mutableListOf() }.add(e)
        }

        return byPkg.mapNotNull { (pkg, events) ->
            if (pkg in SYSTEM_PACKAGES || pkg == context.packageName) return@mapNotNull null
            val segments = pairEvents(events)
            val total = segments.sumOf { it.durationMs }
            if (total < MIN_USAGE_MS) return@mapNotNull null

            AppUsageInfo(
                packageName = pkg,
                appName = resolveAppName(context, pkg),
                totalTimeMs = total,
                lastUsed = segments.maxOfOrNull { it.endTime } ?: 0L,
                firstUsed = segments.minOfOrNull { it.startTime } ?: 0L,
            )
        }.sortedByDescending { it.totalTimeMs }
    }

    /** 给定时间区间内所有 RESUMED/PAUSED 事件，过滤系统包 */
    fun getEventsBetween(context: Context, start: Long, end: Long): List<AppEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return queryEvents(usm, start, end)
            .filter { it.packageName !in SYSTEM_PACKAGES && it.packageName != context.packageName }
    }

    /** 今日 0 点到现在 - 旧入口，保留向后兼容 */
    fun getTodayEvents(context: Context): List<AppEvent> =
        getEventsBetween(context, startOfToday(), System.currentTimeMillis())

    /**
     * 把事件配对成时间段。
     * - 每个"打开"找到下一个同包名的"关闭"
     * - 找不到关闭就用下一次同包名的"打开"
     * - 还找不到就用 now 截断
     */
    fun eventsToSegments(events: List<AppEvent>): List<AppSegment> {
        val sorted = events.sortedBy { it.timestamp }
        return pairEvents(sorted)
    }

    /** 单 App 包名 -> 图标，失败返回 null */
    fun getAppIcon(context: Context, packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * 从系统 UsageEvents 读今天的屏幕状态事件（API 28+）。
     * 不受 App 进程是否活着影响 —— 系统全程在记录。
     *
     *   15 SCREEN_INTERACTIVE       -> "亮屏"
     *   16 SCREEN_NON_INTERACTIVE   -> "息屏"
     *   17 KEYGUARD_HIDDEN          -> "解锁"
     *   18 KEYGUARD_SHOWN           -> "锁屏"
     *
     * 老版本 (< API 28) 返回空列表，让原 ScreenReceiver 数据顶上。
     */
    fun getTodayScreenEvents(context: Context): List<ScreenEvent> =
        getScreenEventsBetween(context, startOfToday(), System.currentTimeMillis())

    /** 给定时间区间从系统 UsageEvents 拉屏幕事件（API 28+） */
    fun getScreenEventsBetween(context: Context, start: Long, end: Long): List<ScreenEvent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val typeMap = mapOf(
            15 to "亮屏",
            16 to "息屏",
            17 to "解锁",
            18 to "锁屏",
        )
        val out = mutableListOf<ScreenEvent>()
        val events = usm.queryEvents(start, end)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val type = typeMap[ev.eventType] ?: continue
            out += ScreenEvent(eventType = type, timestamp = ev.timeStamp)
        }
        return out
    }

    // ---------- 内部 ----------

    private fun queryEvents(
        usm: UsageStatsManager,
        start: Long,
        end: Long,
    ): List<AppEvent> {
        val out = mutableListOf<AppEvent>()
        val raw = usm.queryEvents(start, end)
        val ev = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(ev)
            val type = when (ev.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> "打开"
                UsageEvents.Event.MOVE_TO_BACKGROUND -> "关闭"
                else -> null
            } ?: continue
            out += AppEvent(
                packageName = ev.packageName ?: continue,
                appName = "",            // 真正显示前用 resolveAppName 解析；这里先空着
                appIcon = null,
                eventType = type,
                timestamp = ev.timeStamp,
            )
        }
        return out
    }

    private fun pairEvents(events: List<AppEvent>): List<AppSegment> {
        // 同一包名内的 open -> close 配对
        val byPkg = events.groupBy { it.packageName }
        val segments = mutableListOf<AppSegment>()
        val now = System.currentTimeMillis()

        byPkg.forEach { (pkg, list) ->
            var openTs: Long? = null
            for (e in list) {
                when (e.eventType) {
                    "打开" -> {
                        // 如果已经有未关闭的 open，先以这次 open 截止上一段
                        openTs?.let { prev ->
                            segments += makeSegment(pkg, prev, e.timestamp)
                        }
                        openTs = e.timestamp
                    }
                    "关闭" -> {
                        openTs?.let { prev ->
                            segments += makeSegment(pkg, prev, e.timestamp)
                            openTs = null
                        }
                    }
                }
            }
            // 收尾：还在前台
            openTs?.let { prev -> segments += makeSegment(pkg, prev, now) }
        }
        return segments.sortedBy { it.startTime }
    }

    private fun makeSegment(pkg: String, start: Long, end: Long): AppSegment {
        val safeEnd = if (end >= start) end else start
        return AppSegment(
            packageName = pkg,
            appName = "",
            appIcon = null,
            startTime = start,
            endTime = safeEnd,
            durationMs = safeEnd - start,
        )
    }

    fun resolveAppName(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun startOfToday(): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }
}
