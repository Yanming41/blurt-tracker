"""Blurt 桌面端入口 — 启动 GUI + 后台 API + 监控 + 调度。"""
from __future__ import annotations

import logging
import sys

from PySide6.QtWidgets import QApplication

from core.api import ApiServerThread, get_lan_ip, get_tailscale_ip, monitor
from core.config import get_config
from core.database import init_db
from core.scheduler import start_scheduler
from gui.main_window import MainWindow
from gui.theme import GLOBAL_QSS

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

    bar = "━" * 28
    cfg = get_config()
    port = cfg.get("api_port", 8000)
    print("\n🗑️  烂摊子后端已启动")
    print(bar)
    print(f"📡 {label}：{ip}")
    print(f"🌐 服务地址：http://{ip}:{port}")
    print("📱 手机填写此IP即可连接")
    print(bar + "\n")


def main() -> int:
    cfg = get_config()

    # 数据库 + 监控 + 调度 + API 服务器
    init_db()
    monitor.start()
    scheduler = start_scheduler()
    api_server = ApiServerThread(
        host=cfg.get("api_host", "0.0.0.0"),
        port=int(cfg.get("api_port", 8000)),
    )
    api_server.start()
    print_startup_banner()

    # Qt
    app = QApplication(sys.argv)
    app.setApplicationName("烂摊子控制台")
    app.setQuitOnLastWindowClosed(False)  # 关闭主窗口时保留托盘
    app.setStyleSheet(GLOBAL_QSS)

    window = MainWindow()
    window.show()

    exit_code = app.exec()

    # 优雅退出
    logger.info("Shutting down…")
    monitor.stop()
    try:
        scheduler.shutdown(wait=False)
    except Exception:
        logger.exception("scheduler shutdown failed")
    api_server.stop()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
