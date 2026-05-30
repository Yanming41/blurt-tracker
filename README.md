# 烂摊子追踪器 🗑️

记录每天 App 使用情况和位置移动轨迹的 Android MVP 测试版。

## 技术栈
- Kotlin + Jetpack Compose (Material 3)
- Room 本地存储
- FusedLocationProvider + Geocoder
- UsageStatsManager
- 前台服务（START_STICKY）

## 模块
- `data/` — Room 实体与 DAO
- `service/TrackerService` — 60 秒采样前台 App、5 分钟采样位置
- `ui/DashboardScreen` — 按小时分组的今日时间线
- `util/PermissionHelper` — 使用情况访问 / 位置 / 通知 / 电池优化

## 权限引导
首次启动会按顺序申请：使用情况访问 → 位置 → 通知。全部授予后自动启动前台服务，常驻通知显示「烂摊子正在记录中 🗑️」。

## 构建
最低 Android 8.0 (SDK 26)，目标 SDK 34。需安装 Android Studio Iguana+ 与 Gradle 8.7。
