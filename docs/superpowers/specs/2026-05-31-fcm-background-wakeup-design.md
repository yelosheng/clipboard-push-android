# FCM 后台保活 / 双通道送达 设计文档

- 日期: 2026-05-31
- 状态: 已批准设计,待写实现计划
- 涉及仓库:
  - 安卓客户端: `D:\android-dev\clipboard-man` (`com.clipboardpush.plus`)
  - 中继服务器: `D:\APPs\z_pan_python\clipboard_push_server` (Flask + Flask-SocketIO)

## 1. 背景与问题

通过 adb 抓包 + 受控实验确认:Android 客户端一旦切到后台,**ColorOS 15 会在约 15 秒内冻结/限制 app 进程**,即使满足全部标准 Android 豁免(电池优化白名单、standby bucket=5、前台服务在跑、`CB:WakeLock` partial wakelock 持有、cgroup 仍为 `/foreground`)也无法避免。表现为:连接在前台健康(pong 12–25ms),切后台后心跳协程停摆、socket 静默断开,服务器超时踢人,而通知栏可能仍显示绿色(僵尸连接)。

根因不在 app 连接代码、不在服务器、不在网络,而在 ColorOS 的后台管控(很可能是某次系统/优化更新收紧了冻结策略,故"环境没变却突然变严重")。靠 app 自身维持持久 socket 在国产 ROM 上本质脆弱。

**解决方向(用户已选):启用 FCM 推送唤醒(根治),走双通道送达。**

## 2. 目标与非目标

### 目标
- 即使 Android app 被 ColorOS 冻结/杀死,文本剪贴板内容仍能送达(直接经 FCM 高优先级数据消息送到设备并写入剪贴板)。
- 文件/图片场景:用 FCM 唤醒被冻结的 app,使其重连并走原有 LAN/中继下载流程。
- 保持端到端加密:**服务器永不持有房间密钥**。
- FCM 全程可选:服务器未配置凭据时静默回退为 socket-only,自部署者无需 Firebase。

### 非目标
- iOS 客户端(现有 `FcmService` 为 Android 专用)。
- 替换 socket 主通道(socket 仍是主路径,FCM 是并行冗余/唤醒)。
- 修改 AES-256-GCM 加密格式或房间密钥协商。

## 3. 关键设计决策(已拍板)

| 决策点 | 选择 |
|---|---|
| FCM 角色 | **混合**:文本直推内容 + 文件发唤醒信号 |
| 发送时机 | **总是发**(真双通道),与 socket 广播并行;客户端按消息 `id` 去重 |
| token 注册表存储 | **持久化到 SQLite**(独立于 signal_core 的临时字典) |
| 文件路径局限 | **接受**:被唤醒后若发送方也下线且中继无该文件,可能拉不到 |
| 唤醒后重连方式 | `startForegroundService(ClipboardService)`,由服务走正常重连 |
| 加密 | 服务器转发它本就在中继的**密文**,不解密、不持密钥 |

## 4. 架构与数据流

### 4.1 当前 room 模型(约束来源)
- `ROOM_MAX_PEERS = 2`:房间严格两端(通常 PC + 安卓 app)。
- 房间成员是"仅在线"的:`CLIENT_SESSIONS / CLIENT_ROOMS / ROOM_CLIENT_ORDER` 在 socket 连接时写入,**断开时被 `purge_client_tracking()` 整个清除**(`signal_core.py:445`)。
- 推论:被冻结的设备 B 断开后会从房间里被彻底抹掉,`get_room_client_ids(room)` 只剩发送者 A。

**因此 FCM token 注册表必须独立于在线成员关系、在断开后依然存活。**

### 4.2 文本(直推)数据流
```
设备A 加密文本(client 侧) → emit clipboard_push(密文, encrypted=true) → 服务器
  服务器并行做两件事:
    ① 照旧 broadcast clipboard_sync 给房间(在线设备走 socket)
    ② 查 FCM 注册表 room 内"除发送者外"的 token,调 send_fcm_data:
       data = { type=clipboard_push, content=<同一段密文>, encrypted=true,
                id=<消息id>, timestamp=<...> }, android priority=high
设备B(被冻结/被杀) → FCM 高优先级唤醒 FcmService
  → 用房间密钥解密 → MessageRepository.addMessageAtomic(按 id 去重)
  → 复制到剪贴板 → 通知
  全程不需要 B 的 socket 活着。
```

### 4.3 文件/图片(发唤醒)数据流
```
设备A → emit file_available / file_need_relay → 服务器
  ① 照旧广播给房间
  ② 查注册表,给另一端 token 发 FCM: data = { type=wake }
设备B → FCM 唤醒 FcmService → startForegroundService(ClipboardService)
  → ClipboardService 重连 socket、重新 join 房间
  → 走原有 LAN/中继下载流程拉取文件
已知局限(接受):若此时发送方已下线且中继上无该文件,唤醒后可能拉不到。
```

### 4.4 去重(需要新增"共享消息 id"机制)
两通道可能都送达,客户端以消息 `id` 去重(`MessageRepository.addMessageAtomic` 已按 id 原子去重)。**但当前并不满足该前提**,必须新增:

现状(经核实):
- 发送端 `clipboard_push` payload **不带消息 id**(`sendClipboardSync` 只发 room/content/client_id/encrypted)。
- 服务器 `handle_clipboard_push` 把同一 `data` 原样转发为 `clipboard_sync`,**不加 id**(`socket_events.py:253-257`)。
- 接收端 `handleClipboardSync` **自己生成** `messageId = System.currentTimeMillis()`(`ClipboardService.kt:534`)。

→ 若 socket 与 FCM 各自生成 id,两条通道 id 不同,去重失效、产生重复条目。

设计修正(让两通道共享同一 id):
- **服务器 `handle_clipboard_push`**:若 `data` 无 `id`/`timestamp` 则生成(如 `id = f"{ms}_{uuid4().hex[:6]}"`),**注入 `data`**,随后用这份带 id 的 `data` **同时**用于 `clipboard_sync` 广播和 FCM 消息。
- **接收端 `handleClipboardSync`**:优先用 `data.optString("id")`,缺失才回退到自生成(兼容旧服务器)。
- `FcmService` 已读取 `data["id"]`,无需改动。

如此 socket 与 FCM 写入同一 `id`,`addMessageAtomic` 天然去重。

## 5. 组件改动清单

### 5.1 安卓端 (`clipboard-man`)

1. **AndroidManifest.xml**:注册 `FcmService`,加 `com.google.firebase.MESSAGING_EVENT` intent-filter。(现状:未注册 → 收不到任何推送。)
2. **token 上报**:
   - 用 `FirebaseMessaging.getInstance().token` 获取 token,存入既有 `FcmTokenHolder`。
   - 在 `RelayRepository` 的 `join` payload(当前 `RelayRepository.kt:147`)中带上 `fcm_token`、`client_type`。
   - `FcmService.onNewToken` 刷新时:更新 `FcmTokenHolder`,若 socket 已连接则补发 `register_fcm_token` 事件;否则等下次 join 携带。
3. **FcmService 扩展**:
   - 现有 `type=clipboard_push` 文本分支保留(已实现解密/保存/复制/通知)。
   - 新增 `type=wake` 分支:`startForegroundService(Intent(ctx, ClipboardService::class))` 触发重连;不含内容。
4. **`handleClipboardSync` 去重对齐**(`ClipboardService.kt:498`):优先用 `data.optString("id")` 作为 `messageId`,缺失才回退自生成 —— 确保与 FCM 通道共享同一 id(见 §4.4)。
5. 依赖 `com.google.firebase:firebase-messaging` 已在 `app/build.gradle.kts`。
6. **版本号**:按项目规则递增 `versionName`(发布则同时递增 `versionCode`)。

### 5.2 服务器端 (`clipboard_push_server`)

1. **新模块 `app/services/fcm_registry.py`**(SQLite 持久化):
   - 表 `fcm_tokens(room TEXT, client_id TEXT, token TEXT, client_type TEXT, updated_at INTEGER, PRIMARY KEY(room, client_id))`。
   - `register_token(room, client_id, token, client_type)`:upsert。
   - `get_room_tokens(room, exclude_client_id)`:返回该房间除发送者外的 token 列表。
   - `remove_token(token)` / `remove_client(room, client_id)`:清理。
   - 复用服务器既有 SQLite 约定(参考 `history.db` 的初始化方式;`data/` 目录已在 `.gitignore`)。
2. **新 socket handler `register_fcm_token`**(`socket_events.py`):读取 `{room, client_id, fcm_token, client_type}` → `register_token`;同时 `join` handler 里若 payload 含 `fcm_token` 也写入。
3. **`clipboard_push` handler**(`socket_events.py:253`):
   - 先 **id/timestamp 注入**:若 `data` 缺 `id`/`timestamp` 则生成并写回 `data`(见 §4.4),保证两通道共享同一 id。
   - 用这份带 id 的 `data` 做 `clipboard_sync` 广播(现有逻辑)。
   - 再 `tokens = get_room_tokens(room, exclude_client_id=sender)`,对每个 token 调 `fcm_service.send_fcm_data(token, {type:'clipboard_push', content:<同一密文>, encrypted:<原标志>, id, timestamp})`。
4. **文件事件 handler**(`file_available` / `file_need_relay`):给另一端 token 调 `send_fcm_data(token, {type:'wake'})`。
5. **失败清理**:`send_fcm_data` 返回的失败若为 token 失效(`UNREGISTERED` / `SenderIdMismatch` / `InvalidRegistration`)→ 调 `remove_token`。需要 `fcm_service.send_fcm_data` 把这类永久失败与临时失败区分返回(扩展其返回值或抛特定异常)。
6. **依赖与配置**:`requirements.txt` 加 `firebase-admin`;`FIREBASE_CREDENTIALS_PATH` 环境变量;更新 `.env.example`;`fcm_service.py` 已具备 `_ensure_initialized()` 门控逻辑,无需重写。

> 设计约束:token 注册表**独立于 signal_core**,不被 `purge_client_tracking` 清除——这与"别把 FCM token 塞进 signal_core"的既有约定一致(二者生命周期不同:临时 vs 持久)。

### 5.3 运维 / 一次性配置(用户执行)
- Firebase 控制台 → 生成服务账号 JSON → 放服务器、设 `FIREBASE_CREDENTIALS_PATH`。
- 确认 app 内 `google-services.json` 与该 Firebase 项目为同一项目。
- 服务器 `pip install firebase-admin` 并重新部署 systemd 服务。

## 6. 加密不变量

`RelayRepository.sendClipboardSync`(`RelayRepository.kt:390-405`)在 `encrypted=true` 时,`content` 字段已是客户端加密后的 Base64 密文;服务器只中继。`FcmService.onMessageReceived` 已按 `content + encrypted=true` 用房间密钥解密。**服务器把它本就在中继的密文原样塞进 FCM data 即可,不需要、也不会接触房间密钥。** Google/FCM 只见密文。

## 7. 错误处理

- FCM 未配置(无 `FIREBASE_CREDENTIALS_PATH` 或未装 firebase-admin)→ `send_fcm_*` 静默 no-op,socket 通道照常。
- FCM 发送临时失败 → 记日志,不影响 socket 送达。
- FCM 永久失败(token 失效)→ 从注册表删除该 token。
- 客户端房间密钥缺失 → `FcmService` 已有保护(记 warning 并返回)。
- 双通道重复送达 → 客户端按 `id` 去重。

## 8. 测试策略

- **服务器单元测试**:`fcm_registry` 的 upsert/查询/删除;`clipboard_push` 触发逻辑(mock `fcm_service.send_fcm_data`,断言对"除发送者外"的 token 调用且 content 为原密文);永久失败触发 `remove_token`。
- **安卓单元测试**:`FcmService` 消息解析与解密分支(text / wake / 缺密钥)。
- **手动端到端**:设备 B 进入后台被冻结,设备 A 发文本 → B 收到通知且剪贴板更新;A 发文件 → B 被唤醒并完成下载。用 adb logcat(tag `FcmService`/`Relay`/`ClipboardService`)验证。

## 9. Play Store 数据安全声明(必须更新)

启用后:加密内容经由 Google FCM 中转;FCM token 属设备标识符。本次上架**必须更新 Data Safety 表单**,如实申报"传输的数据"(内容为 E2E 加密,Google 仅见密文)及设备标识符用途(App 功能)。

## 10. 开源 / 自部署影响

FCM 由 `FIREBASE_CREDENTIALS_PATH` 门控,默认关闭。自部署者无 Firebase 时为 socket-only(无后台保活),功能不退化为不可用。三仓库文档需补充 FCM 可选说明。

## 11. 已知局限

- 文件路径:被唤醒后若发送方已下线且中继无文件,可能拉不到(已接受)。
- 仅 Android;iOS 不在本设计范围。
- 服务器重启:SQLite 持久化已覆盖(token 不丢)。
