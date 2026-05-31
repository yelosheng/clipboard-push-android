# FCM 后台保活 / 双通道送达 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **注意:用户偏好 —— 安卓代码不要交给子 agent 写,在主线一步步实现。服务器侧可用子 agent 做。**

**Goal:** 启用 FCM 双通道,使被 ColorOS 冻结/杀死的 Android 客户端仍能收到文本(直推)并被唤醒拉取文件,同时保持 E2E 加密与 FCM 可选。

**Architecture:** 服务器在中继 `clipboard_push` 时,除 socket 广播外,向一张独立持久化(SQLite)的"房间→token"注册表里的其他端发高优先级 FCM data 消息(文本带密文,文件带 wake)。两通道共享服务器分配的消息 `id` 去重。服务器永不持有房间密钥。

**Tech Stack:** 服务器 Python / Flask-SocketIO / firebase-admin / sqlite3 / pytest;客户端 Kotlin / FirebaseMessaging / JUnit+Robolectric。

设计依据:`docs/superpowers/specs/2026-05-31-fcm-background-wakeup-design.md`

---

## 仓库与文件结构

**服务器** `D:\APPs\z_pan_python\clipboard_push_server`
- Create: `app/services/fcm_registry.py` — SQLite token 注册表(镜像 `app/services/history_db.py` 的写法)
- Modify: `app/services/fcm_service.py` — `send_fcm_data` 返回可区分永久失败的结果
- Modify: `app/socket_events.py` — `register_fcm_token` handler、join 读取 token、`clipboard_push` id 注入+FCM 扇出、文件事件 wake
- Modify: `app/__init__.py` — 初始化 token 注册表 DB、把路径注入 socket handlers
- Modify: `requirements.txt`、`.env.example`
- Create: `tests/test_fcm_registry.py`、`tests/test_clipboard_push_fcm.py`

**客户端** `D:\android-dev\clipboard-man`
- Modify: `app/src/main/AndroidManifest.xml` — 注册 `FcmService`
- Modify: `app/src/main/java/com/clipboardpush/plus/data/repository/RelayRepository.kt` — join 带 fcm_token、`register_fcm_token` 补发
- Modify: `app/src/main/java/com/clipboardpush/plus/service/FcmService.kt` — `type=wake` 分支
- Modify: `app/src/main/java/com/clipboardpush/plus/service/ClipboardService.kt` — `handleClipboardSync` 用 `data["id"]`
- Modify: `app/build.gradle.kts` — 版本号递增

> 执行顺序:先 Phase A(服务器,可独立 pytest 验证)→ Phase B(客户端)→ Phase C(配置/联调)。

---

# Phase A — 服务器

### Task A1: FCM token 注册表(SQLite)

**Files:**
- Create: `app/services/fcm_registry.py`
- Test: `tests/test_fcm_registry.py`

参考 `app/services/history_db.py:6-58` 的 `_lock` / `SCHEMA` / `init_db` / `_conn` 写法。

- [ ] **Step 1: 写失败测试**

```python
# tests/test_fcm_registry.py
import os, tempfile
from app.services import fcm_registry

def _db():
    d = tempfile.mkdtemp()
    return os.path.join(d, 'fcm.db')

def test_register_and_get_excludes_sender():
    db = _db(); fcm_registry.init_db(db)
    fcm_registry.register_token(db, room='r1', client_id='A', token='tokA', client_type='app')
    fcm_registry.register_token(db, room='r1', client_id='B', token='tokB', client_type='pc')
    tokens = fcm_registry.get_room_tokens(db, room='r1', exclude_client_id='A')
    assert tokens == ['tokB']

def test_register_is_upsert():
    db = _db(); fcm_registry.init_db(db)
    fcm_registry.register_token(db, room='r1', client_id='A', token='old', client_type='app')
    fcm_registry.register_token(db, room='r1', client_id='A', token='new', client_type='app')
    assert fcm_registry.get_room_tokens(db, room='r1', exclude_client_id='X') == ['new']

def test_remove_token():
    db = _db(); fcm_registry.init_db(db)
    fcm_registry.register_token(db, room='r1', client_id='A', token='tokA', client_type='app')
    fcm_registry.remove_token(db, token='tokA')
    assert fcm_registry.get_room_tokens(db, room='r1', exclude_client_id='X') == []
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /d/APPs/z_pan_python/clipboard_push_server && python -m pytest tests/test_fcm_registry.py -v`
Expected: FAIL — `ModuleNotFoundError` 或 `AttributeError: init_db`(若没装 pytest 先 `pip install pytest`)。

- [ ] **Step 3: 实现 fcm_registry.py**

```python
# app/services/fcm_registry.py
import sqlite3
import threading
import time
from contextlib import contextmanager

_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS fcm_tokens (
    room        TEXT NOT NULL,
    client_id   TEXT NOT NULL,
    token       TEXT NOT NULL,
    client_type TEXT,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (room, client_id)
);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_room ON fcm_tokens(room);
"""


def init_db(db_path: str):
    import os
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    with _lock:
        con = sqlite3.connect(db_path)
        con.executescript(SCHEMA)
        con.commit()
        con.close()


@contextmanager
def _conn(db_path: str):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    finally:
        con.close()


def register_token(db_path: str, room: str, client_id: str, token: str, client_type: str = None):
    if not (db_path and room and client_id and token):
        return
    with _lock, _conn(db_path) as con:
        con.execute(
            "INSERT INTO fcm_tokens (room, client_id, token, client_type, updated_at) "
            "VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(room, client_id) DO UPDATE SET "
            "token=excluded.token, client_type=excluded.client_type, updated_at=excluded.updated_at",
            (room, client_id, token, client_type, int(time.time() * 1000)),
        )


def get_room_tokens(db_path: str, room: str, exclude_client_id: str = None):
    if not (db_path and room):
        return []
    with _lock, _conn(db_path) as con:
        rows = con.execute(
            "SELECT client_id, token FROM fcm_tokens WHERE room = ?", (room,)
        ).fetchall()
    return [r['token'] for r in rows if r['client_id'] != exclude_client_id]


def remove_token(db_path: str, token: str):
    if not (db_path and token):
        return
    with _lock, _conn(db_path) as con:
        con.execute("DELETE FROM fcm_tokens WHERE token = ?", (token,))


def remove_client(db_path: str, room: str, client_id: str):
    if not (db_path and room and client_id):
        return
    with _lock, _conn(db_path) as con:
        con.execute("DELETE FROM fcm_tokens WHERE room = ? AND client_id = ?", (room, client_id))
```

- [ ] **Step 4: 运行测试确认通过**

Run: `python -m pytest tests/test_fcm_registry.py -v`
Expected: PASS（3 passed）

- [ ] **Step 5: 提交**

```bash
git add app/services/fcm_registry.py tests/test_fcm_registry.py
git commit -m "feat(fcm): add persistent SQLite token registry"
```

---

### Task A2: send_fcm_data 区分永久失败

**Files:**
- Modify: `app/services/fcm_service.py:51-71`
- Test: `tests/test_fcm_registry.py`(追加)

目标:token 失效(`UNREGISTERED`/`SenderIdMismatch`/`InvalidArgument` 含 registration token)时,调用方能据此删除 token。做法:`send_fcm_data` 返回字符串状态 `'ok' | 'invalid_token' | 'error' | 'disabled'`,而非 bool。

- [ ] **Step 1: 写失败测试(用 monkeypatch 模拟 messaging)**

```python
# tests/test_fcm_registry.py (追加)
import types
from app.services import fcm_service

def test_send_fcm_data_maps_unregistered_to_invalid_token(monkeypatch):
    monkeypatch.setattr(fcm_service, '_ensure_initialized', lambda: True)
    fake_exc = type('UnregisteredError', (Exception,), {})
    def fake_send(msg):
        raise fake_exc('Requested entity was not found. (UNREGISTERED)')
    fake_messaging = types.SimpleNamespace(
        Message=lambda **kw: kw,
        AndroidConfig=lambda **kw: kw,
        send=fake_send,
    )
    monkeypatch.setitem(__import__('sys').modules, 'firebase_admin.messaging', fake_messaging)
    monkeypatch.setattr(fcm_service, '_import_messaging', lambda: fake_messaging, raising=False)
    assert fcm_service.send_fcm_data('tokX', {'type': 'wake'}) == 'invalid_token'
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_fcm_registry.py::test_send_fcm_data_maps_unregistered_to_invalid_token -v`
Expected: FAIL（返回 bool 或 AttributeError `_import_messaging`）

- [ ] **Step 3: 改 fcm_service.py**

把 `send_fcm_data` 改为返回状态字符串,并抽出 `_import_messaging` 便于测试:

```python
# app/services/fcm_service.py — 替换 send_fcm_data，新增 _import_messaging
_INVALID_TOKEN_MARKERS = ('UNREGISTERED', 'SenderIdMismatch', 'registration-token-not-registered', 'Requested entity was not found')


def _import_messaging():
    from firebase_admin import messaging
    return messaging


def send_fcm_data(token: str, data: dict) -> str:
    """Returns 'ok' | 'invalid_token' | 'error' | 'disabled'."""
    if not _ensure_initialized():
        return 'disabled'
    try:
        messaging = _import_messaging()
        str_data = {k: str(v) for k, v in data.items() if v is not None}
        message = messaging.Message(
            data=str_data,
            token=token,
            android=messaging.AndroidConfig(priority='high'),
        )
        messaging.send(message)
        return 'ok'
    except Exception as e:
        text = str(e)
        if any(m in text for m in _INVALID_TOKEN_MARKERS):
            logger.info(f'FCM token invalid (…{token[-6:]}): will be removed')
            return 'invalid_token'
        logger.warning(f'FCM send failed (token=…{token[-6:]}): {e}')
        return 'error'
```

同时更新 `send_fcm_to_tokens` 里 `if send_fcm_data(token, data):` → `if send_fcm_data(token, data) == 'ok':`。

- [ ] **Step 4: 运行确认通过**

Run: `python -m pytest tests/test_fcm_registry.py -v`
Expected: PASS（全部）

- [ ] **Step 5: 提交**

```bash
git add app/services/fcm_service.py tests/test_fcm_registry.py
git commit -m "feat(fcm): send_fcm_data returns status, detect invalid tokens"
```

---

### Task A3: 应用工厂初始化注册表 DB

**Files:**
- Modify: `app/__init__.py`（参考 `__init__.py:125-127` socketio/bind 区域;以及 history DB 初始化处)
- Modify: `app/settings.py`（加 `FCM_DB_PATH`)

- [ ] **Step 1: settings 加路径**

```python
# app/settings.py — 在 DATA_DIR 定义之后
FCM_DB_PATH = os.path.join(DATA_DIR, 'fcm_tokens.db')
```

- [ ] **Step 2: 工厂初始化并注入**

在 `app/__init__.py` 里(history DB 初始化的相邻位置)加入:

```python
from app.services import fcm_registry
from app.settings import FCM_DB_PATH
fcm_registry.init_db(FCM_DB_PATH)
```

并确保把 `FCM_DB_PATH` 传给注册 socket handlers 的调用(见 Task A4 的 handler 签名)。

- [ ] **Step 3: 冒烟运行**

Run: `python -c "from app import create_app" `（或项目既有的 app 工厂导入方式;确认无导入错误)
Expected: 无异常,`data/fcm_tokens.db` 可被创建。

- [ ] **Step 4: 提交**

```bash
git add app/__init__.py app/settings.py
git commit -m "feat(fcm): init token registry db in app factory"
```

---

### Task A4: register_fcm_token handler + clipboard_push id 注入与 FCM 扇出

**Files:**
- Modify: `app/socket_events.py`（join handler 区 `:84`、clipboard_push handler `:253-258`、handler 注册函数签名 `:46-47`)
- Test: `tests/test_clipboard_push_fcm.py`

> handler 们定义在一个注册函数内(`socket_events.py:47` 附近 `def ...(... ):`)。给该函数新增参数 `fcm_db_path=None`,由 `__init__.py` 传入(Task A3)。

- [ ] **Step 1: 写失败测试(纯逻辑函数,避免起 socket)**

把"扇出决策"抽成可单测的纯函数 `build_clipboard_fcm_payload(data)` 和 `fanout_clipboard_fcm(fcm_db_path, room, sender, data, sender_fn)`。

```python
# tests/test_clipboard_push_fcm.py
import os, tempfile
from app.services import fcm_registry
from app import socket_events as se

def _db():
    p = os.path.join(tempfile.mkdtemp(), 'fcm.db'); fcm_registry.init_db(p); return p

def test_payload_injects_id_and_timestamp():
    data = {'room': 'r1', 'content': 'CIPHER', 'encrypted': True, 'client_id': 'A'}
    out = se.build_clipboard_fcm_payload(data)
    assert out['type'] == 'clipboard_push'
    assert out['content'] == 'CIPHER'
    assert out['encrypted'] == 'true'
    assert out['id'] and out['timestamp']
    # 注入回原 data,供 socket 广播复用同一 id
    assert data['id'] == out['id']

def test_fanout_sends_to_other_peer_only_and_prunes_invalid(monkeypatch):
    db = _db()
    fcm_registry.register_token(db, 'r1', 'A', 'tokA', 'app')
    fcm_registry.register_token(db, 'r1', 'B', 'tokB', 'pc')
    sent = []
    def fake_send(token, payload):
        sent.append(token)
        return 'invalid_token' if token == 'tokB' else 'ok'
    data = {'room': 'r1', 'content': 'C', 'encrypted': True}
    se.fanout_clipboard_fcm(db, room='r1', sender_client_id='A', data=data, send_fn=fake_send)
    assert sent == ['tokB']                       # 只发给非发送者
    assert fcm_registry.get_room_tokens(db, 'r1', 'A') == []  # 失效 token 被清除
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_clipboard_push_fcm.py -v`
Expected: FAIL（`AttributeError: build_clipboard_fcm_payload`）

- [ ] **Step 3: 实现纯函数 + 接进 handler**

在 `socket_events.py` 顶部 import:

```python
import time
from uuid import uuid4
from app.services import fcm_registry
from app.services.fcm_service import send_fcm_data
```

模块级纯函数:

```python
def build_clipboard_fcm_payload(data: dict) -> dict:
    """生成 FCM data;并把 id/timestamp 注入回 data 供 socket 广播复用。"""
    mid = str(data.get('id') or '').strip()
    if not mid:
        mid = f"{int(time.time()*1000)}_{uuid4().hex[:6]}"
        data['id'] = mid
    ts = str(data.get('timestamp') or '').strip()
    if not ts:
        ts = str(int(time.time() * 1000))
        data['timestamp'] = ts
    return {
        'type': 'clipboard_push',
        'content': str(data.get('content', '')),
        'encrypted': 'true' if data.get('encrypted') else 'false',
        'id': mid,
        'timestamp': ts,
    }


def fanout_clipboard_fcm(fcm_db_path, room, sender_client_id, data, send_fn=send_fcm_data):
    if not fcm_db_path or not room:
        return
    payload = build_clipboard_fcm_payload(data)
    for token in fcm_registry.get_room_tokens(fcm_db_path, room, exclude_client_id=sender_client_id):
        if send_fn(token, payload) == 'invalid_token':
            fcm_registry.remove_token(fcm_db_path, token)


def fanout_wake_fcm(fcm_db_path, room, sender_client_id, send_fn=send_fcm_data):
    if not fcm_db_path or not room:
        return
    for token in fcm_registry.get_room_tokens(fcm_db_path, room, exclude_client_id=sender_client_id):
        if send_fn(token, {'type': 'wake'}) == 'invalid_token':
            fcm_registry.remove_token(fcm_db_path, token)
```

改 `clipboard_push` handler(注意:**先注入 id 再广播**,使 socket 与 FCM 同 id):

```python
@socketio.on('clipboard_push')
def handle_clipboard_push(data):
    room = data.get('room')
    if room:
        sender = get_client_from_sid(request.sid)
        # 注入 id/timestamp（写回 data），随后两通道共享
        build_clipboard_fcm_payload(data)
        emit('clipboard_sync', data, room=room, include_self=False)
        logger.info(f"Relayed clipboard data to room: {room}")
        fanout_clipboard_fcm(fcm_db_path, room, sender, data)
        # ...（保留原 activity_log 代码）
```

新增 `register_fcm_token` handler,并在 `join` handler 里读取可选 `fcm_token`:

```python
@socketio.on('register_fcm_token')
def handle_register_fcm_token(data):
    sender = get_client_from_sid(request.sid)
    room = (data or {}).get('room') or CLIENT_ROOMS.get(sender)
    client_id = (data or {}).get('client_id') or sender
    token = (data or {}).get('fcm_token')
    ctype = (data or {}).get('client_type')
    if room and client_id and client_id != 'Unknown' and token:
        fcm_registry.register_token(fcm_db_path, room, client_id, token, ctype)
```

在 `on_join` 内(已拿到 room/client_id/client_type 处)追加:

```python
    fcm_token = (data or {}).get('fcm_token')
    if fcm_token and room and client_id:
        fcm_registry.register_token(fcm_db_path, room, client_id, fcm_token,
                                    CLIENT_TYPES.get(client_id))
```

> `fcm_db_path` 来自注册函数新增的参数(Task A3 注入)。若担心改 handler 签名影响大,可在 `__init__.py` 用闭包/`functools.partial` 传入。

- [ ] **Step 4: 运行确认通过**

Run: `python -m pytest tests/test_clipboard_push_fcm.py tests/test_fcm_registry.py -v`
Expected: PASS（全部）

- [ ] **Step 5: 提交**

```bash
git add app/socket_events.py tests/test_clipboard_push_fcm.py
git commit -m "feat(fcm): register tokens, inject shared id, fan out FCM on clipboard_push"
```

---

### Task A5: 文件事件发 wake

**Files:**
- Modify: `app/socket_events.py`（`file_available` / `file_need_relay` handler 内)

- [ ] **Step 1: 在文件事件 handler 末尾调用 wake 扇出**

在 `file_available`(及别名 `file_announcement`)与 `file_need_relay` 的 handler 中,确定 `room` 与 `sender = get_client_from_sid(request.sid)` 后追加:

```python
    fanout_wake_fcm(fcm_db_path, room, sender)
```

> 不发送任何文件内容,仅 `{'type':'wake'}`。客户端唤醒后走原有下载流程。

- [ ] **Step 2: 冒烟测试(复用 fanout_wake_fcm 单测)**

```python
# tests/test_clipboard_push_fcm.py（追加）
def test_wake_fanout(monkeypatch):
    db = _db()
    fcm_registry.register_token(db, 'r1', 'A', 'tokA', 'app')
    fcm_registry.register_token(db, 'r1', 'B', 'tokB', 'pc')
    sent = []
    se.fanout_wake_fcm(db, 'r1', 'A', send_fn=lambda t, p: sent.append((t, p)) or 'ok')
    assert sent == [('tokB', {'type': 'wake'})]
```

Run: `python -m pytest tests/test_clipboard_push_fcm.py::test_wake_fanout -v`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add app/socket_events.py tests/test_clipboard_push_fcm.py
git commit -m "feat(fcm): send wake push on file events"
```

---

### Task A6: 依赖与环境样例

**Files:**
- Modify: `requirements.txt`、`.env.example`

- [ ] **Step 1: requirements 加 firebase-admin 与 pytest(dev)**

`requirements.txt` 追加:
```
firebase-admin==6.5.0
```
（pytest 作为开发依赖,可单独 `pip install pytest` 或加到 requirements-dev.txt）

- [ ] **Step 2: .env.example 注释 FCM 配置**

```
# FCM (optional). 不设则静默走 socket-only。
# FIREBASE_CREDENTIALS_PATH=/opt/clipboard-push/firebase-credentials.json
```

- [ ] **Step 3: 提交**

```bash
git add requirements.txt .env.example
git commit -m "chore(fcm): add firebase-admin dep and env example"
```

---

# Phase B — 客户端(主线手动实现,勿用子 agent)

### Task B1: 注册 FcmService

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`（在 `:89` ClipboardService `<service>` 之后)

- [ ] **Step 1: 加 service 声明**

```xml
        <!-- FCM 推送(后台保活双通道) -->
        <service
            android:name=".service.FcmService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
```

- [ ] **Step 2: 编译校验**

Run（用户在 Android Studio Terminal,设好 JAVA_HOME 后)：`./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（不实测安装)

- [ ] **Step 3: 提交**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(fcm): register FcmService in manifest"
```

---

### Task B2: 上报 FCM token

**Files:**
- Modify: `app/src/main/java/com/clipboardpush/plus/data/repository/RelayRepository.kt`（join payload 区 `:117-147`)
- Modify: `app/src/main/java/com/clipboardpush/plus/service/FcmService.kt`（`onNewToken`)

- [ ] **Step 1: join payload 带 fcm_token**

在 `RelayRepository.kt` 构造 `joinData` 处(`:136` `device_name` 之后)追加:

```kotlin
                    com.clipboardpush.plus.service.FcmTokenHolder.token?.let {
                        joinData.put("fcm_token", it)
                    }
```

- [ ] **Step 2: 应用启动时主动取 token 存入 holder**

在 `ClipboardManApp.onCreate()`(或 `MainActivity` 启动时)加入:

```kotlin
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { com.clipboardpush.plus.service.FcmTokenHolder.token = it }
```

- [ ] **Step 3: onNewToken 已连接时补发 register_fcm_token**

`FcmService.onNewToken` 已存 holder;若需即时上报,新增一个 RelayRepository 公有方法 `registerFcmToken(roomId, clientId, token)`,内部:

```kotlin
    fun registerFcmToken(roomId: String, clientId: String, token: String) {
        if (socket?.connected() == true) {
            socket?.emit("register_fcm_token", JSONObject().apply {
                put("room", roomId); put("client_id", clientId)
                put("fcm_token", token); put("client_type", "app")
            })
        }
    }
```

并在 token 刷新后(若已知当前 room/client)调用;否则依赖下次 `join` 携带即可。

- [ ] **Step 4: 编译校验**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/clipboardpush/plus/data/repository/RelayRepository.kt \
        app/src/main/java/com/clipboardpush/plus/service/FcmService.kt \
        app/src/main/java/com/clipboardpush/plus/ClipboardManApp.kt
git commit -m "feat(fcm): report fcm token via join and register_fcm_token"
```

---

### Task B3: FcmService 处理 wake

**Files:**
- Modify: `app/src/main/java/com/clipboardpush/plus/service/FcmService.kt`（`onMessageReceived:31-36`)

- [ ] **Step 1: 加 wake 分支**

在 `onMessageReceived` 取得 `type` 后,`clipboard_push` 分支之前加入:

```kotlin
        if (type == "wake") {
            Log.d(TAG, "FCM wake received -> starting ClipboardService")
            val intent = android.content.Intent(applicationContext, ClipboardService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, intent)
            return
        }
```

> `ClipboardService` 启动后会走既有重连逻辑(`acquireWakeLocks()` + `connectRelay()`,见 `ClipboardService.kt:455`)。

- [ ] **Step 2: 编译校验**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/clipboardpush/plus/service/FcmService.kt
git commit -m "feat(fcm): wake message starts ClipboardService to reconnect"
```

---

### Task B4: handleClipboardSync 用共享 id

**Files:**
- Modify: `app/src/main/java/com/clipboardpush/plus/service/ClipboardService.kt`（`handleClipboardSync:498-543`,messageId 生成处 `:534`)

> 该改动是单行取值优先级调整;去重正确性由服务器单测(Task A4)+ 端到端验证(Task C4)覆盖,不单列 Kotlin 单测(对 inline 分支做 Robolectric 测试成本过高且收益低)。

- [ ] **Step 1: 改 messageId 取值优先用 data["id"]**

把生成 `messageId` 的逻辑(当前在 `:534` 附近用 `System.currentTimeMillis()`)改为:

```kotlin
            val messageId = data.optString("id").ifEmpty {
                System.currentTimeMillis().toString()
            }
```

- [ ] **Step 2: 编译校验**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/clipboardpush/plus/service/ClipboardService.kt
git commit -m "fix(fcm): prefer server-assigned id in clipboard_sync for dedup"
```

---

### Task B5: 版本号递增

**Files:**
- Modify: `app/build.gradle.kts:16-17`

- [ ] **Step 1: bump**

```kotlin
        versionCode = 16
        versionName = "1.1.17"
```

- [ ] **Step 2: 提交**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.1.17 (code 16)"
```

---

# Phase C — 配置 / 联调 / 文档(多为用户操作)

### Task C1: Firebase 服务账号(用户在控制台 + 服务器执行)

- [ ] Firebase 控制台 → 项目设置 → 服务账号 → 生成新私钥(JSON)。确认与 app 内 `google-services.json` 同一项目。
- [ ] 上传到服务器(如 `/opt/clipboard-push/firebase-credentials.json`,确保不入 git;`.gitignore` 已含 `firebase-credentials.json`)。
- [ ] systemd `.env` 设 `FIREBASE_CREDENTIALS_PATH=/opt/clipboard-push/firebase-credentials.json`。
- [ ] `pip install -r requirements.txt`(装 firebase-admin),重启服务。
- [ ] 验证启动日志出现 `Firebase Admin SDK initialized (FCM enabled)`。

### Task C2: Play Store 数据安全声明(用户)

- [ ] Play Console → 数据安全表单:申报"消息/其他内容(已加密传输,经第三方 FCM 中转)"与"设备标识符(FCM token,用于 App 功能)"。
- [ ] 与现有 ANDROID_ID 声明并列,不要遗漏。

### Task C3: 文档(可选,三仓库)

- [ ] 服务器 `README.md` / `RELAY_SERVER_API.md`:补 `register_fcm_token` 事件、`clipboard_push` 现含 `id`、FCM 可选说明。
- [ ] Android 仓库 README:补后台保活依赖 FCM 的说明。

### Task C4: 端到端手动验证(用户 + adb)

- [ ] 设备 B 连接后切后台等被冻结(复现先前实验)。
- [ ] 设备 A 发**文本** → B 出现通知且剪贴板更新;adb logcat `-s FcmService:* Relay:*` 看到 `FCM message received` 与解密成功。
- [ ] 设备 A 发**文件** → B 收到 `type=wake`、`ClipboardService` 被拉起重连、完成下载。
- [ ] 确认无重复条目(socket + FCM 同 id 去重生效)。
- [ ] 服务器未配 `FIREBASE_CREDENTIALS_PATH` 时回归测试:功能回退 socket-only,无报错。

---

## 风险与回滚

- 任一 Phase 可独立提交;客户端改动均向后兼容(无 token 则 join 不带、服务器 `fcm_db_path=None` 时扇出为 no-op)。
- 回滚:还原 manifest 的 FcmService 声明即可让客户端回到 dormant 状态;服务器取消 `FIREBASE_CREDENTIALS_PATH` 即停用 FCM。
