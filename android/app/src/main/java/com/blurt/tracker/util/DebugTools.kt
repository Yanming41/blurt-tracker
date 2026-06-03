package com.blurt.tracker.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.blurt.tracker.data.TrackerDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时验证工具：dump 系统 UsageEvents 里的屏幕事件，跟我们 Room 里
 * Receiver 抓的 screen_events 做对比。
 *
 * 用法：
 *   1. 手机上点 Locations Tab 里的「🔬 验证屏幕事件」按钮
 *   2. 电脑跑：
 *      adb logcat -d -s BLURT_DEBUG:I
 */
object DebugTools {

    private const val TAG = "BLURT_DEBUG"

    /**
     * UsageEvents 里跟屏幕状态相关的事件类型（API 28+）
     *
     *  15 SCREEN_INTERACTIVE       — 系统亮屏
     *  16 SCREEN_NON_INTERACTIVE   — 系统息屏（这就是我们一直丢的）
     *  17 KEYGUARD_HIDDEN          — 解锁
     *  18 KEYGUARD_SHOWN           — 锁屏
     */
    private val SCREEN_EVENT_TYPES = mapOf(
        15 to "SCREEN_INTERACTIVE",
        16 to "SCREEN_NON_INTERACTIVE",
        17 to "KEYGUARD_HIDDEN",
        18 to "KEYGUARD_SHOWN",
    )

    suspend fun dumpUsageEventsLast24h(ctx: Context) {
        val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        Log.i(TAG, "================ UsageEvents Validation ================")
        Log.i(TAG, "Build.SDK_INT = ${Build.VERSION.SDK_INT} (need >=28 for screen events)")

        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 24 * 3600_000L

        // ---- pass 1: 计数所有事件类型 ----
        val typeCounts = sortedMapOf<Int, Int>()
        val screenEvents = mutableListOf<Triple<Long, Int, String>>() // ts, type, pkg
        run {
            val events = usm.queryEvents(start, end)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                typeCounts[ev.eventType] = (typeCounts[ev.eventType] ?: 0) + 1
                if (ev.eventType in SCREEN_EVENT_TYPES.keys) {
                    screenEvents += Triple(ev.timeStamp, ev.eventType, ev.packageName ?: "")
                }
            }
        }

        Log.i(TAG, "--- Event type histogram (last 24h) ---")
        for ((type, n) in typeCounts) {
            val label = SCREEN_EVENT_TYPES[type] ?: when (type) {
                1 -> "MOVE_TO_FOREGROUND"
                2 -> "MOVE_TO_BACKGROUND"
                23 -> "ACTIVITY_RESUMED"
                24 -> "ACTIVITY_PAUSED"
                else -> "type$type"
            }
            Log.i(TAG, "  type=$type  count=$n  ($label)")
        }

        // ---- pass 2: 屏幕事件时间序列 ----
        Log.i(TAG, "--- Screen events from UsageEvents (sorted) ---")
        screenEvents.sortBy { it.first }
        for ((ts, type, pkg) in screenEvents) {
            val label = SCREEN_EVENT_TYPES[type]
            Log.i(TAG, "  ${tf.format(Date(ts))}  $label  pkg=$pkg")
        }
        Log.i(TAG, "Total screen events from UsageEvents = ${screenEvents.size}")

        // ---- pass 3: 对比 Room 里 ScreenReceiver 写的数据 ----
        val dao = TrackerDatabase.get(ctx).trackerDao()
        val roomEvents = dao.getScreenEventsBetween(start, end)
        Log.i(TAG, "--- Screen events from Room (Receiver-based) ---")
        for (e in roomEvents) {
            Log.i(TAG, "  ${tf.format(Date(e.timestamp))}  ${e.eventType}")
        }
        Log.i(TAG, "Total screen events from Room = ${roomEvents.size}")

        // ---- 总结 ----
        val sysOff = screenEvents.count { it.second == 16 }
        val sysOn = screenEvents.count { it.second == 15 }
        val sysUnlock = screenEvents.count { it.second == 17 }
        val sysLock = screenEvents.count { it.second == 18 }
        val roomOff = roomEvents.count { it.eventType == "息屏" }
        val roomOn = roomEvents.count { it.eventType == "亮屏" }
        val roomUnlock = roomEvents.count { it.eventType == "解锁" }

        Log.i(TAG, "--- Summary ---")
        Log.i(TAG, "UsageEvents:  亮屏=$sysOn  息屏=$sysOff  解锁=$sysUnlock  锁屏=$sysLock")
        Log.i(TAG, "Room/Recv :   亮屏=$roomOn  息屏=$roomOff  解锁=$roomUnlock")
        Log.i(TAG, "Diff(系统-接收器): 亮屏 ${sysOn - roomOn}  息屏 ${sysOff - roomOff}  解锁 ${sysUnlock - roomUnlock}")
        Log.i(TAG, "========================================================")
    }
}
