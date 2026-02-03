# Clipboard Man Server

跨设备剪贴板同步服务器，支持 WebSocket 实时推送。

## 快速开始

### 安装依赖

```bash
pip install -r requirements.txt
```

### 启动服务器

```bash
python app.py
```

服务器将在 `http://0.0.0.0:9661` 启动。

## API 文档

### 推送文本

```bash
POST /api/push/text
Content-Type: application/json

{
  "content": "要推送的文本内容"
}
```

**示例:**

```bash
curl -X POST http://localhost:9661/api/push/text \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello World"}'
```

### 推送文件

```bash
POST /api/push/file
Content-Type: multipart/form-data

file: [文件]
```

**示例:**

```bash
# 推送图片
curl -X POST http://localhost:9661/api/push/file \
  -F "file=@image.jpg"

# 推送视频
curl -X POST http://localhost:9661/api/push/file \
  -F "file=@video.mp4"
```

### 获取历史消息

```bash
GET /api/messages?limit=50
```

**参数:**
- `limit`: 返回消息数量（默认 50）
- `after_id`: 只返回该 ID 之后的消息

### 下载文件

```bash
GET /files/<filename>
```

## WebSocket

### 连接

```javascript
const socket = io('http://localhost:9661');
```

### 事件

| 事件名 | 方向 | 说明 |
|--------|------|------|
| `connect` | 客户端→服务器 | 建立连接 |
| `connected` | 服务器→客户端 | 连接成功确认 |
| `new_message` | 服务器→客户端 | 收到新消息 |
| `ping` | 客户端→服务器 | 心跳请求 |
| `pong` | 服务器→客户端 | 心跳响应 |

### 消息格式

```json
{
  "id": "uuid-string",
  "type": "text | image | video | audio | file",
  "content": "消息内容或文件描述",
  "file_url": "/files/xxx.jpg",
  "file_name": "original_name.jpg",
  "file_size": 12345,
  "mime_type": "image/jpeg",
  "timestamp": "2026-01-29T16:00:00"
}
```

## 配置

修改 `app.py` 中的常量:

```python
UPLOAD_FOLDER = 'uploads'           # 文件存储目录
MAX_CONTENT_LENGTH = 100 * 1024 * 1024  # 最大文件大小 (100MB)
PORT = 9661                         # 服务器端口
MAX_MESSAGES = 100                  # 历史消息保存数量
```
