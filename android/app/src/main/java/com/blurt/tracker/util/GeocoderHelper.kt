package com.blurt.tracker.util

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * 反向地理编码：lat/lon -> 人话地址。
 * 用系统 Geocoder（GMS 设备走谷歌后端，国内 ROM 走厂商后端）。
 */
object GeocoderHelper {

    fun reverseGeocode(ctx: Context, lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull() ?: return "未知位置"

            val line0 = a.getAddressLine(0)?.trim()
            if (!line0.isNullOrEmpty()) {
                return line0
                    .removePrefix("中国")
                    .removePrefix("中華人民共和國")
                    .trim()
                    .ifEmpty { "未知位置" }
            }
            val parts = listOfNotNull(
                a.thoroughfare, a.subLocality, a.locality,
            ).distinct()
            if (parts.isNotEmpty()) return parts.joinToString("·")

            a.featureName ?: a.adminArea ?: "未知位置"
        } catch (e: Exception) {
            "位置解析失败"
        }
    }
}
