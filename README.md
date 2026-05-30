# 烂摊子（Blurt）🗑️

个人日常追踪 App — 把每天的手机使用、电脑窗口活动和位置轨迹聚合到一处，
晚上由本地 LLM 生成一段总结。

---

## 仓库结构

```
blurt-tracker/
├── android/         安卓端 (Kotlin + Jetpack Compose)
├── desktop/         电脑端 (Python + FastAPI)
│   ├── main.py            FastAPI 入口 + 监控 + 调度
│   ├── monitor.py         前台窗口采样
│   ├── scheduler.py       每日 23:30 调用 Ollama 生成总结
│   ├── database.py        SQLAlchemy 模型 + SQLite
│   ├── requirements.txt
│   └── setup_autostart.bat  Windows 任务计划开机自启
├── shared/
│   └── api_schema.md      手机↔电脑 通信规范
└── README.md
```

手机和电脑通过 **Tailscale** 私网互联，
所有数据通过 HTTP API 上报到电脑端，存储在本地 SQLite。

---

## 启动电脑端

```powershell
cd desktop
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py
```

服务将监听 `0.0.0.0:8000`，同时启动：
- FastAPI 接口
- 前台窗口监控（每 60 秒采样一次）
- APScheduler（每天 23:30 自动生成总结）

### 开机自启动
以管理员身份运行 `desktop\setup_autostart.bat`，
会注册一个名为 `BlurtDesktop` 的 Windows 计划任务，在登录时启动。

### 本地 LLM
总结调用本地 [Ollama](https://ollama.com/) 上的 `qwen2.5:3b`：
```powershell
ollama pull qwen2.5:3b
ollama serve   # 默认监听 http://localhost:11434
```

---

## 安装安卓端

```bash
cd android
./gradlew assembleDebug
# 把 app/build/outputs/apk/debug/app-debug.apk 安装到手机
```

或在 Android Studio (Iguana+, Gradle 8.7) 中打开 `android/` 目录直接运行。

首次启动按顺序授权：使用情况访问 → 位置 → 通知 → 电池优化。

### 配置后端地址
在安卓端设置里填入电脑的 Tailscale 地址，例如：
```
http://100.x.y.z:8000
```

---

## Tailscale 配置

1. 在电脑和手机上都安装 [Tailscale](https://tailscale.com/) 并登录同一账号。
2. 在电脑上确认 Tailscale IP：
   ```powershell
   tailscale ip -4
   ```
3. 确保 Windows 防火墙允许入站 TCP 8000：
   ```powershell
   New-NetFirewallRule -DisplayName "Blurt Desktop" `
       -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow
   ```
4. 手机端使用上面的 Tailscale IP 作为后端地址。
5. 双方都不在公网暴露端口，仅通过 Tailscale 私网通信。

---

## 分支策略
- `main` — 稳定版本
- `dev`  — 日常开发分支

## API 规范
详见 [`shared/api_schema.md`](shared/api_schema.md)。
