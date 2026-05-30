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
}
