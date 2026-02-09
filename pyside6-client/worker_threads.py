import time
import json
import base64
import socketio
import requests
from PySide6.QtCore import QThread, Signal
from pynput import keyboard
from loguru import logger
from crypto_utils import CryptoUtils

class NetworkWorker(QThread):
    connected = Signal()
    disconnected = Signal()
    clipboard_received = Signal(str, bool) # content, is_encrypted
    file_received = Signal(dict) # data
    error_occurred = Signal(str)

    def __init__(self, config):
        super().__init__()
        self.config = config
        self.sio = socketio.Client(ssl_verify=False)
        self.setup_handlers()
        self.running = True

    def setup_handlers(self):
        @self.sio.event
        def connect():
            logger.info("Connected to relay server")
            if self.config.get("room_id"):
                payload = {
                    'room': self.config['room_id'],
                    'client_id': self.config.get('device_id')
                }
                self.sio.emit('join', payload)
            self.connected.emit()

        @self.sio.event
        def disconnect():
            logger.info("Disconnected from server")
            self.disconnected.emit()

        @self.sio.event
        def clipboard_sync(data):
            content = data.get('content')
            is_encrypted = data.get('encrypted', False)
            if content:
                self.clipboard_received.emit(content, is_encrypted)

        @self.sio.event
        def file_sync(data):
            self.file_received.emit(data)

    def run(self):
        while self.running:
            if not self.sio.connected:
                try:
                    # Clean connect attempt
                    self.sio.connect(self.config['relay_server_url'], wait_timeout=5)
                except Exception as e:
                    logger.debug(f"Auto-connect attempt failed: {e}")
                    time.sleep(5)
            self.msleep(2000)

    def force_reconnect(self):
        logger.info("Forcing Socket.IO reconnection...")
        if self.sio.connected:
            try:
                self.sio.disconnect()
            except: pass
        # The run loop will pick it up and reconnect

    def stop(self):
        self.running = False
        if self.sio.connected:
            try:
                self.sio.disconnect()
            except: pass
        self.wait()

class HotkeyWorker(QThread):
    triggered = Signal()

    def __init__(self, hotkey_str):
        super().__init__()
        self.hotkey_str = hotkey_str # e.g. "<alt>+v"
        self.listener = None

    def run(self):
        # Translate from user format "Alt+V" to pynput format "<alt>+v"
        pynput_key = self.translate_hotkey(self.hotkey_str)
        if not pynput_key:
            return

        with keyboard.GlobalHotKeys({pynput_key: self.on_activate}) as self.listener:
            self.listener.join()

    def on_activate(self):
        logger.info("Hotkey triggered!")
        self.triggered.emit()

    def translate_hotkey(self, hs):
        if not hs: return None
        parts = hs.lower().split('+')
        translated = []
        for p in parts:
            p = p.strip()
            # Modifiers
            if p == 'alt': translated.append('<alt>')
            elif p in ['ctrl', 'control']: translated.append('<ctrl>')
            elif p == 'shift': translated.append('<shift>')
            elif p in ['win', 'cmd', 'command']: translated.append('<cmd>')
            # Function keys: handle f1-f12
            elif p.startswith('f') and p[1:].isdigit():
                translated.append(f'<{p}>')
            else:
                translated.append(p)
        return '+'.join(translated)

    def stop(self):
        if self.listener:
            self.listener.stop()
        self.wait()
