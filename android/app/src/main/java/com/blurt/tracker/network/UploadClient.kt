package com.blurt.tracker.network

import com.blurt.tracker.data.ActivityBlock
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.util.AppUsageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 简单 HTTP 上传客户端（HttpURLConnection，不依赖第三方）。
 * 单条 POST /mobile/screen-event：
 *   { "event_type": "亮屏/息屏/解锁", "timestamp": 1234567890 }   // 秒级
 */
object UploadClient {

    /**
     * 批量上传屏幕事件。返回成功上传到的最后一个时间戳（毫秒），
     * 失败时返回 null —— 调用方可以保留 lastUploadedTs 不前进，下次重试。
     */
    suspend fun uploadScreenEvents(
        baseUrl: String,
        events: List<ScreenEvent>,
    ): Long? = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext null
        var lastOk: Long? = null
        for (e in events) {
            val ok = runCatching {
                postJson(
                    "$baseUrl/mobile/screen-event",
                    """{"event_type":"${e.eventType}","timestamp":${e.timestamp / 1000}}""",
                )
            }.getOrDefault(false)
            if (!ok) return@withContext lastOk
            lastOk = e.timestamp
        }
        lastOk
    }

    /** 每日打包上传：今日 App 使用汇总（位置和屏幕事件按需另行扩展） */
    suspend fun uploadDailyDigest(
        baseUrl: String,
        dayStart: Long,
        appUsages: List<AppUsageInfo>,
    ): Boolean = withContext(Dispatchers.IO) {
        val appsJson = appUsages.joinToString(",", "[", "]") { u ->
            """{"package_name":"${u.packageName.escape()}","app_name":"${u.appName.escape()}",""" +
                """"total_time_ms":${u.totalTimeMs},""" +
                """"first_used":${u.firstUsed / 1000},"last_used":${u.lastUsed / 1000}}"""
        }
        val body = """{"day_start":${dayStart / 1000},"apps":$appsJson}"""
        postJson("$baseUrl/mobile/daily-upload", body)
    }

    // ============ ActivityBlock 上传 / 拉取 ============

    data class FetchedLabel(
        val startTime: Long,
        val endTime: Long,
        val dominantAppPackage: String,
        val category: String,
        val activityLabel: String?,
        val confidence: Float?,
        val reasoning: String?,
        val askUser: String?,
        val manuallyCorrected: Boolean,
    )

    private val isoFmt: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    /** 上传一批 ActivityBlock 给电脑端，按 (start_time, dominant_app_package) upsert。 */
    suspend fun uploadBlocks(
        baseUrl: String,
        dateStr: String,
        blocks: List<ActivityBlock>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext true
        val fmt = isoFmt
        val arr = blocks.joinToString(",", "[", "]") { b ->
            """{"phone_block_id":${b.id},""" +
                """"start_time":"${fmt.format(Date(b.startTime))}",""" +
                """"end_time":"${fmt.format(Date(b.endTime))}",""" +
                """"category":"${b.category.escape()}",""" +
                """"dominant_app_package":"${b.dominantAppPackage.escape()}",""" +
                """"dominant_app_name":"${b.dominantAppName.escape()}",""" +
                """"total_app_time_ms":${b.totalAppTimeMs},""" +
                """"duration_ms":${b.durationMs},""" +
                """"interruption_count":${b.interruptionCount},""" +
                """"location_address":${jsonNullableStr(b.locationAddress)}}"""
        }
        val body = """{"date":"$dateStr","blocks":$arr}"""
        postJson("$baseUrl/mobile/blocks", body)
    }

    /** 拉某天的块（含 LLM 标签）。失败返回 null。 */
    suspend fun fetchLabels(baseUrl: String, dateStr: String): List<FetchedLabel>? =
        withContext(Dispatchers.IO) {
            val raw = getText("$baseUrl/mobile/blocks/$dateStr") ?: return@withContext null
            try {
                val obj = JSONObject(raw)
                val arr = obj.getJSONArray("blocks")
                val fmt = isoFmt
                val out = mutableListOf<FetchedLabel>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += FetchedLabel(
                        startTime = fmt.parse(o.getString("start_time"))!!.time,
                        endTime = fmt.parse(o.getString("end_time"))!!.time,
                        dominantAppPackage = "",  // 服务端不在 OUT 里返回 pkg；按 startTime 匹配足够
                        category = o.optString("category", "other"),
                        activityLabel = o.optString("activity_label").takeIf { it.isNotBlank() && it != "null" },
                        confidence = if (o.isNull("confidence")) null else o.getDouble("confidence").toFloat(),
                        reasoning = o.optString("reasoning").takeIf { it.isNotBlank() && it != "null" },
                        askUser = o.optString("ask_user").takeIf { it.isNotBlank() && it != "null" },
                        manuallyCorrected = o.optBoolean("manually_corrected", false),
                    )
                }
                out
            } catch (e: Exception) {
                null
            }
        }

    /** 触发电脑端跑 LLM 标签 */
    suspend fun triggerLabeler(baseUrl: String, dateStr: String? = null, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val body = buildString {
                append("{")
                if (dateStr != null) append("\"date\":\"$dateStr\",")
                append("\"force\":$force")
                append("}")
            }
            // LLM 跑批可能要几十秒
            postJson("$baseUrl/labeler/run", body, timeoutMs = 120_000)
        }

    private fun jsonNullableStr(s: String?): String =
        if (s == null) "null" else "\"${s.escape()}\""

    private fun String.escape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")

    private fun getText(url: String, timeoutMs: Int = 5000): String? {
        val u = URL(url)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(url: String, body: String, timeoutMs: Int = 5000): Boolean {
        val u = URL(url)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
