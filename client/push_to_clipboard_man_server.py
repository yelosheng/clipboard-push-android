"""
Clipboard Man - Push Tool
=========================
PC 端剪贴板推送工具 (Windows)

功能：
    - 读取系统剪贴板内容 (支持文件、图片、文本)
    - 推送到 Clipboard Man 服务器
    - 运行一次即退出，适合绑定快捷键

用法：
    python push_to_clipboard_man_server.py

依赖：
    pip install requests pyperclip pillow pywin32
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

# --- 配置 ---
def load_config():
    config_path = Path(__file__).parent / "config.json"
    default_config = {
        "server_url": "http://localhost:9661"
    }
    
    if config_path.exists():
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                user_config = json.load(f)
                default_config.update(user_config)
        except Exception as e:
            print(f"加载配置文件失败: {e}，使用默认配置")
            
    return default_config

CONFIG = load_config()
SERVER_URL = CONFIG["server_url"].rstrip('/')
API_TEXT = f"{SERVER_URL}/api/push/text"
API_FILE = f"{SERVER_URL}/api/push/file"

def send_text(text):
    """发送文本消息"""
    try:
        response = requests.post(API_TEXT, json={"content": text, "source": "pc_push_tool"})
        if response.status_code == 200:
            print(f"✅ 文本发送成功: {text[:50]}...")
            return True
        else:
            print(f"❌ 发送失败: {response.text}")
            return False
    except Exception as e:
        print(f"❌ 发送错误: {e}")
        return False

def send_file(file_bytes, filename):
    """发送文件/图片消息"""
    try:
        files = {'file': (filename, file_bytes)}
        data = {'source': 'pc_push_tool'}
        
        print(f"正在发送: {filename} ({len(file_bytes)} bytes)...")
        response = requests.post(API_FILE, files=files, data=data)
        
        if response.status_code == 200:
            print(f"✅ 文件发送成功: {filename}")
            return True
        else:
            print(f"❌ 发送失败: {response.text}")
            return False
    except Exception as e:
        print(f"❌ 发送错误: {e}")
        return False

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
        try:
            win32clipboard.CloseClipboard()
        except:
            pass
    return files

def main():
    print("📋 正在读取剪贴板...")
    
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
                    if send_file(file_data, file_name):
                        success_count += 1
                except Exception as e:
                    print(f"读取文件失败: {file_name}, {e}")
        
        if success_count > 0:
            print(f"共处理 {len(files)} 个文件，成功发送 {success_count} 个")
            return
        elif len(files) > 0:
            print("未能成功发送任何文件")
            return

    # 2. 尝试获取图片
    try:
        image = ImageGrab.grabclipboard()
        if isinstance(image, Image.Image):
            with io.BytesIO() as output:
                image.save(output, format='PNG')
                output.seek(0)
                image_bytes = output.read()
            
            send_file(image_bytes, f"screenshot_{int(time.time())}.png")
            return
    except Exception as e:
        print(f"处理图片时出错: {e}")

    # 3. 获取文本
    text = pyperclip.paste()
    if text and text.strip():
        send_text(text)
    else:
        print("⚠️ 剪贴板为空或不支持该格式")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"程序异常: {e}")
    
    # 稍微暂停以便用户看清结果
    time.sleep(1.5)
