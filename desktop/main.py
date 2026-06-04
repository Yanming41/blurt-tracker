"""Blurt 桌面端入口 — Fluent GUI + 后台 API + 监控 + 调度。"""
from __future__ import annotations

import logging
import signal
import sys

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import QApplication
from qfluentwidgets import setTheme, Theme

from core.api import ApiServerThread, get_lan_ip, get_tailscale_ip, monitor
from core.config import get_config
from core.database import init_db
from core.scheduler import start_scheduler
from gui.main_window import MainWindow

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("blurt")


def print_startup_banner() -> None:
    ts_ip = get_tailscale_ip()
    if ts_ip:
        label = "本机Tailscale IP"
        ip = ts_ip
    else:
        label = "本机局域网IP（未检测到Tailscale）"
        ip = get_lan_ip()

    cfg = get_config()
    port = cfg.get("api_port", 8000)
    bar = "━" * 28
    print("\n🗑️  烂摊子后端已启动")
    print(bar)
    print(f"📡 {label}：{ip}")
    print(f"🌐 服务地址：http://{ip}:{port}")
    print("📱 手机填写此IP即可连接")
    print(bar + "\n")


def main() -> int:
    cfg = get_config()

    init_db()
    monitor.start()
    scheduler = start_scheduler()
    api_server = ApiServerThread(
        host=cfg.get("api_host", "0.0.0.0"),
        port=int(cfg.get("api_port", 8000)),
    )
    api_server.start()
    print_startup_banner()

    # Qt — 必须在创建 QApplication 之前设 HiDPI
    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.Round
    )
    app = QApplication(sys.argv)
    app.setApplicationName("烂摊子控制台")
    app.setQuitOnLastWindowClosed(False)
    setTheme(Theme.AUTO)  # 跟随系统亮/暗

    window = MainWindow()
    window.show()

    # —— Ctrl+C 处理 ——
    # Qt 的 app.exec() 会阻塞解释器，Python 收不到 SIGINT。
    # 装一个 100ms 的空转 QTimer 让解释器定期苏醒处理信号；
    # 然后用自定义 handler 强制退出 Qt 主循环。
    def _sigint(*_):
        logger.info("收到 Ctrl+C，正在退出…")
        window._force_quit = True
        try:
            window.tray.hide()
        except Exception:
            pass
        QApplication.instance().quit()

    signal.signal(signal.SIGINT, _sigint)
    signal.signal(signal.SIGTERM, _sigint)
    _wake = QTimer()
    _wake.start(100)
    _wake.timeout.connect(lambda: None)

    exit_code = app.exec()

    logger.info("Shutting down…")
    monitor.stop()
    try:
        scheduler.shutdown(wait=False)
    except Exception:
        logger.exception("scheduler shutdown failed")
    try:
        api_server.stop()
    except Exception:
        logger.exception("api_server stop failed")

    # 兜底 — 1.5 秒后如果还活着就强退，避免一直挂
    import os, threading
    def _hard_exit():
        os._exit(exit_code)
    t = threading.Timer(1.5, _hard_exit)
    t.daemon = True
    t.start()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
