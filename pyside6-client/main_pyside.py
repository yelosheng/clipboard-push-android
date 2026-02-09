import sys
import os
import json
import time
import base64
import pyperclip
import requests
import io
import ctypes
import win32clipboard
import win32con
from pathlib import Path
from PIL import ImageGrab, Image
from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QFileDialog
from PySide6.QtGui import QIcon, QAction
from PySide6.QtCore import Qt, Slot, QTimer

from ui_manager import SettingsWindow
from worker_threads import NetworkWorker, HotkeyWorker
from crypto_utils import CryptoUtils
from loguru import logger

# Constants
CONFIG_FILE = "config.json"

class DROPFILES(ctypes.Structure):
    _fields_ = [
        ("pFiles", ctypes.c_uint),
        ("pt", ctypes.c_long * 2),
        ("fNC", ctypes.c_int),
        ("fWide", ctypes.c_bool),
    ]

class ClipboardApp:
    def __init__(self):
        self.app = QApplication(sys.argv)
        self.app.setQuitOnLastWindowClosed(False)
        
        self.load_config()
        self.crypto = None
        if self.config.get("room_key"):
            self.crypto = CryptoUtils(self.config["room_key"])

        self.icon = QIcon(str(Path(__file__).parent / "icon.png"))
        self.app.setWindowIcon(self.icon)

        self.ui = SettingsWindow()
        self.ui.setWindowIcon(self.icon)
        self.setup_tray()
        self.setup_connections()
        
        self.net_worker = NetworkWorker(self.config)
        self.setup_network_worker()
        self.net_worker.start()

        self.hotkey_worker = None
        self.restart_hotkey_worker()

    def load_config(self):
        self.config_path = Path(__file__).parent / CONFIG_FILE
        defaults = self.get_default_config()
        if self.config_path.exists():
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                    self.config = {**defaults, **loaded}
            except Exception as e:
                logger.error(f"Failed to load config: {e}")
                self.config = defaults
        else:
            logger.info("Config file not found, initializing new pairing session.")
            self.config = defaults
            # Auto-generate room and key for fresh start
            self.config["room_id"] = f"room_{int(time.time())}"
            self.config["room_key"] = CryptoUtils().get_key_base64()
            self.save_config()

    def get_default_config(self):
        # Default path: User/Downloads/ClipboardMan
        download_path = str(Path.home() / "Downloads" / "ClipboardMan")
        
        return {
            "relay_server_url": "http://kxkl.tk:5055",
            "download_path": download_path,
            "device_id": f"pc_{os.getlogin()}_{int(time.time()) % 10000}",
            "room_id": None,
            "room_key": None,
            "push_hotkey": "Ctrl+F6",
            "auto_copy_image": True,
            "auto_copy_file": True,
            "auto_start": False
        }

    def save_config(self):
        try:
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=4)
        except Exception as e:
            logger.error(f"Failed to save config: {e}")

    def setup_tray(self):
        self.tray = QSystemTrayIcon(self.ui)
        self.tray.setIcon(self.icon)
        
        menu = QMenu()
        push_action = QAction("Push Clipboard", self.app)
        push_action.triggered.connect(self.on_hotkey_triggered)
        
        show_action = QAction("Settings", self.app)
        show_action.triggered.connect(self.show_settings)
        
        quit_action = QAction("Quit", self.app)
        quit_action.triggered.connect(self.quit_app)
        
        menu.addAction(push_action)
        menu.addSeparator()
        menu.addAction(show_action)
        menu.addAction(quit_action)
        
        self.tray.setContextMenu(menu)
        self.tray.show()
        self.tray.activated.connect(self.on_tray_activated)

    def on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.ActivationReason.Trigger:
            self.show_settings()

    def setup_connections(self):
        self.ui.save_clicked.connect(self.on_settings_saved)
        self.ui.reconnect_clicked.connect(self.on_reconnect_clicked)
        self.ui.browse_clicked.connect(self.on_browse_clicked)
        self.ui.push_clicked.connect(self.on_hotkey_triggered)

    def setup_network_worker(self):
        self.net_worker.connected.connect(lambda: self.ui.set_status("Connected", "green"))
        self.net_worker.disconnected.connect(lambda: self.ui.set_status("Disconnected", "red"))
        self.net_worker.clipboard_received.connect(self.on_clipboard_received)
        self.net_worker.file_received.connect(self.on_file_received)

    def restart_hotkey_worker(self):
        if self.hotkey_worker:
            self.hotkey_worker.stop()
        
        hs = self.config.get("push_hotkey", "Ctrl+F6")
        if hs:
            self.hotkey_worker = HotkeyWorker(hs)
            self.hotkey_worker.triggered.connect(self.on_hotkey_triggered)
            self.hotkey_worker.start()
            logger.info(f"Hotkey listener started: {hs}")

    def show_settings(self):
        self.ui.server_url_input.setText(self.config.get("relay_server_url", ""))
        self.ui.download_path_input.setText(self.config.get("download_path", ""))
        self.ui.hotkey_input.setText(self.config.get("push_hotkey", "Ctrl+F6"))
        self.ui.cb_images.setChecked(self.config.get("auto_copy_image", True))
        self.ui.cb_files.setChecked(self.config.get("auto_copy_file", True))
        self.ui.cb_startup.setChecked(self.config.get("auto_start", False))
        
        # QR Code - Try to show even if just generated
        rid = self.config.get("room_id")
        rk = self.config.get("room_key")
        if rid and rk:
            qr_data = json.dumps({
                "server": self.config["relay_server_url"],
                "room": rid,
                "key": rk
            })
            self.ui.qr_label.set_qr_content(qr_data)
        
        # Ensure status reflects current state
        if self.net_worker.sio.connected:
            self.ui.set_status("Connected", "green")
        else:
            self.ui.set_status("Disconnected", "red")

        self.ui.show()
        self.ui.raise_()
        self.ui.activateWindow()

    @Slot(dict)
    def on_settings_saved(self, data):
        # Update config and ensure no duplicate 'server_url' (use only 'relay_server_url')
        self.config.update(data)
        self.config.pop("server_url", None) 
        self.save_config()
        
        if self.config.get("room_key"):
            self.crypto = CryptoUtils(self.config["room_key"])
        
        # Trigger reconnection instead of thread restart
        self.on_reconnect_clicked()
        
        self.restart_hotkey_worker()
        self.show_settings() # Refresh QR if needed
        logger.success("Settings updated")

    def on_reconnect_clicked(self):
        logger.info("Manual reconnection triggered...")
        self.net_worker.config = self.config
        self.net_worker.force_reconnect()

    def on_browse_clicked(self):
        path = QFileDialog.getExistingDirectory(self.ui, "Select Download Directory", self.config.get("download_path", ""))
        if path:
            self.ui.download_path_input.setText(path)

    @Slot(str, bool)
    def on_clipboard_received(self, content, is_encrypted):
        try:
            final_text = content
            if is_encrypted:
                if self.crypto:
                    enc_bytes = base64.b64decode(content)
                    dec_bytes = self.crypto.decrypt(enc_bytes)
                    final_text = dec_bytes.decode('utf-8')
                else:
                    return
            
            pyperclip.copy(final_text)
            self.tray.showMessage("Clipboard Man", f"Received Text: {final_text[:50]}...", QSystemTrayIcon.MessageIcon.Information)
        except Exception as e:
            logger.error(f"Clipboard sync error: {e}")

    @Slot(dict)
    def on_file_received(self, data):
        url = data.get('download_url')
        filename = data.get('filename')
        file_type = data.get('type', 'file')
        
        if not url or not filename or not self.crypto:
            return

        logger.info(f"Downloading {filename}...")
        try:
            resp = requests.get(url, stream=True, timeout=60, verify=False)
            resp.raise_for_status()
            enc_data = resp.content
            dec_data = self.crypto.decrypt(enc_data)
            
            save_dir = Path(self.config["download_path"])
            save_dir.mkdir(parents=True, exist_ok=True)
            local_path = save_dir / filename
            
            # De-conflict
            count = 1
            while local_path.exists():
                local_path = save_dir / f"{local_path.stem}_{count}{local_path.suffix}"
                count += 1
            
            with open(local_path, 'wb') as f:
                f.write(dec_data)
            
            path_str = str(local_path.absolute())
            if file_type == 'image' and self.config.get("auto_copy_image", True):
                self.set_clipboard_image(path_str)
            elif self.config.get("auto_copy_file", True):
                self.set_clipboard_files([path_str])
            
            self.tray.showMessage("Clipboard Man", f"Received File: {filename}", QSystemTrayIcon.MessageIcon.Information)
            logger.success(f"File saved: {path_str}")
        except Exception as e:
            logger.error(f"File sync error: {e}")

    def on_hotkey_triggered(self):
        # Captures and pushes
        if not self.crypto or not self.config.get("room_id"):
            logger.warning("Not paired, cannot push")
            return
            
        # 1. Files
        files = self.get_clipboard_files()
        if files:
            for f in files:
                if os.path.isfile(f):
                    self.perform_push_file(f)
            return

        # 2. Image
        img = ImageGrab.grabclipboard()
        if isinstance(img, Image.Image):
            with io.BytesIO() as out:
                img.save(out, format='PNG')
                self.perform_push_file_data(out.getvalue(), f"img_{int(time.time())}.png", "image")
            return

        # 3. Text
        text = pyperclip.paste()
        if text and text.strip():
            self.perform_push_text(text)

    def perform_push_text(self, text):
        try:
            enc_bytes = self.crypto.encrypt(text.encode('utf-8'))
            enc_b64 = base64.b64encode(enc_bytes).decode('utf-8')
            
            payload = {
                "room": self.config["room_id"],
                "event": "clipboard_sync",
                "data": {
                    "content": enc_b64,
                    "encrypted": True,
                    "timestamp": time.strftime("%H:%M:%S"),
                    "source": f"PC_{os.getlogin()}"
                },
                "client_id": self.config.get("device_id")
            }
            url = f"{self.config['relay_server_url']}/api/relay"
            requests.post(url, json=payload, timeout=5, verify=False)
            logger.info("Pushed text sync")
        except Exception as e:
            logger.error(f"Text push error: {e}")

    def perform_push_file(self, path):
        with open(path, 'rb') as f:
            data = f.read()
        self.perform_push_file_data(data, os.path.basename(path), "file")

    def perform_push_file_data(self, data, filename, ftype):
        try:
            enc_data = self.crypto.encrypt(data)
            # Get Auth
            url_auth = f"{self.config['relay_server_url']}/api/file/upload_auth"
            resp = requests.post(url_auth, json={
                "filename": filename,
                "size": len(enc_data),
                "content_type": "application/octet-stream"
            }, timeout=10, verify=False)
            
            auth = resp.json()
            requests.put(auth['upload_url'], data=enc_data, headers={"Content-Type": "application/octet-stream"}, timeout=300, verify=False)
            
            # Relay notification
            payload = {
                "room": self.config["room_id"],
                "event": "file_sync",
                "data": {
                    "download_url": auth['download_url'],
                    "filename": filename,
                    "type": ftype,
                    "timestamp": time.strftime("%H:%M:%S")
                },
                "client_id": self.config.get("device_id")
            }
            requests.post(f"{self.config['relay_server_url']}/api/relay", json=payload, timeout=5, verify=False)
            logger.info(f"Pushed {ftype}: {filename}")
        except Exception as e:
            logger.error(f"File push error: {e}")

    # Clipboard Helpers
    def set_clipboard_files(self, paths):
        offset = ctypes.sizeof(DROPFILES)
        length = sum(len(p) + 1 for p in paths) + 1
        size = offset + length * ctypes.sizeof(ctypes.c_wchar)
        buf = (ctypes.c_char * size)()
        df = DROPFILES.from_buffer(buf)
        df.pFiles = offset
        df.fWide = True
        raw_paths = ("\0".join(paths) + "\0\0").encode("utf-16le")
        ctypes.memmove(ctypes.byref(buf, offset), raw_paths, len(raw_paths))
        try:
            win32clipboard.OpenClipboard()
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32con.CF_HDROP, buf)
        finally:
            win32clipboard.CloseClipboard()

    def set_clipboard_image(self, path):
        img = Image.open(path)
        output = io.BytesIO()
        img.save(output, 'BMP')
        data = output.getvalue()[14:]
        try:
            win32clipboard.OpenClipboard()
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32con.CF_DIB, data)
        finally:
            win32clipboard.CloseClipboard()

    def get_clipboard_files(self):
        files = []
        try:
            win32clipboard.OpenClipboard()
            if win32clipboard.IsClipboardFormatAvailable(win32con.CF_HDROP):
                data = win32clipboard.GetClipboardData(win32con.CF_HDROP)
                files = [data[i] for i in range(len(data))]
        finally:
            try: win32clipboard.CloseClipboard()
            except: pass
        return files

    def quit_app(self):
        self.net_worker.stop()
        if self.hotkey_worker:
            self.hotkey_worker.stop()
        self.app.quit()

    def run(self):
        sys.exit(self.app.exec())

if __name__ == "__main__":
    app = ClipboardApp()
    app.run()
