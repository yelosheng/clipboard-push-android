# Clipboard Man 项目完整开发文档

本文档详细记录了 **Clipboard Man** 项目的开发过程、系统架构、功能实现细节以及各端的技术特性。

---

## 1. 产品概述 (Overview)

**Clipboard Man** 是一款跨设备的剪贴板同步与内容推送工具，旨在实现 Android 手机、PC（Windows）与服务器之间的高效内容流转。

### 1.1 核心价值
- **双向同步**：支持从服务器推送到手机/PC，也支持从手机/PC 分享上传到服务器。
- **多端互通**：打通了 Android 手机与 Windows PC 的剪贴板隔阂。
- **全类型支持**：不仅支持纯文本，完美支持图片（直接粘贴）、文件（直接复制）的跨设备传输。
- **即时触达**：基于 WebSocket 长连接，实现毫秒级内容推送。

### 1.2 系统架构
采用经典的 **Client-Server** 架构，Server 端作为中枢，维护所有连接和消息广播。

```
[Android Client] <---> [Server (Flask)] <---> [PC Client]
       ^                      ^
       |                      | (Socket.IO / REST API)
    (Native WS / REST API)    |
                              v
                        [Web Client] (Optional)
```

---

## 2. 后端服务 (Server)

后端基于 **Python Flask** 框架开发，是为了兼容性采用了 **双模 WebSocket** 架构。

### 2.1 技术栈
- **框架**: Flask
- **实时通信**:
  - `Flask-SocketIO`: 用于 Web 和 PC 客户端（功能更丰富，支持房间、事件）。
  - `Flask-Sock`: 用于 Android 客户端（原生 WebSocket，更轻量，适合移动端）。
- **并发模型**: Eventlet (生产环境)
- **部署**: Nginx 反向代理 + Gunicorn (可选)

### 2.2 核心实现
服务器 `app.py` 实现了以下关键逻辑：

1.  **双协议广播 (`broadcast_message`)**：
    当接收到新消息时，服务器会同时向 `socketio` 连接池（PC端）和 `websocket_clients_native` 连接池（Android端）发送消息，确保协议互通。
    ```python
    def broadcast_message(message):
        socketio.emit('new_message', message) # 给 PC
        for ws in websocket_clients_native: ws.send(json.dumps(message)) # 给 Android
    ```

2.  **统一消息格式**：
    无论是文本还是文件，都统一封装为 JSON 对象：
    ```json
    {
      "id": "uuid...",
      "type": "text/image/file",
      "content": "...",
      "file_url": "/files/xxx.jpg",
      "timestamp": "..."
    }
    ```

3.  **REST API 支持**：
    除了 WebSocket 推送，还提供了 `POST /api/push/text` 和 `POST /api/push/file` 接口，方便第三方集成或简单客户端调用。

---

## 3. 安卓客户端 (Android App)

Android 端采用 **Kotlin** 原生开发，不仅是一个接收端，也是一个强大的内容发送端。

### 3.1 技术栈
- **语言**: Kotlin
- **网络层**: OkHttp (REST API + WebSocket)
- **序列化**: Gson
- **架构**: MVVM (ViewModel + Repository)
- **后台机制**: Foreground Service (前台服务)

### 3.2 关键功能实现
1.  **原生 WebSocket 客户端 (`WebSocketClient.kt`)**：
    - 使用 OkHttp 的 `newWebSocket` 接口。
    - **自适应重连**：实现了指数退避（Exponential Backoff）重连策略（1s -> 2s -> 4s...），确保网络波动后的自动恢复。
    - **心跳机制**：每 30 秒发送 Ping 包，防止 NAT 超时连接断开。

2.  **后台保活 (`ClipboardService`)**：
    - 使用 `startForeground` 启动前台服务，并显示常驻通知，极大幅度降低被系统杀死的概率。
    - 绑定 WebSocket 生命周期到 Service，确保应用切到后台甚至锁屏后仍能接收推送。

3.  **系统分享集成 (`ShareReceiverActivity`)**：
    - 注册了 `android.intent.action.SEND` 意图过滤器 (Intent Filter)。
    - 支持从相册、浏览器、文件管理器直接 "分享" 到 Clipboard Man，自动上传到服务器。

4.  **剪贴板写入**：
    收到消息后，自动调用 `ClipboardManager` 将文本写入系统剪贴板。

---

## 4. PC 客户端 (PC Client)

PC 客户端 (`clipboard_man_client.py`) 是一个基于命令行的 Python 程序，但提供了极强的系统集成能力。

### 4.1 技术栈
- **语言**: Python
- **UI**: `rich` (TUI 终端界面，美观不仅是面子，也是体验)
- **通信**: `python-socketio` (Socket.IO 协议)
- **系统API**: `pywin32`, `ctypes`

### 4.2 核心黑科技：Windows 剪贴板高级操作
PC 端不仅仅是复制文本，它实现了 Windows 剪贴板的底层格式写入：

1.  **图片直接复制 (`CF_DIB`)**：
    - 当收到图片推送时，客户端下载图片。
    - 使用 `Pillow` 转换图片格式，去掉 BMP 头。
    - 调用 `win32clipboard.SetClipboardData(win32con.CF_DIB, ...)` 将图片二进制数据写入剪贴板。
    - **效果**：用户可以在微信、PS、Word 中直接 `Ctrl+V` 粘贴图片，而不是粘贴路径。

2.  **文件直接复制 (`CF_HDROP`)**：
    - 当收到文件推送时，下载到本地。
    - 构造 Windows `DROPFILES` 结构体，分配全局内存。
    - 写入 `CF_HDROP` 格式。
    - **效果**：用户可以在资源管理器中直接 `Ctrl+V` 粘贴文件（就像你也从另一个文件夹 `Ctrl+C` 了一样）。

### 4.3 交互体验
- 使用 `rich.console` 和 `Panel` 渲染彩色日志和消息卡片。
- 支持配置文件 `config.json`，可自定义服务器地址和下载路径。

---

## 5. 开发流程回顾

整个项目的开发遵循了 **Server First -> Client Next** 的迭代路径：

1.  **阶段一：基础设施 (Server)**
    - 搭建 Flask 基础框架。
    - 定义 API 接口规范与 JSON 消息格式。
    - 实现基础的 REST 推送接口。

2.  **阶段二：核心协议 (Hybrid WebSocket)**
    - 引入 Socket.IO 支持 Web/PC 实时通信。
    - 发现 Socket.IO 在 Android 端的库兼容性问题，决定引入 `flash-sock` 增加原生 WebSocket 支持。
    - 实现双向消息广播，打通两种协议。

3.  **阶段三：PC 客户端深度集成**
    - 开发 Python 脚本连接 Server。
    - 攻克 Windows 剪贴板底层 API，实现"所见即所得"的图片/文件复制体验。
    - 优化 TUI 界面。

4.  **阶段四：Android 客户端完善**
    - 实现 UI 界面与连接逻辑。
    - 攻克后台保活难题，引入 Foreground Service。
    - 完善网络重连机制，确保移动环境下的稳定性。

---

## 6. 功能清单 (Feature List)

| 功能 | Server | Android App | PC Client |
| :--- | :---: | :---: | :---: |
| **文本推送** | ✅ | ✅ (接收+发送) | ✅ (接收) |
| **图片推送** | ✅ | ✅ (接收+发送) | ✅ (接收+原生粘贴) |
| **文件推送** | ✅ | ✅ (接收+发送) | ✅ (接收+资源管理器粘贴) |
| **历史记录** | ✅ (内存存储) | ✅ (UI展示) | ✅ (日志展示) |
| **自动重连** | - | ✅ (指数退避) | ✅ (内置重试) |
| **后台保活** | - | ✅ (前台服务) | - |
| **跨协议互通** | ✅ | - | - |

---

## 7. 部署说明

- **服务器端口**: 默认为 `9661`。
- **Android连接**: `ws://<server-ip>:9661/ws`
- **PC连接**: `http://<server-ip>:9661` (Socket.IO 自动处理协议)
- **部署建议**: 生产环境建议使用 Nginx 反向代理，并配置 SSL 证书以启用 HTTPS/WSS 安全连接。
