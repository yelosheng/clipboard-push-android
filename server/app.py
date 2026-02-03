"""
Clipboard Man - Flask WebSocket Server
跨设备剪贴板同步服务器

端口: 9661
"""

import os
import json
import uuid
import mimetypes
from datetime import datetime
from flask import Flask, request, jsonify, send_from_directory
from flask_socketio import SocketIO, emit
from flask_cors import CORS
from flask_sock import Sock

# 配置
UPLOAD_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'uploads')
MAX_CONTENT_LENGTH = 100 * 1024 * 1024  # 100MB 最大文件大小
PORT = 9661

# 初始化 Flask
app = Flask(__name__)
app.config['MAX_CONTENT_LENGTH'] = MAX_CONTENT_LENGTH
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

# 跨域支持
CORS(app)

# WebSocket (Socket.IO for web client)
# async_mode='eventlet' 适合生产环境
# engineio_logger=True 用于调试 nginx 反向代理问题
socketio = SocketIO(
    app, 
    cors_allowed_origins="*",
    async_mode='eventlet',
    logger=False,
    engineio_logger=False,
    ping_timeout=60,
    ping_interval=25
)

# Native WebSocket (for Android client)
sock = Sock(app)

# 消息历史存储（内存，重启后清空）
messages = []
MAX_MESSAGES = 100  # 最多保存100条消息

# 已连接的客户端
connected_clients = set()  # Socket.IO clients
websocket_clients_native = set()  # Native WebSocket clients


def get_mime_type(filename):
    """获取文件 MIME 类型"""
    mime_type, _ = mimetypes.guess_type(filename)
    return mime_type or 'application/octet-stream'


def detect_message_type(mime_type):
    """根据 MIME 类型判断消息类型"""
    if mime_type.startswith('image/'):
        return 'image'
    elif mime_type.startswith('video/'):
        return 'video'
    elif mime_type.startswith('audio/'):
        return 'audio'
    else:
        return 'file'


def broadcast_message(message):
    """广播消息给所有客户端（Socket.IO + Native WebSocket）"""
    # Socket.IO clients
    socketio.emit('new_message', message)

    # Native WebSocket clients
    for ws_client in list(websocket_clients_native):
        try:
            ws_client.send(json.dumps(message))
        except Exception as e:
            print(f"[Native WS] Failed to send to client: {e}")
            websocket_clients_native.discard(ws_client)


def save_message(message):
    """保存消息到历史记录"""
    messages.append(message)
    if len(messages) > MAX_MESSAGES:
        messages.pop(0)


# ==================== REST API ====================

@app.route('/')
def index():
    """服务器状态页"""
    return jsonify({
        'name': 'Clipboard Man Server',
        'version': '1.0.0',
        'status': 'running',
        'port': PORT,
        'connected_clients': {
            'socketio': len(connected_clients),
            'native_ws': len(websocket_clients_native),
            'total': len(connected_clients) + len(websocket_clients_native)
        },
        'total_messages': len(messages),
        'endpoints': {
            'push_text': 'POST /api/push/text',
            'push_file': 'POST /api/push/file',
            'get_messages': 'GET /api/messages',
            'get_file': 'GET /files/<filename>',
            'websocket_native': f'ws://host:{PORT}/ws',
            'websocket_socketio': f'ws://host:{PORT}/socket.io/'
        }
    })


@app.route('/api/push/text', methods=['POST'])
def push_text():
    """推送文本消息

    请求体 (JSON):
        {"content": "文本内容"}

    来源：Web 客户端或 Android App
    """
    data = request.get_json() or {}
    content = data.get('content', '')
    source = data.get('source', 'unknown')  # 可选：标识来源

    if not content:
        return jsonify({'status': 'error', 'error': '内容不能为空'}), 400

    message = {
        'id': str(uuid.uuid4()),
        'type': 'text',
        'content': content,
        'source': source,
        'timestamp': datetime.now().isoformat(),
    }

    # 保存并广播
    save_message(message)
    broadcast_message(message)

    print(f"[PUSH] 文本消息 (from {source}): {content[:50]}{'...' if len(content) > 50 else ''}")
    return jsonify({'status': 'ok', 'message': message})


@app.route('/api/push/file', methods=['POST'])
def push_file():
    """推送文件（图片/视频/其他）

    请求体 (multipart/form-data):
        file: 文件
        source: 来源（可选）

    来源：Web 客户端或 Android App
    """
    if 'file' not in request.files:
        return jsonify({'status': 'error', 'error': '没有上传文件'}), 400

    file = request.files['file']
    if file.filename == '':
        return jsonify({'status': 'error', 'error': '文件名为空'}), 400

    source = request.form.get('source', 'unknown')

    # 生成唯一文件名
    original_filename = file.filename
    ext = os.path.splitext(original_filename)[1]
    unique_filename = f"{uuid.uuid4()}{ext}"

    # 确保上传目录存在
    os.makedirs(UPLOAD_FOLDER, exist_ok=True)

    # 保存文件
    filepath = os.path.join(UPLOAD_FOLDER, unique_filename)
    file.save(filepath)
    file_size = os.path.getsize(filepath)

    # 获取文件类型
    mime_type = get_mime_type(original_filename)
    msg_type = detect_message_type(mime_type)

    message = {
        'id': str(uuid.uuid4()),
        'type': msg_type,
        'content': f'[{msg_type.upper()}] {original_filename}',
        'file_url': f'/files/{unique_filename}',
        'file_name': original_filename,
        'file_size': file_size,
        'mime_type': mime_type,
        'source': source,
        'timestamp': datetime.now().isoformat(),
    }

    # 保存并广播
    save_message(message)
    broadcast_message(message)

    print(f"[PUSH] 文件消息 (from {source}): {original_filename} ({file_size} bytes)")
    return jsonify({'status': 'ok', 'message': message})


@app.route('/api/messages')
def get_messages():
    """获取历史消息"""
    limit = request.args.get('limit', 50, type=int)
    after_id = request.args.get('after_id', None)

    result = messages[-limit:]

    # 如果指定了 after_id，只返回该 ID 之后的消息
    if after_id:
        found = False
        filtered = []
        for msg in messages:
            if found:
                filtered.append(msg)
            elif msg['id'] == after_id:
                found = True
        result = filtered[-limit:]

    return jsonify({
        'status': 'ok',
        'count': len(result),
        'messages': result
    })


@app.route('/files/<filename>')
def serve_file(filename):
    """文件下载"""
    return send_from_directory(UPLOAD_FOLDER, filename)


# ==================== Socket.IO 事件 (Web 客户端) ====================

@socketio.on('connect')
def handle_connect():
    """客户端连接"""
    client_id = request.sid
    connected_clients.add(client_id)
    print(f"[Socket.IO] 客户端连接: {client_id} (在线: {len(connected_clients)})")

    emit('connected', {
        'status': 'ok',
        'client_id': client_id,
        'server_time': datetime.now().isoformat()
    })


@socketio.on('disconnect')
def handle_disconnect():
    """客户端断开"""
    client_id = request.sid
    connected_clients.discard(client_id)
    print(f"[Socket.IO] 客户端断开: {client_id} (在线: {len(connected_clients)})")


@socketio.on('ping')
def handle_ping():
    """心跳检测"""
    emit('pong', {'time': datetime.now().isoformat()})


# ==================== Native WebSocket (Android 客户端) ====================

@sock.route('/ws')
def websocket_endpoint(ws):
    """原生 WebSocket 端点（for Android client）"""
    client_addr = request.remote_addr
    print(f"[Native WS] 客户端连接: {client_addr} (在线: {len(websocket_clients_native) + 1})")
    websocket_clients_native.add(ws)

    try:
        # 发送欢迎消息
        ws.send(json.dumps({
            'type': 'connected',
            'server_time': datetime.now().isoformat()
        }))

        # 保持连接，接收消息
        while True:
            data = ws.receive()
            if data:
                print(f"[Native WS] 收到消息: {data[:100]}{'...' if len(data) > 100 else ''}")

                # 处理客户端发来的消息
                try:
                    msg = json.loads(data)
                    handle_native_ws_message(ws, msg)
                except json.JSONDecodeError:
                    print(f"[Native WS] 无效的 JSON: {data}")

    except Exception as e:
        print(f"[Native WS] 错误: {e}")
    finally:
        websocket_clients_native.discard(ws)
        print(f"[Native WS] 客户端断开: {client_addr} (在线: {len(websocket_clients_native)})")


def handle_native_ws_message(ws, msg):
    """处理原生 WebSocket 客户端发来的消息"""
    msg_type = msg.get('type', '')

    if msg_type == 'ping':
        # 心跳响应
        ws.send(json.dumps({
            'type': 'pong',
            'time': datetime.now().isoformat()
        }))

    elif msg_type == 'text':
        # 客户端通过 WebSocket 发送文本
        content = msg.get('content', '')
        if content:
            message = {
                'id': str(uuid.uuid4()),
                'type': 'text',
                'content': content,
                'source': 'android_ws',
                'timestamp': datetime.now().isoformat(),
            }
            save_message(message)
            broadcast_message(message)
            print(f"[Native WS] 收到文本推送: {content[:50]}...")


# ==================== 启动服务器 ====================

if __name__ == '__main__':
    # 确保上传目录存在
    os.makedirs(UPLOAD_FOLDER, exist_ok=True)

    print(f"""
╔══════════════════════════════════════════════════════════╗
║            Clipboard Man Server v1.0.0                   ║
╠══════════════════════════════════════════════════════════╣
║  端口: {PORT}                                               ║
║  上传目录: {UPLOAD_FOLDER}
║                                                          ║
║  REST API:                                               ║
║    POST /api/push/text   - 推送文本                      ║
║    POST /api/push/file   - 推送文件（图片/视频/文件）    ║
║    GET  /api/messages    - 获取历史消息                  ║
║    GET  /files/<name>    - 下载文件                      ║
║                                                          ║
║  WebSocket:                                              ║
║    ws://0.0.0.0:{PORT}/ws         - Android 客户端        ║
║    ws://0.0.0.0:{PORT}/socket.io/ - Web 客户端            ║
║                                                          ║
║  数据流:                                                 ║
║    Web/App -> POST API -> 广播给所有客户端               ║
║    服务器 -> WebSocket -> 推送到 App                     ║
╚══════════════════════════════════════════════════════════╝
    """)

    socketio.run(app, host='0.0.0.0', port=PORT, debug=True, allow_unsafe_werkzeug=True)
