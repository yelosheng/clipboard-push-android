"""
Clipboard Man - PC Client
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
import time
import json
import socketio
import requests
import pyperclip
import io
import os
import ctypes
import struct
from pathlib import Path
from loguru import logger
from rich.console import Console
from rich.panel import Panel

# --- 配置 ---
def load_config():
    config_path = Path(__file__).parent / "config.json"
    default_config = {
        "server_url": "http://localhost:9661",
        "download_path": str(Path.home() / "Downloads" / "ClipboardMan"),
        "auto_copy_image": True,
        "auto_copy_file": True
    }
    
    if config_path.exists():
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                user_config = json.load(f)
                # Expand ~ in paths
                if "download_path" in user_config:
                    user_config["download_path"] = str(Path(user_config["download_path"]).expanduser())
                default_config.update(user_config)
                logger.info(f"已加载配置文件: {config_path}")
        except Exception as e:
            logger.error(f"加载配置文件失败: {e}，使用默认配置")
    else:
        logger.warning(f"配置文件不存在: {config_path}，使用默认配置")
        # Optional: Generate default config file? 
        # For now, just use defaults.

    return default_config

CONFIG = load_config()
SERVER_URL = CONFIG["server_url"]
DOWNLOAD_PATH = Path(CONFIG["download_path"]) # Ensure it's a Path object for later use

# --- 初始化 Rich Console ---
console = Console()

# --- 配置 Loguru ---
logger.remove()
logger.add(sys.stderr, level="INFO", format="<green>{time:HH:mm:ss}</green> | <level>{level: <8}</level> | <level>{message}</level>")

# --- 额外依赖检测 ---
try:
    import win32clipboard
    import win32con
    HAVE_WIN32 = True
except ImportError:
    HAVE_WIN32 = False
    logger.warning("未检测到 pywin32，图片/文件的高级复制功能将不可用 (回退到复制路径)")

try:
    from PIL import Image
    HAVE_PIL = True
except ImportError:
    HAVE_PIL = False
    logger.warning("未检测到 Pillow，图片直接复制功能将不可用")


# ==================== Windows 剪贴板高级操作 ====================

def _copy_image_to_clipboard(image_path: str) -> bool:
    """将图片内容复制到系统剪贴板（Windows, 使用 CF_DIB）。
    返回 True 表示成功，False 表示失败。"""
    if not HAVE_WIN32 or not HAVE_PIL:
        return False

    try:
        image = Image.open(image_path)
        output = io.BytesIO()
        # 保存为 BMP，去掉 14 字节文件头，保留 DIB 数据
        image.convert("RGB").save(output, "BMP")
        data = output.getvalue()[14:]
        output.close()

        win32clipboard.OpenClipboard()
        try:
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32con.CF_DIB, data)
        finally:
            win32clipboard.CloseClipboard()
        return True
    except Exception as e:
        logger.error(f"复制图片失败: {e}")
        return False


def _copy_files_to_clipboard(file_paths: list) -> bool:
    """将文件路径作为 CF_HDROP 置入剪贴板，允许在资源管理器中粘贴文件。"""
    if not HAVE_WIN32:
        return False

    try:
        # 构造 DROPFILES 结构
        file_list = '\0'.join(file_paths) + '\0\0'
        encoded = file_list.encode('utf-16le')

        pFiles = struct.pack('<I', 20)  # Offset
        pt_x = struct.pack('<i', 0)
        pt_y = struct.pack('<i', 0)
        fNC = struct.pack('<i', 0)
        fWide = struct.pack('<i', 1)  # Unicode
        dropfiles = pFiles + pt_x + pt_y + fNC + fWide
        data = dropfiles + encoded

        # 分配全局内存
        try:
            GMEM_MOVEABLE = 0x0002
            hGlobal = ctypes.windll.kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data) + 1024)
            if not hGlobal: raise MemoryError("GlobalAlloc failed")
            
            pGlobal = ctypes.windll.kernel32.GlobalLock(hGlobal)
            if not pGlobal: raise MemoryError("GlobalLock failed")
            
            ctypes.memmove(pGlobal, data, len(data))
            ctypes.windll.kernel32.GlobalUnlock(hGlobal)
        except Exception as e:
            logger.error(f"内存分配失败: {e}")
            return False

        # 写入剪贴板
        win32clipboard.OpenClipboard()
        try:
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32con.CF_HDROP, hGlobal)
        finally:
            win32clipboard.CloseClipboard()
        return True
    except Exception as e:
        logger.error(f"复制文件(HDROP)失败: {e}")
        return False


# ==================== Socket.IO Client ====================

sio = socketio.Client()

def download_file(url, filename=None):
    """下载文件并返回本地路径"""
    try:
        if not filename:
            filename = url.split('/')[-1]
        
        # 保存到用户的下载目录下的 ClipboardMan 文件夹 (使用配置)
        download_dir = DOWNLOAD_PATH
        download_dir.mkdir(parents=True, exist_ok=True)

        local_path = download_dir / filename

        # 避免文件名冲突
        counter = 1
        stem = local_path.stem
        suffix = local_path.suffix
        while local_path.exists():
            local_path = download_dir / f"{stem}_{counter}{suffix}"
            counter += 1

        logger.info(f"正在下载: {url}")
        with requests.get(url, stream=True) as r:
            r.raise_for_status()
            with open(local_path, 'wb') as f:
                for chunk in r.iter_content(chunk_size=8192):
                    f.write(chunk)
        
        logger.info(f"下载完成: {local_path}")
        return str(local_path)
    except Exception as e:
        logger.error(f"下载文件失败: {e}")
        return None

@sio.event
def connect():
    logger.info("已连接服务器")
    console.print(Panel(f"[bold green]✓ 已连接到 Clipboard Man 服务器[/bold green]\n地址: {SERVER_URL}", border_style="green"))

@sio.event
def disconnect():
    logger.warning("服务器连接断开")
    console.print(Panel("[bold red]✗ 连接已断开，正在重连...[/bold red]", border_style="red"))

@sio.event
def new_message(data):
    """处理新消息"""
    try:
        msg_type = data.get('type')
        content = data.get('content')
        file_url = data.get('file_url')
        source = data.get('source', 'Unknown')
        timestamp = data.get('timestamp', '')

        # 忽略本机推送工具发送的消息 (避免回声)
        if source == 'pc_push_tool':
            logger.info("忽略本机推送的消息")
            return


        panel_content = f"[bold cyan]来源:[/bold cyan] {source}\n[bold cyan]时间:[/bold cyan] {timestamp}\n\n"
        status_msg = ""
        success = False

        if msg_type == 'text':
            # 文本：直接复制
            pyperclip.copy(content)
            panel_content += f"[bold white]{content}[/bold white]"
            status_msg = "[bold green]✓ 文本已复制到剪贴板[/bold green]"
            success = True

        elif msg_type == 'image':
            # 图片：下载 -> 复制图片对象
            full_url = f"{SERVER_URL}{file_url}" if not file_url.startswith('http') else file_url
            local_path = download_file(full_url)
            
            if local_path:
                if _copy_image_to_clipboard(local_path):
                    status_msg = f"[bold green]✓ 图片已复制 (可直接粘贴)[/bold green]\n保存位置: {local_path}"
                    success = True
                else:
                    # 回退到文件复制
                    if _copy_files_to_clipboard([local_path]):
                        status_msg = f"[bold yellow]⚠ 图片复制失败，已复制文件 (可粘贴文件)[/bold yellow]\n保存位置: {local_path}"
                        success = True
                    else:
                        status_msg = "[bold red]✗ 复制失败[/bold red]"
                
                panel_content += f"[image] {data.get('file_name')}"

        elif msg_type in ['file', 'video', 'audio']:
            # 文件：下载 -> 复制文件对象
            full_url = f"{SERVER_URL}{file_url}" if not file_url.startswith('http') else file_url
            local_path = download_file(full_url, filename=data.get('file_name'))

            if local_path:
                if _copy_files_to_clipboard([local_path]):
                    status_msg = f"[bold green]✓ 文件已复制 (可粘贴文件)[/bold green]\n保存位置: {local_path}"
                    success = True
                else:
                    # 回退
                    pyperclip.copy(local_path)
                    status_msg = f"[bold yellow]⚠ 文件复制失败，已复制路径[/bold yellow]\n保存位置: {local_path}"
            
            panel_content += f"[file] {data.get('file_name')}"

        # 显示面板
        console.print(Panel(
            panel_content + "\n\n" + status_msg,
            title=f"📥 新消息 ({msg_type})",
            border_style="green" if success else "red"
        ))

    except Exception as e:
        logger.error(f"处理消息失败: {e}")

def main():
    try:
        console.print(Panel(
            f"[bold cyan]Clipboard Man PC Client[/bold cyan]\n"
            f"正在连接: {SERVER_URL} ...",
            border_style="blue"
        ))
        
        # 连接 Socket.IO
        sio.connect(SERVER_URL, wait_timeout=10)
        sio.wait()
        
    except KeyboardInterrupt:
        logger.info("程序退出")
        sys.exit(0)
    except Exception as e:
        logger.error(f"发生错误: {e}")
        time.sleep(5)
        main() # 简单重试

if __name__ == '__main__':
    main()
