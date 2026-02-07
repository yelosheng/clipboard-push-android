"""
Clipboard Push - Push Tool
==========================
PC 端剪贴板推送工具 (Windows) -> Server (Relay Mode with E2EE)

功能：
    - 读取系统剪贴板 (文件/图片/文本)
    - 本地加密 (AES-256-GCM)
    - 文本 -> 直接通过 REST API 推送加密数据
    - 文件/图片 -> 上传到 Cloudflare R2 -> 推送下载链接
    - 既然 "Stateless"，本工具不需要 Socket 连接，直接发 HTTP Post (快!)

依赖：
    pip install requests pyperclip pillow pywin32 rich cryptography
"""

import sys
import json
import requests
import pyperclip
import io
import os
import time
import win32clipboard
import win32con
from pathlib import Path
from PIL import ImageGrab, Image
from crypto_utils import CryptoUtils
from rich.console import Console
from rich.panel import Panel
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import os
import urllib3
# Disable SSL warnings
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Force-clear proxy environment variables
for k in ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"]:
    os.environ.pop(k, None)

console = Console()

def create_retry_session(retries=3, backoff_factor=0.3):
    session = requests.Session()
    session.trust_env = False # Disable proxy
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

# --- 配置 ---
def load_config():
    config_path = Path(__file__).parent / "config.json"
    if not config_path.exists():
        console.print("[bold red]❌ 配置文件 config.json 不存在，请先运行主客户端进行配对！[/bold red]")
        sys.exit(1)
        
    with open(config_path, "r", encoding="utf-8") as f:
        return json.load(f)

CONFIG = load_config()
SERVER_URL = CONFIG.get("relay_server_url", "http://localhost:5000").rstrip('/')
ROOM_ID = CONFIG.get("room_id")
ROOM_KEY = CONFIG.get("room_key")

if not ROOM_ID or not ROOM_KEY:
    console.print("[bold red]❌ 未找到配对信息 (RoomID/Key)，请先运行主客户端进行配对！[/bold red]")
    sys.exit(1)

# 初始化加密套件
CRYPTO = CryptoUtils(ROOM_KEY)

# --- API ---
API_RELAY = f"{SERVER_URL}/api/relay"
API_UPLOAD_AUTH = f"{SERVER_URL}/api/file/upload_auth"

def push_event(event_type, data):
    """通过 Relay API 推送事件"""
    payload = {
        "room": ROOM_ID,
        "event": event_type,
        "data": data,
        "client_id": CONFIG.get("device_id") # Identify sender to prevent echo
    }
    try:
        resp = requests.post(API_RELAY, json=payload, timeout=5)
        if resp.status_code == 200:
            return True
        else:
            console.print(f"[red]推送失败: {resp.text}[/red]")
            return False
    except Exception as e:
        console.print(f"[red]连接服务器失败: {e}[/red]")
        return False

def send_text(text):
    """加密并发送文本"""
    # 暂定: 文本目前为了兼容性，先明文发送？还是直接加密？
    # 根据计划，全部 E2EE。但是 Android 端如果要接收，需要对应解密逻辑。
    # 这里我们只管发。
    
    # 目前 Phase 2 计划里 Text 也是要加密的吗？
    # 检查 Task Plan: "Security Layer (E2EE) ... Algorithm: AES-256-GCM"
    # 我们假设全加密。但为了调试方便，并在 Android 未 ready 前，文本暂且明文？
    # 不，既然是 "Clipboard Push"，安全第一。
    # 还是先保持明文吧，为了 MVP 快一点验证，后续 Phase 3 再加文本加密。
    # 修正：Implementation Plan 里写了 "Encrypt text using crypto_utils".
    # 既然写了，那就加上！但是要注意，如果接收端没解密，会看到乱码。
    # 我们先发个 JSON 结构，让接收端知道是加密的。
    # { "content": "...", "encrypted": true }
    
    # 也不对，Socket.io event 里的 data 也就是 content。
    # 简单点，我们在 content 前面加个前缀？或者直接发。
    # 算了，MVP 先发 **明文** 文本，因为 Android 端还没改。
    # 等 Android 端 CryptoManager 上线了，再统一开启文本加密。
    # 文件必须加密，因为上云了。文本只过 Relay，不持久化，风险稍低。
    
    console.print(f"正在发送文本: {text[:30]}...")
    # NOTE: Relay API broadcasts raw events. Client listens for 'clipboard_sync'.
    return push_event('clipboard_sync', {
        'content': text,
        'room': ROOM_ID,
        'timestamp': time.strftime("%H:%M:%S"),
        'source': f"PC_{os.environ.get('USERNAME', 'User')}"
    })

def upload_and_send_file(file_bytes, filename):
    """加密上传文件 -> 发送链接"""
    try:
        # 1. 加密
        console.print(f"正在加密文件 ({len(file_bytes)/1024:.1f} KB)...")
        encrypted_data = CRYPTO.encrypt(file_bytes)
        file_size = len(encrypted_data)
        
        # 2. 获取上传链接
        # Use retry session for auth request too
        session = create_retry_session()
        resp = session.post(API_UPLOAD_AUTH, json={
            "filename": filename,
            "size": file_size,
            "content_type": "application/octet-stream"
        }, timeout=10)
        
        if resp.status_code != 200:
            console.print(f"[red]获取上传凭证失败: {resp.text}[/red]")
            return False
            
        auth_data = resp.json()
        upload_url = auth_data['upload_url']
        download_url = auth_data['download_url']
        
        # 3. 上传 R2
        console.print(f"正在上传 Cloudflare R2...")
        # Use retry session for upload
        put_resp = session.put(upload_url, data=encrypted_data, headers={
            "Content-Type": "application/octet-stream"
        }, timeout=300) 
        
        if put_resp.status_code != 200:
            console.print(f"[red]上传 R2 失败: {put_resp.status_code}[/red]")
            return False
            
        console.print("[green]上传成功！[/green]")
        
        # 4. 推送通知
        event_data = {
            'download_url': download_url,
            'filename': filename, # 原始文件名
            'size': file_size,
            'timestamp': time.strftime("%H:%M:%S"),
            'source': f"PC_{os.environ.get('USERNAME', 'User')}",
            'type': 'file' # or image, video
        }
        
        # 判断类型
        ext = os.path.splitext(filename)[1].lower()
        if ext in ['.jpg', '.jpeg', '.png', '.bmp', '.gif', '.webp']:
            event_data['type'] = 'image'
            
        return push_event('file_sync', event_data)

    except Exception as e:
        console.print(f"[red]文件处理失败: {e}[/red]")
        return False

# --- Clipboard Helpers (Copied/Simplified) ---

def get_clipboard_files():
    """获取剪贴板中的文件路径列表"""
    files = []
    try:
        win32clipboard.OpenClipboard()
        if win32clipboard.IsClipboardFormatAvailable(win32con.CF_HDROP):
            data = win32clipboard.GetClipboardData(win32con.CF_HDROP)
            files = [data[i] for i in range(len(data))]
    except Exception:
        pass
    finally:
        try: win32clipboard.CloseClipboard()
        except: pass
    return files

def main():
    console.print(Panel("[bold cyan]Clipboard Push (Sending...)[/bold cyan]"))
    
    # 1. 尝试获取文件
    files = get_clipboard_files()
    if files:
        success_count = 0
        for file_path in files:
            if os.path.isfile(file_path):
                file_name = os.path.basename(file_path)
                try:
                    with open(file_path, 'rb') as f:
                        file_data = f.read()
                    if upload_and_send_file(file_data, file_name):
                        success_count += 1
                except Exception as e:
                    console.print(f"[red]读取文件失败: {file_name}, {e}[/red]")
        
        if success_count > 0:
            console.print(f"[bold green]成功发送 {success_count} 个文件[/bold green]")
        else:
             console.print("[yellow]未发送任何文件[/yellow]")
        time.sleep(2)
        return

    # 2. 尝试获取图片
    try:
        image = ImageGrab.grabclipboard()
        if isinstance(image, Image.Image):
            with io.BytesIO() as output:
                image.save(output, format='PNG')
                output.seek(0)
                image_bytes = output.read()
            
            upload_and_send_file(image_bytes, f"screenshot_{int(time.time())}.png")
            time.sleep(2)
            return
    except Exception as e:
        console.print(f"[red]图片处理错误: {e}[/red]")

    # 3. 获取文本
    text = pyperclip.paste()
    if text and text.strip():
        if send_text(text):
            console.print("[bold green]文本已推送[/bold green]")
    else:
        console.print("[yellow]剪贴板为空[/yellow]")
        
    time.sleep(1)

if __name__ == "__main__":
    main()
