package com.blurt.tracker.util

import android.content.Context
import android.location.Geocoder
import com.blurt.tracker.BuildConfig
import com.blurt.tracker.network.PlacesClient
import java.util.Locale

/**
 * 反向地理编码：lat/lon -> 人话地址。
 *
 * 策略：
 *  1. 如果配了 PLACES_API_KEY，先调 Google Places API (New) 找最近 POI
 *     - 有 POI 名 + 地址：「星巴克朝阳门店 · 朝阳门外大街 8 号」
 *     - 只有地址：返回 shortFormattedAddress
 *  2. Places 不可用 / 没结果 → 回落系统 Geocoder（GMS 设备走谷歌后端）
 *  3. 都失败 → "未知位置"
 */
object GeocoderHelper {

    suspend fun reverseGeocode(ctx: Context, lat: Double, lon: Double): String {
        // ---- 1. Google Places (New) ----
        val key = BuildConfig.PLACES_API_KEY
        if (key.isNotBlank()) {
            val p = PlacesClient.nearestPlace(lat, lon, key)
            if (p != null) {
                val name = p.displayName
                val addr = (p.shortAddress ?: p.formattedAddress)?.let(::cleanCountry)
                val composed = when {
                    !name.isNullOrBlank() && !addr.isNullOrBlank() -> "$name · $addr"
                    !name.isNullOrBlank() -> name
                    !addr.isNullOrBlank() -> addr
                    else -> null
                }
                if (!composed.isNullOrBlank()) return composed
            }
        }

        // ---- 2. 系统 Geocoder 回落 ----
        return systemGeocode(ctx, lat, lon)
    }

    private fun systemGeocode(ctx: Context, lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull() ?: return "未知位置"

            val line0 = a.getAddressLine(0)?.trim()
            if (!line0.isNullOrEmpty()) {
                return cleanCountry(line0).ifEmpty { "未知位置" }
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

    private fun cleanCountry(s: String): String =
        s.removePrefix("中国").removePrefix("中華人民共和國").trim()
}
