package com.blurt.tracker.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 调用 Google Places API (New) 做"基于坐标找最近 POI"。
 *
 * 文档：https://developers.google.com/maps/documentation/places/web-service/nearby-search
 * 端点：POST https://places.googleapis.com/v1/places:searchNearby
 *
 * 字段掩码（X-Goog-FieldMask）只取我们要的字段，省钱省流量。
 */
object PlacesClient {
    private const val URL_STR = "https://places.googleapis.com/v1/places:searchNearby"
    private const val FIELD_MASK = "places.displayName,places.formattedAddress,places.shortFormattedAddress"

    data class PlaceInfo(
        val displayName: String?,
        val formattedAddress: String?,
        val shortAddress: String?,
    )

    /**
     * 在 50m 范围内取最近的 1 个 POI。
     * 返回 null 表示请求失败或没有结果（调用方应回落 Geocoder）。
     */
    suspend fun nearestPlace(
        lat: Double,
        lon: Double,
        apiKey: String,
        radiusMeters: Double = 50.0,
    ): PlaceInfo? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val body = """
            {
              "locationRestriction": {
                "circle": {
                  "center": {"latitude": $lat, "longitude": $lon},
                  "radius": $radiusMeters
                }
              },
              "maxResultCount": 1,
              "rankPreference": "DISTANCE"
            }
        """.trimIndent()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return@withContext null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(text)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(json: String): PlaceInfo? {
        val obj = JSONObject(json)
        val places = obj.optJSONArray("places") ?: return null
        if (places.length() == 0) return null
        val first = places.getJSONObject(0)
        val name = first.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotBlank() }
        val addr = first.optString("formattedAddress").takeIf { it.isNotBlank() }
        val short = first.optString("shortFormattedAddress").takeIf { it.isNotBlank() }
        if (name == null && addr == null && short == null) return null
        return PlaceInfo(name, addr, short)
    }
}
