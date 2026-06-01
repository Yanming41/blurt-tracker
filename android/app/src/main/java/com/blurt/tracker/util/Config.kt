package com.blurt.tracker.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置：电脑端 Tailscale IP + 派生出来的 baseUrl。
 * 没有配置 IP 时上传功能应当全部暂停。
 */
object Config {
    private const val PREFS = "blurt_config"
    private const val KEY_DESKTOP_IP = "desktop_ip"
    const val DESKTOP_PORT = 8000

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getDesktopIp(ctx: Context): String? =
        prefs(ctx).getString(KEY_DESKTOP_IP, null)?.takeIf { it.isNotBlank() }

    fun setDesktopIp(ctx: Context, ip: String) {
        prefs(ctx).edit().putString(KEY_DESKTOP_IP, ip.trim()).apply()
    }

    fun clearDesktopIp(ctx: Context) {
        prefs(ctx).edit().remove(KEY_DESKTOP_IP).apply()
    }

    fun baseUrl(ctx: Context): String? = getDesktopIp(ctx)?.let { "http://$it:$DESKTOP_PORT" }

    fun pingUrl(ctx: Context): String? = baseUrl(ctx)?.let { "$it/ping" }

    fun isConfigured(ctx: Context): Boolean = getDesktopIp(ctx) != null

    // ----- 上传水位线：记录最后一次成功上传的屏幕事件时间戳（ms）-----
    private const val KEY_LAST_UPLOADED_SCREEN_TS = "last_uploaded_screen_ts"

    fun getLastUploadedScreenTs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_UPLOADED_SCREEN_TS, 0L)

    fun setLastUploadedScreenTs(ctx: Context, ts: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_UPLOADED_SCREEN_TS, ts).apply()
    }
}
