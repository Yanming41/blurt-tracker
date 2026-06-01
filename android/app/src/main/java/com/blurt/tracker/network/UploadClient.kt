package com.blurt.tracker.network

import com.blurt.tracker.data.ScreenEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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

    private fun postJson(url: String, body: String): Boolean {
        val u = URL(url)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
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
