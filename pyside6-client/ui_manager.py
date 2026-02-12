import sys
import qrcode
from io import BytesIO
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, 
                             QHBoxLayout, QLabel, QLineEdit, QCheckBox, 
                             QPushButton, QSystemTrayIcon, QMenu, QFrame, QTextEdit)
from PySide6.QtGui import QIcon, QPixmap, QImage, QFont, QKeyEvent
from PySide6.QtCore import Qt, Signal

class QRCodeLabel(QLabel):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setFixedSize(200, 200)
        self.setStyleSheet("border: 1px solid #ccc; background: white;")

    def set_qr_content(self, content):
        qr = qrcode.QRCode(version=1, box_size=10, border=1)
        qr.add_data(content)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        
        buffer = BytesIO()
        img.save(buffer, format="PNG")
        image = QImage.fromData(buffer.getvalue())
        pixmap = QPixmap.fromImage(image)
        self.setPixmap(pixmap.scaled(self.size(), Qt.AspectRatioMode.KeepAspectRatio))

class HotkeyRecorderEdit(QLineEdit):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setPlaceholderText("Press keys to set hotkey...")
        self.setReadOnly(True)
        self.setStyleSheet("background-color: #f8f9fa; border: 1px solid #ced4da; padding: 5px;")
        self.current_keys = set()

    def focusInEvent(self, event):
        self.clear()
        self.setStyleSheet("background-color: #fff3cd; border: 1px solid #ffeeba; padding: 5px;")
        super().focusInEvent(event)

    def focusOutEvent(self, event):
        self.setStyleSheet("background-color: #f8f9fa; border: 1px solid #ced4da; padding: 5px;")
        if not self.text():
            self.setText("None")
        super().focusOutEvent(event)

    def keyPressEvent(self, event: QKeyEvent):
        key = event.key()
        if key == Qt.Key_Backspace or key == Qt.Key_Delete:
            self.clear()
            return

        if key in [Qt.Key_Control, Qt.Key_Alt, Qt.Key_Shift, Qt.Key_Meta]:
            # Just updating modifiers view
            modifiers = event.modifiers()
            parts = []
            if modifiers & Qt.ControlModifier: parts.append("Ctrl")
            if modifiers & Qt.AltModifier: parts.append("Alt")
            if modifiers & Qt.ShiftModifier: parts.append("Shift")
            if modifiers & Qt.MetaModifier: parts.append("Win")
            if parts:
                self.setText("+".join(parts) + "+...")
            return

        if key == Qt.Key_Escape:
            self.clear()
            self.clearFocus()
            return

        modifiers = event.modifiers()
        parts = []
        if modifiers & Qt.ControlModifier: parts.append("Ctrl")
        if modifiers & Qt.AltModifier: parts.append("Alt")
        if modifiers & Qt.ShiftModifier: parts.append("Shift")
        if modifiers & Qt.MetaModifier: parts.append("Win")

        # Handle the main key
        main_key = ""
        if Qt.Key_F1 <= key <= Qt.Key_F12:
            main_key = f"F{key - Qt.Key_F1 + 1}"
        elif Qt.Key_A <= key <= Qt.Key_Z:
            main_key = chr(key)
        elif key == Qt.Key_Space:
            main_key = "Space"
        
        if main_key:
            if main_key not in parts:
                parts.append(main_key)
            self.setText("+".join(parts))
            self.clearFocus() # Auto-finish recording
        else:
            # If modifier + unknown key, don't update to invalid state
            pass

class MainWindow(QMainWindow):
    push_text_clicked = Signal(str)
    settings_clicked = Signal()

    def __init__(self):
        super().__init__()
        self.setWindowTitle("Clipboard Push")
        self.setFixedSize(400, 300)
        self.init_ui()

    def init_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        layout = QVBoxLayout(central_widget)
        layout.setContentsMargins(15, 15, 15, 15)
        layout.setSpacing(10)

        # Text Area
        self.text_edit = QTextEdit()
        self.text_edit.setPlaceholderText("Enter text to push... (Ctrl+Enter to send)")
        self.text_edit.installEventFilter(self)
        layout.addWidget(self.text_edit)

        # Buttons
        btn_layout = QHBoxLayout()
        
        self.push_btn = QPushButton("Push")
        self.push_btn.setFixedHeight(40)
        self.push_btn.clicked.connect(self.on_push)
        
        self.settings_btn = QPushButton("⚙")
        self.settings_btn.setFixedSize(40, 40)
        self.settings_btn.setToolTip("Settings")
        self.settings_btn.setFont(QFont("Segoe UI", 12)) 
        self.settings_btn.clicked.connect(self.settings_clicked.emit)
        
        btn_layout.addWidget(self.push_btn, 1) # Push button takes more space
        btn_layout.addWidget(self.settings_btn)
        
        layout.addLayout(btn_layout)
        
        # Status Label
        self.status_label = QLabel("Ready")
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.status_label.setStyleSheet("color: #666; font-size: 11px;")
        layout.addWidget(self.status_label)

        self.setFont(QFont("Segoe UI", 10))

    def eventFilter(self, source, event):
        if source == self.text_edit and event.type() == QKeyEvent.Type.KeyPress:
            if event.key() == Qt.Key_Return and (event.modifiers() & Qt.ControlModifier):
                self.on_push()
                return True
        return super().eventFilter(source, event)

    def on_push(self):
        text = self.text_edit.toPlainText()
        if text.strip():
            self.push_text_clicked.emit(text)
            self.text_edit.clear()
        else:
            self.set_status("Please enter text", "red")

    def set_status(self, text, color="#666"):
        self.status_label.setText(text)
        self.status_label.setStyleSheet(f"color: {color}; font-size: 11px;")

class SettingsWindow(QMainWindow):
    save_clicked = Signal(dict)
    reconnect_clicked = Signal()
    browse_clicked = Signal()
    push_clicked = Signal()
    
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Clipboard Push - Settings")
        # self.setFixedSize(650, 450)
        self.resize(650, 450) # Allow resizing if needed, but keep default
        self.init_ui()

    def init_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QHBoxLayout(central_widget)
        main_layout.setContentsMargins(20, 20, 20, 20)
        main_layout.setSpacing(30)

        # Left side: Settings
        left_layout = QVBoxLayout()
        left_layout.setSpacing(15)

        # Server URL (Hidden, configurable via config.json)
        self.server_url_label = QLabel("Server URL:")
        self.server_url_input = QLineEdit()
        self.server_url_input.setPlaceholderText("http://your-server:5055")
        # left_layout.addWidget(self.server_url_label)
        # left_layout.addWidget(self.server_url_input)
        self.server_url_label.hide()
        self.server_url_input.hide()

        # Download Path
        left_layout.addWidget(QLabel("Download Path:"))
        path_layout = QHBoxLayout()
        self.download_path_input = QLineEdit()
        path_layout.addWidget(self.download_path_input)
        
        self.browse_btn = QPushButton("...")
        self.browse_btn.setFixedWidth(40)
        self.browse_btn.clicked.connect(self.browse_clicked.emit)
        path_layout.addWidget(self.browse_btn)
        
        left_layout.addLayout(path_layout)

        # Push Hotkey
        left_layout.addWidget(QLabel("Push Hotkey (Click and press keys):"))
        self.hotkey_input = HotkeyRecorderEdit()
        left_layout.addWidget(self.hotkey_input)

        # Checkboxes
        self.cb_images = QCheckBox("Auto Copy Received Images")
        self.cb_files = QCheckBox("Auto Copy Received Files")
        self.cb_startup = QCheckBox("Start on Boot")
        left_layout.addWidget(self.cb_images)
        left_layout.addWidget(self.cb_files)
        left_layout.addWidget(self.cb_startup)

        left_layout.addStretch()

        # Status
        self.status_label = QLabel("Status: Disconnected")
        self.status_label.setStyleSheet("color: #666;")
        left_layout.addWidget(self.status_label)

        left_layout.addStretch()

        # Right side: QR Code
        right_layout = QVBoxLayout()
        right_layout.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignHCenter)
        
        right_layout.addWidget(QLabel("Scan to Pair Mobile"))
        self.qr_label = QRCodeLabel()
        right_layout.addWidget(self.qr_label)
        
        right_layout.addSpacing(20)

        # Buttons on the right
        btn_layout = QVBoxLayout()
        btn_layout.setSpacing(10)

        self.save_btn = QPushButton("Save Settings")
        self.save_btn.setFixedHeight(35)
        self.save_btn.clicked.connect(self.on_save)
        
        self.push_btn = QPushButton("Push Manual")
        self.push_btn.setFixedHeight(35)
        self.push_btn.clicked.connect(self.push_clicked.emit)

        self.reconnect_btn = QPushButton("Reconnect")
        self.reconnect_btn.setFixedHeight(35)
        self.reconnect_btn.clicked.connect(self.reconnect_clicked.emit)
        
        self.cancel_btn = QPushButton("Close")
        self.cancel_btn.setFixedHeight(35)
        self.cancel_btn.clicked.connect(self.hide)
        
        btn_layout.addWidget(self.save_btn)
        btn_layout.addWidget(self.push_btn)
        btn_layout.addWidget(self.reconnect_btn)
        btn_layout.addWidget(self.cancel_btn)
        
        right_layout.addLayout(btn_layout)
        right_layout.addStretch()

        main_layout.addLayout(left_layout, 2)
        
        line = QFrame()
        line.setFrameShape(QFrame.Shape.VLine)
        line.setFrameShadow(QFrame.Shadow.Sunken)
        main_layout.addWidget(line)
        
        main_layout.addLayout(right_layout, 1)

        # Set default font
        self.setFont(QFont("Segoe UI", 10))

    def on_save(self):
        data = {
            "server_url": self.server_url_input.text(),
            "download_path": self.download_path_input.text(),
            "push_hotkey": self.hotkey_input.text(),
            "auto_copy_image": self.cb_images.isChecked(),
            "auto_copy_file": self.cb_files.isChecked(),
            "auto_start": self.cb_startup.isChecked()
        }
        self.save_clicked.emit(data)

    def set_status(self, text, color="#666"):
        self.status_label.setText(f"Status: {text}")
        self.status_label.setStyleSheet(f"color: {color};")
