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
                    self.sio.connect(self.config['relay_server_url'])
                except Exception as e:
                    logger.error(f"Connect failed: {e}")
                    time.sleep(5)
            self.msleep(1000)

    def stop(self):
        self.running = False
        if self.sio.connected:
            self.sio.disconnect()
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
            if p == 'alt': translated.append('<alt>')
            elif p == 'ctrl': translated.append('<ctrl>')
            elif p == 'shift': translated.append('<shift>')
            elif p == 'win': translated.append('<cmd>')
            else: translated.append(p)
        return '+'.join(translated)

    def stop(self):
        if self.listener:
            self.listener.stop()
        self.wait()
