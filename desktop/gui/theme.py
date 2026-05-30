"""集中放主题色和 QSS 样式表。"""

# Color palette (VSCode 暗色风格变体)
BG = "#1a1a2e"
SIDEBAR_BG = "#16213e"
CARD_BG = "#222539"
BORDER = "#2a2d4a"
TEXT = "#e6e6e6"
TEXT_DIM = "#a0a0b0"
ACCENT = "#e94560"
ACCENT_BLUE = "#0f3460"
SUCCESS = "#4caf50"
WARNING = "#ff9800"
DANGER = "#e94560"

RADIUS = "10px"


GLOBAL_QSS = f"""
QWidget {{
    background-color: {BG};
    color: {TEXT};
    font-family: "Microsoft YaHei UI", "PingFang SC", "Segoe UI", sans-serif;
    font-size: 13px;
}}

#Sidebar {{
    background-color: {SIDEBAR_BG};
    border-right: 1px solid {BORDER};
}}

#SidebarTitle {{
    color: {TEXT};
    font-size: 18px;
    font-weight: bold;
}}
#SidebarSubtitle {{
    color: {TEXT_DIM};
    font-size: 11px;
}}

QFrame#Card, QFrame#SidebarCard {{
    background-color: {CARD_BG};
    border: 1px solid {BORDER};
    border-radius: {RADIUS};
}}

QFrame#SidebarCard {{
    background-color: #1d2747;
}}

QLabel.section-title {{
    color: {TEXT_DIM};
    font-size: 11px;
    font-weight: bold;
}}

QPushButton {{
    background-color: {ACCENT_BLUE};
    color: {TEXT};
    border: none;
    border-radius: {RADIUS};
    padding: 8px 14px;
}}
QPushButton:hover {{ background-color: #144a80; }}
QPushButton:pressed {{ background-color: #0c2848; }}

QPushButton[accent="true"] {{
    background-color: {ACCENT};
}}
QPushButton[accent="true"]:hover {{ background-color: #ff5b78; }}

QPushButton#NavButton {{
    background-color: transparent;
    color: {TEXT_DIM};
    text-align: left;
    padding: 10px 14px;
    border-radius: {RADIUS};
    font-size: 14px;
}}
QPushButton#NavButton:hover {{
    background-color: #1d2747;
    color: {TEXT};
}}
QPushButton#NavButton:checked {{
    background-color: {ACCENT_BLUE};
    color: {TEXT};
}}

QProgressBar {{
    background-color: #0d1024;
    border: 1px solid {BORDER};
    border-radius: 6px;
    text-align: center;
    color: {TEXT};
    height: 14px;
}}
QProgressBar::chunk {{
    background-color: #4a90e2;
    border-radius: 5px;
}}
QProgressBar[warn="true"]::chunk {{
    background-color: {DANGER};
}}

QLineEdit, QDateEdit, QComboBox, QSpinBox, QTextEdit, QPlainTextEdit {{
    background-color: #0d1024;
    border: 1px solid {BORDER};
    border-radius: 6px;
    padding: 6px 8px;
    color: {TEXT};
}}

QListWidget, QListView {{
    background-color: {CARD_BG};
    border: 1px solid {BORDER};
    border-radius: {RADIUS};
    padding: 4px;
}}
QListWidget::item {{
    padding: 8px;
    border-bottom: 1px solid {BORDER};
}}
QListWidget::item:selected {{
    background-color: {ACCENT_BLUE};
}}

QScrollBar:vertical {{
    background: transparent;
    width: 10px;
}}
QScrollBar::handle:vertical {{
    background: #2a2d4a;
    border-radius: 5px;
    min-height: 20px;
}}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
"""
