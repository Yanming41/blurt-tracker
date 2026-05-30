# Blurt API 规范

桌面端 FastAPI 服务监听 `0.0.0.0:8000`，手机端通过 Tailscale 私网地址访问。
所有时间字段使用 **ISO 8601** 格式（如 `2026-05-30T14:23:00`，无时区视为本地时间）。
所有请求/响应均为 `application/json`，编码 UTF-8。

---

## 1. POST `/mobile/app-record`
手机端上报一段 App 使用记录。

请求体：
```json
{
  "app_name": "微信",
  "package_name": "com.tencent.mm",
  "start_time": "2026-05-30T09:12:00",
  "end_time":   "2026-05-30T09:18:42"
}
```

响应 `201`：
```json
{ "id": 123 }
```

---

## 2. POST `/mobile/location`
手机端上报一条位置点。

请求体：
```json
{
  "latitude": 31.2304,
  "longitude": 121.4737,
  "address": "上海市黄浦区南京东路",
  "timestamp": "2026-05-30T10:05:00"
}
```
`address` 可选。响应 `201`：
```json
{ "id": 456 }
```

---

## 3. GET `/timeline/today`
返回今日完整时间线（电脑 + 手机 + 位置），按时间升序排列。

响应 `200`：
```json
{
  "date": "2026-05-30",
  "entries": [
    {
      "source": "windows",
      "start_time": "2026-05-30T09:00:00",
      "end_time":   "2026-05-30T09:25:00",
      "app_name": "Code.exe",
      "title_or_address": "main.py - blurt-tracker"
    },
    {
      "source": "mobile",
      "start_time": "2026-05-30T09:12:00",
      "end_time":   "2026-05-30T09:18:42",
      "app_name": "微信",
      "package_name": "com.tencent.mm"
    },
    {
      "source": "location",
      "start_time": "2026-05-30T10:05:00",
      "title_or_address": "上海市黄浦区南京东路",
      "latitude": 31.2304,
      "longitude": 121.4737
    }
  ]
}
```

`source` 取值：`"windows"` / `"mobile"` / `"location"`。
`location` 类记录无 `end_time`。

---

## 4. GET `/timeline/{date}`
指定日期的时间线，`date` 为 `YYYY-MM-DD`。
响应结构同 `/timeline/today`。

错误 `400`：日期格式非法。

---

## 5. GET `/summary/today`
返回今日总结。

响应 `200`：
```json
{
  "date": "2026-05-30",
  "summary_text": "今天主要在……",
  "total_screen_time": 21540,
  "created_at": "2026-05-30T23:30:12"
}
```
`total_screen_time` 单位为秒。

错误 `404`：今日尚未生成总结。

---

## 6. POST `/summary/generate`
触发生成总结。默认生成今天的，可指定 `date`。

请求体（均为可选）：
```json
{ "date": "2026-05-30" }
```

响应 `200`：结构同 `/summary/today`。
内部调用本地 Ollama (`http://localhost:11434`)，模型默认 `qwen2.5:3b`。

---

## 7. GET `/health`
存活探针。响应 `{"status":"ok"}`。

---

## 错误格式
所有 4xx/5xx 错误遵循 FastAPI 默认格式：
```json
{ "detail": "Invalid date: 2026-13-40" }
```

## 客户端约定
- 手机端在网络可用时批量上报，失败时本地缓存重试。
- 时间字段全部使用本地时间（手机和电脑均在同一时区运行）。
- 所有写接口幂等性由客户端去重保证（短期内重复上报不报错）。
