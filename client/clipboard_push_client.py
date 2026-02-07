"""
Clipboard Push - PC Client
=========================
PC 端剪贴板同步客户端 (Windows)

功能：
    - 连接 Clipboard Man 服务器 (Socket.IO)
    - 实时接收 Android 端推送的剪贴板内容
    - 自动写入 Windows 系统剪贴板
    - 支持文本、图片 (直接粘贴到微信/PS)、文件 (直接粘贴到资源管理器)
    - 美化的 Rich 界面

依赖：
    pip install "python-socketio[client]" requests pyperclip rich pillow pywin32
"""

import sys
import uuid
import time
import json
import socketio
import requests
import pyperclip
import io
import os
import ctypes
import struct
import threading
import qrcode
import win32clipboard
import win32con
from pathlib import Path
from loguru import logger
from rich.console import Console
from rich.panel import Panel
from crypto_utils import CryptoUtils

# --- 配置 ---
def load_config():
    config_path = Path(__file__).parent / "config.json"
    # 默认配置：不再需要 server_url，而是 relay_server_url
    default_config = {
        "relay_server_url": "http://kxkl.tk:5055",   # 默认公网地址
        "download_path": str(Path.home() / "Downloads" / "ClipboardPush"),
        "device_id": f"pc_{os.getlogin()}_{uuid.uuid4().hex[:8]}", # Append UUID for uniqueness
        "room_id": None,      # 配对成功后保存
        "room_key": None      # 配对成功后保存 (Base64)
    }
    
    if config_path.exists():
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                user_config = json.load(f)
                if "download_path" in user_config:
                    user_config["download_path"] = str(Path(user_config["download_path"]).expanduser())
                default_config.update(user_config)
        except Exception as e:
            logger.error(f"加载配置文件失败: {e}")

    return default_config

def save_config(config):
    config_path = Path(__file__).parent / "config.json"
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=4)
    except Exception as e:
        logger.error(f"保存配置文件失败: {e}")

CONFIG = load_config()
SERVER_URL = CONFIG["relay_server_url"]
DOWNLOAD_PATH = Path(CONFIG["download_path"])
CRYPTO = None  # 将在配对后初始化

# --- 初始化 Rich Console ---
console = Console()

# --- Loguru ---
logger.remove()
logger.add(sys.stderr, level="INFO", format="<green>{time:HH:mm:ss}</green> | <level>{level: <8}</level> | <level>{message}</level>")

# Win32 & PIL imports (kept same as before, simplified here for brevity, assume they exist)

# ==================== Encryption & Upload Logic ====================

def upload_encrypted_file(file_path):
    """
    1. Encrypt file locally
    2. Get upload URL from Server
    3. Upload to R2
    4. Return public metadata (download_url)
    """
    if not CRYPTO:
        logger.error("未配对，无法加密文件")
        return None

    try:
        # 1. Encrypt
        encrypted_data = CRYPTO.encrypt_file(file_path)
        file_size = len(encrypted_data)
        filename = Path(file_path).name

        # 2. Get Auth
        resp = requests.post(f"{SERVER_URL}/api/file/upload_auth", json={
            "filename": filename,
            "size": file_size,
            "content_type": "application/octet-stream" # R2 stores as binary blob
        })
        auth_data = resp.json()
        upload_url = auth_data['upload_url']
        download_url = auth_data['download_url']

        # 3. Upload to R2
        logger.info(f"正在上传加密文件到 R2 ({file_size/1024:.1f} KB)...")
        put_resp = requests.put(upload_url, data=encrypted_data, headers={
            "Content-Type": "application/octet-stream"
        })
        put_resp.raise_for_status()

        logger.info("上传成功！")
        return download_url

    except Exception as e:
        logger.error(f"上传失败: {e}")
        return None

from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# ... (Previous imports)

import urllib3
# Disable SSL warnings
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Force-clear proxy environment variables
for k in ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"]:
    os.environ.pop(k, None)

def create_retry_session(retries=5, backoff_factor=1.0):
    session = requests.Session()
    session.trust_env = False # Disable proxy (ignore HTTP_PROXY/HTTPS_PROXY)
    session.verify = False    # Disable SSL Cert verification
    retry = Retry(
        total=retries,
        read=retries,
        connect=retries,
        backoff_factor=backoff_factor,
        status_forcelist=[500, 502, 503, 504],
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount('http://', adapter)
    session.mount('https://', adapter)
    return session

def process_download(url, filename):
    """
    1. Download from R2 (Streamed)
    2. Decrypt locally
    3. Save to disk
    """
    try:
        if not CRYPTO:
            logger.error("未配对，无法解密文件")
            return None

        logger.info(f"正在下载加密文件: {url}...")
        
        # Use retry session
        session = create_retry_session()
        # Verify=False is dangerous but helps with some aggressive proxies. 
        # Keep verify=True for now.
        resp = session.get(url, timeout=60, stream=True)
        resp.raise_for_status()
        
        # Read content (in memory for now as decryption needs full block)
        # For huge files, we'd need chunked decryption, but for now <100MB is fine.
        encrypted_data = resp.content

        # Decrypt
        decrypted_data = CRYPTO.decrypt(encrypted_data)
        
        # Save
        download_dir = DOWNLOAD_PATH
        download_dir.mkdir(parents=True, exist_ok=True)
        local_path = download_dir / filename
        
        # Avoid overwrite
        counter = 1
        stem = local_path.stem
        suffix = local_path.suffix
        while local_path.exists():
            local_path = download_dir / f"{stem}_{counter}{suffix}"
            counter += 1
            
        with open(local_path, 'wb') as f:
            f.write(decrypted_data)
            
        logger.info(f"文件解密并保存成功: {local_path}")
        return str(local_path)

    except Exception as e:
        logger.error(f"下载/解密失败: {e}")
        return None

# ==================== Windows Clipboard Helpers ====================

class DROPFILES(ctypes.Structure):
    _fields_ = [
        ("pFiles", ctypes.c_uint),
        ("pt", ctypes.c_long * 2),
        ("fNC", ctypes.c_int),
        ("fWide", ctypes.c_bool),
    ]

def set_clipboard_files(paths):
    """ Writes a list of files to the Windows clipboard (CF_HDROP) """
    if not paths: return
    
    # Create Buffer
    offset = ctypes.sizeof(DROPFILES)
    length = sum(len(p) + 1 for p in paths) + 1
    size = offset + length * ctypes.sizeof(ctypes.c_wchar)
    buf = (ctypes.c_char * size)()
    
    # Configure DropFiles
    df = DROPFILES.from_buffer(buf)
    df.pFiles = offset
    df.pt[0] = 0
    df.pt[1] = 0
    df.fNC = 0
    df.fWide = True # Unicode
    
    # Write Paths
    raw_paths = ("\0".join(paths) + "\0\0").encode("utf-16le")
    ctypes.memmove(ctypes.byref(buf, offset), raw_paths, len(raw_paths))
    
    # Set Clipboard
    try:
        win32clipboard.OpenClipboard()
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32con.CF_HDROP, buf)
        logger.info(f"已将 {len(paths)} 个文件写入剪贴板")
    except Exception as e:
        logger.error(f"写入剪贴板失败: {e}")
    finally:
        try: win32clipboard.CloseClipboard()
        except: pass

def set_clipboard_image(image_path):
    """ Writes an image file to the Windows clipboard (CF_DIB) """
    try:
        from PIL import Image
        output = io.BytesIO()
        img = Image.open(image_path)
        img.save(output, 'BMP')
        data = output.getvalue()[14:] # Strip BMP Header (14 bytes) for CF_DIB
        output.close()
        
        win32clipboard.OpenClipboard()
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32con.CF_DIB, data)
        logger.info("已将图片写入剪贴板")
    except Exception as e:
        logger.error(f"写入图片失败: {e}")
    finally:
        try: win32clipboard.CloseClipboard()
        except: pass

# ==================== Socket.IO Client ====================

sio = socketio.Client()

@sio.event
def connect():
    logger.info("已连接中转服务器")
    if CONFIG["room_id"]:
        # Send client_id (device_id) to server to identify this connection
        # This allows the server to exclude us from broadcasts we initiated via HTTP
        join_payload = {
            'room': CONFIG["room_id"],
            'client_id': CONFIG.get("device_id")
        }
        sio.emit('join', join_payload)
        logger.info(f"已加入房间: {CONFIG['room_id']} (Device ID: {CONFIG.get('device_id')})")
        console.print(Panel(f"[bold green]✓ 在线 | 房间: {CONFIG['room_id']}[/bold green]", border_style="green"))
    else:
        console.print(Panel("[bold yellow]⚠ 未配对，请在菜单中选择配对[/bold yellow]", border_style="yellow"))

@sio.event
def clipboard_sync(data):
    """收到文本同步"""
    content = data.get('content')
    # TODO: Decrypt content if we encrypt text too (Phase 3)
    if content:
        pyperclip.copy(content)
        logger.info(f"已同步剪贴板文本: {content[:20]}...")

@sio.event
def file_sync(data):
    """收到文件同步通知"""
    download_url = data.get('download_url')
    filename = data.get('filename')
    file_type = data.get('type', 'file')
    
    if download_url and filename:
        local_path = process_download(download_url, filename)
        if local_path:
            if file_type == 'image':
                set_clipboard_image(local_path)
            else:
                set_clipboard_files([local_path])

# ==================== Pairing Logic ====================

def pairing_menu():
    while True:
        console.clear()
        console.print(Panel("[bold cyan]Clipboard Push 配对向导[/bold cyan]"))
        print("1. 生成新的配对码 (我是新电脑)")
        print("2. 输入已有配对码 (加入现有圈子)")
        print("3. 返回主程序")
        
        choice = input("请选择: ")
        
        if choice == '1':
            # Generate new room & key
            new_room = f"room_{int(time.time())}"
            crypto = CryptoUtils() # Generate random key
            key_b64 = crypto.get_key_base64()
            
            payload = {
                "server": SERVER_URL,
                "room": new_room,
                "key": key_b64
            }
            payload_json = json.dumps(payload)
            
            # Show QR
            qr = qrcode.QRCode()
            qr.add_data(payload_json)
            qr.print_ascii(invert=True)
            print(f"\nRoom ID: {new_room}")
            print(f"Key: {key_b64}")
            print("\n请使用 Android App 扫描上方二维码进行配对")
            
            # Save
            CONFIG["room_id"] = new_room
            CONFIG["room_key"] = key_b64
            save_config(CONFIG)
            
            # Re-init crypto
            global CRYPTO
            CRYPTO = CryptoUtils(key_b64)
            
            input("\n按回车键启动服务...")
            return

        elif choice == '3':
            return

def main():
    # Simple CLI Loop
    force_pair = "--pair" in sys.argv or "--reset" in sys.argv
    if force_pair or not CONFIG["room_id"]:
        pairing_menu()
        if not CONFIG["room_id"]: # User exited without pairing
            return 
            
    # Init Crypto with final key
    if CONFIG["room_key"]:
        CRYPTO = CryptoUtils(CONFIG["room_key"])

    # Connect to Server
    try:
        sio.connect(SERVER_URL)
    except Exception as e:
        logger.error(f"连接服务器失败: {e}")
            
    # Main Loop
    logger.info("正在监听剪贴板推送... (按 Ctrl+C 退出)")
    try:
        # sio.wait() blocks signals on Windows. Use sleep loop instead.
        while True:
            time.sleep(1)
            if not sio.connected:
                # Optional: Reconnect logic could go here, but client lib usually handles it
                pass
    except KeyboardInterrupt:
        logger.info("正在停止客户端...")
        sio.disconnect()
        sys.exit(0)

if __name__ == '__main__':
    main()
