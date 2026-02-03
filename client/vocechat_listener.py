"""
VoceChat Clipboard Listener
============================
监听 VoceChat Webhook 服务器的消息并自动复制到剪贴板

功能：
    - 定期轮询服务器获取新消息
    - 自动将消息内容复制到系统剪贴板
    - 支持文本、链接、文件路径
    - 美化的 UI 显示
    - 自动重连机制

使用方法：
    python vocechat_listener.py
    
配置文件：
    vocechat_client_config.json
"""

import time
import pyperclip  # 用来操作剪贴板
import requests
import json
import sys
from pathlib import Path
from loguru import logger
from rich.console import Console
from rich.panel import Panel

# --- 初始化 Rich Console ---
console = Console()

# --- 配置 Loguru ---
logger.remove()  # 移除默认处理器
logger.add(sys.stderr, level="INFO", format="<green>{time:HH:mm:ss}</green> | <level>{level: <8}</level> | <level>{message}</level>")

# --- 额外依赖（可回退） ---
import os
import struct
import ctypes
import io

# 尝试导入 Windows 剪贴板 / Pillow，如不可用则回退到路径复制
try:
    import win32clipboard
    import win32con
    HAVE_WIN32 = True
except Exception:
    HAVE_WIN32 = False
    logger.warning("未检测到 pywin32（win32clipboard），文件/图片复制到剪贴板的功能将回退到复制路径")

try:
    from PIL import Image
    HAVE_PIL = True
except Exception:
    HAVE_PIL = False
    logger.warning("未检测到 Pillow，图片直接复制到剪贴板的功能将不可用")


def _copy_image_to_clipboard(image_path: str) -> bool:
    """将图片内容复制到系统剪贴板（Windows, 使用 CF_DIB）。
    返回 True 表示成功，False 表示失败（或不支持）。"""
    logger.debug(f"_copy_image_to_clipboard: HAVE_WIN32={HAVE_WIN32}, HAVE_PIL={HAVE_PIL}, path={image_path}")
    if not HAVE_WIN32 or not HAVE_PIL:
        logger.debug("_copy_image_to_clipboard: 环境不支持（缺少 win32 或 PIL），回退")
        return False

    try:
        logger.debug(f"尝试打开图片: {image_path}")
        image = Image.open(image_path)
        output = io.BytesIO()
        # 保存为 BMP，然后去掉 BMP 的 14 字节头，留下 DIB 数据
        image.convert("RGB").save(output, "BMP")
        data = output.getvalue()[14:]
        logger.debug(f"图片转换为 DIB，字节长度: {len(data)}")

        win32clipboard.OpenClipboard()
        try:
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32con.CF_DIB, data)
        finally:
            win32clipboard.CloseClipboard()
        logger.info(f"图片已复制到剪贴板: {image_path}")
        return True
    except Exception as e:
        logger.exception(f"将图片复制到剪贴板失败: {e}")
        return False


def _copy_files_to_clipboard(file_paths: list) -> bool:
    """将文件路径作为 CF_HDROP 置入剪贴板，允许在 Explorer 中粘贴文件。
    返回 True 表示成功，False 表示失败（或不支持）。"""
    if not HAVE_WIN32:
        return False

    try:
        # 构造以双 null 结尾的 UTF-16LE 路径列表
        file_list = '\0'.join(file_paths) + '\0\0'
        encoded = file_list.encode('utf-16le')

        # DROPFILES 结构：pFiles (DWORD), pt.x (LONG), pt.y (LONG), fNC (BOOL), fWide (BOOL)
        pFiles = struct.pack('<I', 20)  # 偏移到文件列表，DROPFILES 结构为 20 字节
        pt_x = struct.pack('<i', 0)
        pt_y = struct.pack('<i', 0)
        fNC = struct.pack('<i', 0)
        fWide = struct.pack('<i', 1)  # 使用 Unicode
        dropfiles = pFiles + pt_x + pt_y + fNC + fWide

        data = dropfiles + encoded

        GMEM_MOVEABLE = 0x0002
        GMEM_ZEROINIT = 0x0040

        def _alloc_and_set(data_bytes):
            size = len(data_bytes)
            # 尝试常规分配
            h = ctypes.windll.kernel32.GlobalAlloc(GMEM_MOVEABLE, size)
            if not h:
                return None, "GlobalAlloc 失败"
            p = ctypes.windll.kernel32.GlobalLock(h)
            if not p:
                ctypes.windll.kernel32.GlobalFree(h)
                return None, "GlobalLock 失败"
            try:
                ctypes.memmove(p, data_bytes, size)
            finally:
                ctypes.windll.kernel32.GlobalUnlock(h)
            return h, None

        # 第一次尝试常规分配
        hGlobal, err = _alloc_and_set(data)
        if not hGlobal:
            logger.debug(f"首次 GlobalAlloc/Lock 失败: {err}，尝试使用 GMEM_ZEROINIT 并增加缓冲区")
            # 尝试备用分配策略：使用 GMEM_ZEROINIT，并略微增加分配大小
            try:
                hGlobal = ctypes.windll.kernel32.GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, len(data) + 1024)
                if not hGlobal:
                    raise MemoryError("备用 GlobalAlloc 失败")
                pGlobal = ctypes.windll.kernel32.GlobalLock(hGlobal)
                if not pGlobal:
                    ctypes.windll.kernel32.GlobalFree(hGlobal)
                    raise MemoryError("备用 GlobalLock 失败")
                # 将数据写入（确保不越界）
                ctypes.memmove(pGlobal, data, len(data))
                ctypes.windll.kernel32.GlobalUnlock(hGlobal)
            except Exception as e:
                logger.exception(f"备用分配也失败: {e}")
                return False

        # 成功获得 hGlobal 后设置到剪贴板
        try:
            win32clipboard.OpenClipboard()
            try:
                win32clipboard.EmptyClipboard()
                win32clipboard.SetClipboardData(win32con.CF_HDROP, hGlobal)
            finally:
                win32clipboard.CloseClipboard()
            return True
        except Exception as e:
            logger.exception(f"设置剪贴板数据失败: {e}")
            try:
                ctypes.windll.kernel32.GlobalFree(hGlobal)
            except Exception:
                pass
            return False
    except Exception as e:
        logger.error(f"将文件复制到剪贴板（CF_HDROP）失败: {e}")
        return False


def _is_image_file(path: str) -> bool:
    """尽量判断文件是否为图像。优先使用 Pillow 打开确定。"""
    logger.debug(f"_is_image_file: HAVE_PIL={HAVE_PIL}, path={path}")
    if not HAVE_PIL:
        # 用扩展名做粗略判断
        ext = os.path.splitext(path)[1].lower()
        is_img = ext in {'.png', '.jpg', '.jpeg', '.bmp', '.gif', '.webp', '.tiff'}
        logger.debug(f"_is_image_file: 没有 PIL，基于扩展名判断 -> {ext} -> {is_img}")
        return is_img

    try:
        Image.open(path).close()
        logger.debug(f"_is_image_file: Pillow 打开成功，判定为图片: {path}")
        return True
    except Exception as e:
        logger.debug(f"_is_image_file: Pillow 打开失败，不是图片或损坏: {e}")
        return False

# ===================== 加载配置 =====================

def load_config():
    """从 vocechat_client_config.json 加载配置"""
    config_path = Path(__file__).parent / "vocechat_client_config.json"
    
    if not config_path.exists():
        logger.warning(f"配置文件不存在: {config_path}，使用默认配置")
        return {
            "WEBHOOK_API_URL": "http://192.168.2.5:6588",
            "POLL_INTERVAL": 2,
            "FILTER_USERS": []
        }
    
    with open(config_path, 'r', encoding='utf-8') as f:
        config = json.load(f)
    
    logger.info(f"配置加载成功: {config_path}")
    return config


CONFIG = load_config()

WEBHOOK_API_URL = CONFIG.get("WEBHOOK_API_URL", "http://192.168.2.5:6588")
POLL_INTERVAL = CONFIG.get("POLL_INTERVAL", 2)  # 轮询间隔（秒）
FILTER_USERS = CONFIG.get("FILTER_USERS", [])  # 过滤特定用户（空列表 = 接受所有）

# =====================================================


class VoceChatListener:
    """VoceChat 消息监听器"""
    
    def __init__(self, api_url: str, poll_interval: int = 2):
        """
        初始化监听器
        
        Args:
            api_url: Webhook 服务器 API URL
            poll_interval: 轮询间隔（秒）
        """
        self.api_url = api_url.rstrip('/')
        self.poll_interval = poll_interval
        self.last_check_time = time.time()  # 上次检查的时间戳
        self.running = False
        self.retry_count = 0
        self.max_retries = 5
        
    def start(self):
        """启动监听器"""
        self.running = True
        logger.info(f"VoceChat Listener 已启动")
        logger.info(f"服务器: {self.api_url}")
        logger.info(f"轮询间隔: {self.poll_interval} 秒")
        
        console.print(Panel(
            f"[green]✓[/green] VoceChat Listener 已启动\n\n"
            f"[cyan]服务器:[/cyan] {self.api_url}\n"
            f"[cyan]轮询间隔:[/cyan] {self.poll_interval} 秒\n\n"
            f"[yellow]按 Ctrl+C 退出[/yellow]",
            title="📋 VoceChat Clipboard Listener",
            border_style="green"
        ))
        
        while self.running:
            try:
                self.poll_messages()
                self.retry_count = 0  # 成功后重置重试计数
                time.sleep(self.poll_interval)
                
            except KeyboardInterrupt:
                logger.info("收到退出信号")
                self.stop()
                break
                
            except Exception as e:
                logger.error(f"轮询异常: {e}")
                self.retry_count += 1
                
                if self.retry_count >= self.max_retries:
                    logger.error(f"达到最大重试次数 ({self.max_retries})，等待 30 秒...")
                    time.sleep(30)
                    self.retry_count = 0
                else:
                    # 指数退避
                    wait_time = min(2 ** self.retry_count, 60)
                    logger.info(f"等待 {wait_time} 秒后重试...")
                    time.sleep(wait_time)
    
    def stop(self):
        """停止监听器"""
        self.running = False
        logger.info("VoceChat Listener 已停止")
        console.print("\n[blue]程序已退出[/blue]")
    
    def poll_messages(self):
        """轮询获取新消息"""
        try:
            # 构建请求 URL
            url = f"{self.api_url}/api/get_messages"
            params = {"since": self.last_check_time}
            
            # 禁用代理（避免局域网访问被代理拦截）
            proxies = {
                'http': None,
                'https': None
            }
            
            # 发送请求
            response = requests.get(url, params=params, timeout=10, proxies=proxies)
            
            if response.status_code != 200:
                logger.warning(f"服务器返回错误: {response.status_code}")
                # 显示详细错误信息
                try:
                    error_data = response.json()
                    logger.error(f"服务器错误详情: {error_data}")
                except:
                    logger.error(f"服务器响应内容: {response.text[:500]}")
                return
            
            data = response.json()
            
            if not data.get("success"):
                logger.error(f"API 返回失败: {data.get('error', 'Unknown error')}")
                return
            
            messages = data.get("messages", [])
            server_time = data.get("server_time", time.time())
            
            # 处理每条消息
            if messages:
                logger.info(f"收到 {len(messages)} 条新消息")
                for msg in messages:
                    self.process_message(msg)
            
            # 更新时间戳（使用服务器时间更准确）
            self.last_check_time = server_time
            
        except requests.RequestException as e:
            logger.error(f"请求失败: {e}")
        except Exception as e:
            logger.exception(f"处理消息时出错: {e}")
    
    def process_message(self, message: dict):
        """
        处理单条消息
        
        Args:
            message: 消息字典，包含 timestamp, from_uid, content_type, content, properties
        """
        try:
            from_uid = message.get("from_uid")
            content_type = message.get("content_type", "")
            content = message.get("content", "")
            properties = message.get("properties", {})
            msg_datetime = message.get("datetime", "")
            
            # 过滤用户（如果配置了）
            if FILTER_USERS and from_uid not in FILTER_USERS:
                logger.debug(f"跳过用户 {from_uid} 的消息（不在过滤列表中）")
                return
            
            # 准备显示内容
            panel_content = f"[bold cyan]发送者:[/bold cyan] UID {from_uid}\n"
            panel_content += f"[bold cyan]时间:[/bold cyan] {msg_datetime}\n"
            panel_content += f"[bold cyan]类型:[/bold cyan] {content_type}\n\n"
            
            content_to_copy = None
            copy_type_str = "content"
            clipboard_copied = False  # 标记是否已用二进制方式（文件/图片）复制到剪贴板

            # 根据消息类型处理
            if content_type == "text/plain" or content_type == "text/markdown":
                # 文本消息
                display_content = content
                content_to_copy = content.strip()
                copy_type_str = "文本"

            elif content_type == "vocechat/file":
                # 文件消息
                file_name = properties.get("name", "Unknown")
                file_size = properties.get("size", 0)
                saved_path = properties.get("saved_path", "")
                download_error = properties.get("download_error", "")

                if download_error:
                    display_content = f"⚠️ 文件下载失败: {file_name}\n错误: {download_error}"
                    copy_type_str = "错误信息"
                    content_to_copy = download_error
                elif saved_path:
                    display_content = f"📁 文件已保存: {file_name}\n路径: {saved_path}\n大小: {file_size} bytes"

                    # 先尝试将文件或图片本体放入系统剪贴板（Windows）。失败则回退到复制路径文本。
                    try:
                        # 映射网络路径到本地驱动（例如将 /mnt/nas/... 映射到 Z:/...），便于 Windows 下访问
                        mapped_path = saved_path
                        try:
                            if isinstance(saved_path, str) and saved_path.startswith('/mnt/nas/'):
                                mapped_path = saved_path.replace('/mnt/nas/', 'Z:/')
                                logger.debug(f"路径映射: {saved_path} -> {mapped_path}")
                        except Exception as e:
                            logger.debug(f"路径映射失败: {e}")

                        p = Path(mapped_path)
                        logger.debug(f"处理文件: saved_path={saved_path}, mapped_path={mapped_path}, exists={p.exists()}")
                        if p.exists():
                            is_img = _is_image_file(mapped_path)
                            logger.debug(f"文件是否为图片: {is_img}")
                            if is_img:
                                ok = _copy_image_to_clipboard(mapped_path)
                                logger.debug(f"_copy_image_to_clipboard 返回: {ok}")
                                if ok:
                                    copy_type_str = "图片"
                                    clipboard_copied = True
                                else:
                                    # 若图片复制失败，尝试以文件方式复制（CF_HDROP）
                                    ok2 = _copy_files_to_clipboard([str(p)])
                                    logger.debug(f"_copy_files_to_clipboard（作为文件）返回: {ok2}")
                                    if ok2:
                                        copy_type_str = "文件"
                                        clipboard_copied = True
                                    else:
                                        logger.debug("图片与文件二进制复制均失败，回退到复制路径文本")
                                        copy_type_str = "文件路径"
                                        content_to_copy = saved_path
                            else:
                                # 非图片文件：不尝试二进制复制（CF_HDROP），直接复制文件路径（保存已完成）
                                logger.debug("非图片文件，跳过二进制复制，直接复制路径文本")
                                copy_type_str = "文件路径"
                                content_to_copy = mapped_path
                        else:
                            # saved_path 在本地不可访问，尝试从消息中下载文件（download_url 或 content_base64）
                            logger.debug("saved_path 在本地不可访问，尝试从 properties 中寻找下载信息")
                            download_url = properties.get('download_url') or properties.get('url') or properties.get('file_url') or properties.get('download_uri')
                            content_b64 = properties.get('content_base64') or properties.get('content_b64')

                            tmp_path = None
                            downloaded = False
                            if download_url:
                                logger.debug(f"发现 download_url，尝试下载: {download_url}")
                                try:
                                    r = requests.get(download_url, stream=True, timeout=15)
                                    if r.status_code == 200:
                                        import tempfile
                                        tmp_fd, tmp_name = tempfile.mkstemp(suffix=Path(download_url).suffix or '')
                                        with open(tmp_name, 'wb') as f:
                                            for chunk in r.iter_content(8192):
                                                if chunk:
                                                    f.write(chunk)
                                        tmp_path = tmp_name
                                        downloaded = True
                                        logger.info(f"已从 download_url 下载到临时文件: {tmp_path}")
                                    else:
                                        logger.error(f"下载文件失败，HTTP 状态: {r.status_code}")
                                except Exception as e:
                                    logger.exception(f"通过 download_url 下载文件失败: {e}")

                            elif content_b64:
                                logger.debug("发现 content_base64，尝试写入临时文件")
                                try:
                                    import tempfile, base64
                                    decoded = base64.b64decode(content_b64)
                                    tmp_fd, tmp_name = tempfile.mkstemp(suffix='')
                                    with open(tmp_name, 'wb') as f:
                                        f.write(decoded)
                                    tmp_path = tmp_name
                                    downloaded = True
                                    logger.info(f"已将 content_base64 写入临时文件: {tmp_path}")
                                except Exception as e:
                                    logger.exception(f"写入 content_base64 临时文件失败: {e}")

                            if downloaded and tmp_path:
                                try:
                                    is_img = _is_image_file(tmp_path)
                                    logger.debug(f"下载后文件是否为图片: {is_img}")
                                    if is_img:
                                        ok = _copy_image_to_clipboard(tmp_path)
                                        logger.debug(f"下载后 _copy_image_to_clipboard 返回: {ok}")
                                        if ok:
                                            copy_type_str = "图片"
                                            clipboard_copied = True
                                        else:
                                            ok2 = _copy_files_to_clipboard([tmp_path])
                                            logger.debug(f"下载后 _copy_files_to_clipboard 返回: {ok2}")
                                            if ok2:
                                                copy_type_str = "文件"
                                                clipboard_copied = True
                                            else:
                                                copy_type_str = "文件路径"
                                                content_to_copy = saved_path
                                    else:
                                        # 下载后为非图片：不尝试二进制复制，直接复制原始路径文本以保持一致
                                        logger.debug("下载后为非图片，跳过二进制复制，复制路径文本")
                                        copy_type_str = "文件路径"
                                        content_to_copy = saved_path
                                finally:
                                    try:
                                        import os
                                        os.remove(tmp_path)
                                        logger.debug(f"已删除临时文件: {tmp_path}")
                                    except Exception:
                                        pass
                            else:
                                logger.debug("未能下载文件，回退到复制路径文本")
                                copy_type_str = "文件路径"
                                content_to_copy = saved_path
                    except Exception as e:
                        logger.exception(f"处理文件剪贴板时出错: {e}")
                        copy_type_str = "文件路径"
                        content_to_copy = saved_path

                else:
                    display_content = f"📄 文件: {file_name} ({file_size} bytes)"
                    copy_type_str = "文件名"
                    content_to_copy = file_name
            else:
                # 其他类型
                display_content = content
                content_to_copy = content
                copy_type_str = "内容"
            
            # 显示内容（限制长度）
            if display_content:
                preview = display_content[:200] + ('...' if len(display_content) > 200 else '')
                panel_content += f"[bold cyan]内容:[/bold cyan]\n{preview}\n\n"
            
            # 复制到剪贴板
            copy_status = "[grey50]无内容可复制[/grey50]"
            if clipboard_copied:
                # 已用二进制方式复制（图片或文件）
                copy_status = f"[bold green]✓ {copy_type_str} 已复制到剪贴板![/bold green]"
                src = None
                try:
                    src = saved_path
                except NameError:
                    src = content_to_copy
                logger.info(f"已以二进制方式复制: {copy_type_str} (来源: {src})")
            elif content_to_copy:
                try:
                    pyperclip.copy(content_to_copy)
                    copy_status = f"[bold green]✓ {copy_type_str} 已复制到剪贴板![/bold green]"
                    logger.info(f"已复制: {copy_type_str} (长度: {len(content_to_copy)})")
                except Exception as e:
                    logger.error(f"复制到剪贴板失败: {e}")
                    copy_status = f"[bold red]✗ 复制失败: {e}[/bold red]"
            
            panel_content += copy_status
            
            # 显示面板
            console.print(Panel(
                panel_content,
                title="📥 新消息",
                border_style="blue",
                expand=False
            ))
            
        except Exception as e:
            logger.error(f"处理消息失败: {e}")


def main():
    """主函数"""
    try:
        listener = VoceChatListener(
            api_url=WEBHOOK_API_URL,
            poll_interval=POLL_INTERVAL
        )
        listener.start()
        
    except KeyboardInterrupt:
        console.print("\n[yellow]程序已退出[/yellow]")
    except Exception as e:
        logger.exception(f"程序异常: {e}")
        console.print(f"[bold red]错误:[/bold red] {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
