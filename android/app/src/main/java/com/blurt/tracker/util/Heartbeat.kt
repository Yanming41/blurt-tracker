package com.blurt.tracker.util

import android.content.Context

/**
 * 服务心跳：TrackerService 每次采样都写一个时间戳，
 * WorkManager Watchdog 定期读，超时 -> 判定服务死了。
 *
 * `expected` 由 MainActivity 在所有权限就绪后置 true，
 * 这样 Watchdog 在用户尚未授权时不会误报。
 */
object Heartbeat {
    private const val PREFS = "blurt_heartbeat"
    private const val KEY_LAST = "last_heartbeat"
    private const val KEY_EXPECTED = "service_expected"

    /** 超过这个时间没收到心跳 = 服务挂了 */
    const val STALE_MS = 5 * 60_000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beat(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    fun lastBeat(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST, 0L)

    fun ageMs(ctx: Context): Long {
        val last = lastBeat(ctx)
        return if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last
    }

    fun isStale(ctx: Context): Boolean = ageMs(ctx) > STALE_MS

    fun setExpected(ctx: Context, expected: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EXPECTED, expected).apply()
    }

    fun isExpected(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_EXPECTED, false)
}
