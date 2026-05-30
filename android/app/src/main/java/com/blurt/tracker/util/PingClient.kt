package com.blurt.tracker.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class PingResult {
    data class Ok(val latencyMs: Long) : PingResult()
    data class Error(val message: String) : PingResult()
}

/**
 * 轻量 ping：GET http://ip:port/ping，测量往返耗时。
 * 用 HttpURLConnection 避免引入 OkHttp/Retrofit 依赖。
 */
object PingClient {
    suspend fun ping(
        ip: String,
        port: Int = Config.DESKTOP_PORT,
        timeoutMs: Int = 3000,
    ): PingResult = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext PingResult.Error("IP 为空")
        val url = try {
            URL("http://$ip:$port/ping")
        } catch (e: Exception) {
            return@withContext PingResult.Error("IP 格式错误")
        }
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - started
            if (code in 200..299) PingResult.Ok(elapsed)
            else PingResult.Error("HTTP $code")
        } catch (e: Exception) {
            PingResult.Error(e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }
}
