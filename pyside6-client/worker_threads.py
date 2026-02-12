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
        self.hotkey_str = hotkey_str.lower()
        self.listener = None
        self.pressed_keys = set()
        self.target_combination = self.parse_combination(self.hotkey_str)
        self.running = True

    def parse_combination(self, hs):
        parts = hs.split('+')
        target = set()
        for p in parts:
            p = p.strip()
            if p == 'ctrl': target.add(keyboard.Key.ctrl_l)
            elif p == 'alt': target.add(keyboard.Key.alt_l)
            elif p == 'shift': target.add(keyboard.Key.shift_l)
            elif p == 'win': target.add(keyboard.Key.cmd)
            elif p.startswith('f') and p[1:].isdigit():
                f_key = getattr(keyboard.Key, p, None)
                if f_key: target.add(f_key)
            elif len(p) == 1:
                target.add(keyboard.KeyCode.from_char(p))
        return target

    def run(self):
        logger.info(f"Starting Hotkey listener for: {self.hotkey_str}")
        try:
            with keyboard.Listener(on_press=self.on_press, on_release=self.on_release) as self.listener:
                self.listener.join()
        except Exception as e:
            logger.error(f"Hotkey Listener error: {e}")

    def on_press(self, key):
        # Normalize key for comparison
        norm_key = key
        # Handle simple char keys
        if hasattr(key, 'char') and key.char:
            norm_key = keyboard.KeyCode.from_char(key.char.lower())
        
        self.pressed_keys.add(norm_key)
        
        # Check if target combination is met
        if all(k in self.pressed_keys for k in self.target_combination):
            # Only trigger if the target combination is exactly what's pressed (or at least all target keys are down)
            # To avoid stuck keys, we check if at least one non-modifier key is in the set
            self.triggered.emit()

    def on_release(self, key):
        norm_key = key
        if hasattr(key, 'char') and key.char:
            norm_key = keyboard.KeyCode.from_char(key.char.lower())
        
        if norm_key in self.pressed_keys:
            self.pressed_keys.remove(norm_key)

    def stop(self):
        self.running = False
        if self.listener:
            self.listener.stop()
        self.wait()
