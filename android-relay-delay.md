# Android 客户端：PAIR_DIFF_LAN 场景下 download 被延迟 ~10 秒

> 这份文档是给 Android 端 AI 看的，定位并修复一个 ~10 秒延迟问题。
> 不需要 logcat，按下面 §3 的代码搜索就能 80% 概率直接命中。

---

## 1. 问题概述

PC 客户端 push 一个 6.4 MB 文件给 Android 客户端。两端不同 LAN，server 判定 `PAIR_DIFF_LAN`，必须走 relay。

**实际耗时 ~16 秒，其中 ~10 秒是 Android 端的 sleep**——sender 已经把文件 PUT 到 relay 完成、download URL 也已经通过 `file_sync` 事件送到 Android 手里，Android 却还要再等 10 秒才发起 GET。

---

## 2. 服务端真实日志（已经定位到 10s 延迟在 Android 进程内）

PC 时钟已经校准到 NTP（偏移 ±2.5ms），所以 payload 里的时间戳都是干净的"当前时间"。

```
23:26:18.x  RX  file_available（来自 PC sender，含 local_url）
23:26:18.x  TX  file_available 转发给 Android
            ↑ 注意 server 已经主动抹掉了 local_url 字段，Android 收到的版本没有 LAN URL
23:26:18.x  TX  transfer_command(action=upload_relay, reason=room_diff_lan) → 给 sender
23:26:18.x  TX  file_need_relay(reason=room_diff_lan)  → Android 也能在房间广播里收到

23:26:19.0  PC  PUT /api/file/upload/<key>  → 6.4 MB, 78ms 完成
23:26:19.0  PC  POST /api/relay event=file_sync   ← server 转发给 Android
            file_sync.payload = {
              download_url: "https://kxkl.tk:12505/api/file/download/<key>",
              filename:     "...",
              timestamp:    "23:26:19",   ← 当前时间，不是未来时间
              type:         "file"
            }

23:26:29.0  Android GET /api/file/download/<key>   ★★★ 整整晚 10 秒
23:26:34.x  GET 完成（5.07s 下载 6.4 MB ≈ 1.3 MB/s，受手机下行带宽限制）
```

**Android 在 23:26:19 已经掌握了所有信息**：
- ✓ relay 的 download URL（来自 file_sync）
- ✓ 知道必须走 relay（来自 file_need_relay，reason=room_diff_lan）
- ✓ 知道没 LAN URL 可试（file_available 里 local_url 字段被 server 抹掉了）
- ✓ timestamp 是当前时间，不需要等任何"未来时刻"

但 Android 还是 sleep 了 10 秒才发 GET。

---

## 3. 已经在服务端验证排除的假说

| 排查方向 | 验证方法 | 结论 |
|---|---|---|
| PC 时钟漂移 → Android 把 sender 的 timestamp 当 scheduled execution time | `w32tm /resync` 校准到 NTP，偏移降到 ±2.5ms | **不是这个**。timestamp 现在等于 server 接收时刻，但 GET 仍晚 10 秒 |
| Android 状态机要求先收到 file_available 才能用 file_sync 的 URL | server 改成 PAIR_DIFF_LAN 时也转发 file_available（仅抹掉 local_url） | **不是这个**。日志确认 Android 收到了，但 GET 仍晚 10 秒 |

**所以 10 秒延迟在 Android 客户端代码里，独立于 timestamp 字段、独立于 file_available 事件。**

---

## 4. 请你（Android AI）做的事 —— 优先级 1：直接 grep 源码

不需要跑 logcat，不需要起设备。在 Android 项目根目录搜下面的关键字，看哪一处出现在 `file_sync` 事件处理路径上或者 download 路径上：

### 4.1 搜数值常量（最大概率命中）

```
10000
10_000
10000L
"10 * 1000"
"10*1000"
Duration.ofSeconds(10)
TimeUnit.SECONDS, 10
TimeUnit.MILLISECONDS, 10000
TimeUnit.SECONDS.toMillis(10)
```

### 4.2 搜延迟/调度 API

```
postDelayed
Handler(...).postDelayed
delay\(           ← Kotlin 协程
Thread.sleep
ScheduledExecutorService
Timer\(
\.schedule\(
withTimeout
launch.*delay
CountDownLatch.*await
```

### 4.3 搜业务名字

```
lan_timeout
lanTimeout
LAN_SYNC
LanSync
preferLan
lanFirst
LanFirstStrategy
syncDelay
relayDelay
fileSync
onFileSync
handleFileSync
file_sync
```

### 4.4 搜 OkHttp 配置（也许 10s 是某个 timeout 巧合）

```
connectTimeout
readTimeout
callTimeout
writeTimeout
```

把每一处搜到的相关代码（特别是出现在 `file_sync` 事件处理调用链上、或 download 启动前的）贴出来。

---

## 5. 可能的具体场景，每一个都对应一个搜索点

| 怀疑场景 | 搜索什么 | 修法 |
|---|---|---|
| Android 收到 file_sync 后 `Handler.postDelayed { startDownload() }` | `postDelayed` + 10000 | 改成 `post {}` 或直接调用 |
| Android 协程 `delay(10_000)` 后再下载 | `delay(10` | 移除 |
| Android 内部有 `LAN_SYNC_TIMEOUT_MS = 10000`，等待 LAN 失败超时 | `LAN_SYNC` / `lanTimeout` | DIFF_LAN 时跳过 |
| 收到 file_need_relay 后 Android 还在等某个 ack/relay_ready | 搜 `file_need_relay`、`onFileNeedRelay` | 收到 file_need_relay 立即标记"准备走 relay"，看见 file_sync 立刻 download |
| Android 试图根据缓存的 LAN URL 自己拼 URL 去试，等 10 秒 OkHttp connectTimeout 超时 | `connectTimeout` 看是不是 10000 | 看到 file_need_relay 就跳过任何 LAN 尝试 |
| Android 用 `ROOM_LAST_PROBE` 或 peer 的 `network_id_hash` 自己拼 LAN URL 试 | 搜 `network_id_hash`、`private_ip`、`probe_url` | 同上 |

---

## 6. 协议事件流参考

Android 在 `PAIR_DIFF_LAN` 场景下从 server 收到的事件流（按时间顺序）：

```
1. file_available  payload = {
     file_id, filename, size_bytes, sender_client_id,
     transfer_id, sent_at_ms, protocol_version,
     // 注意 local_url 字段被 server 主动抹掉，没有这个 key
   }

2. file_need_relay payload = {
     file_id, transfer_id, reason: "room_diff_lan",
     reported_at_ms, protocol_version
   }
   // 这条事件原本是发给 sender 的兼容信号，但 socketio 房间广播 Android 也会收到。
   // 如果 Android 没监听这个事件，建议加上监听：收到这个事件就标记本次传输强制走 relay，
   // 不再尝试任何 LAN 路径。

3. file_sync       payload = {
     download_url,   // ← 立即用这个 GET，不要等
     filename, room, type, timestamp,
   }
```

---

## 7. 期望的修复

**最干净的修法**：

1. Android 监听 `file_need_relay` 事件。收到这个事件 → 标记 `useRelayOnly = true`，跳过所有 LAN 相关逻辑（不再尝试任何 LAN URL，不再等 LAN 超时）。
2. Android 收到 `file_sync` 事件 → 立即 OkHttp.newCall(download_url) ——不 sleep、不 postDelayed、不预探测。
3. 任何"LAN 优先尝试时长"的设置只在 `useRelayOnly == false` 的路径上生效。

修复后，期望服务端日志看到：

```
T+0      file_available rx + 转发
T+0      transfer_command + file_need_relay tx
T+0.1    PUT 完成（PC 上行很快）
T+0.1    file_sync 转发
T+0.2    Android GET 触发    ← 期望 < 1 秒，不再有 10 秒空等
T+5      GET 完成
INFO:app:Pipeline relay download: <key>  ← server in-memory pipeline 命中标志（额外 bonus）
total: ~5-6 秒（vs 现状 ~16 秒）
```

---

## 8. 如果代码搜索没结果，旁路调试方案（不用 logcat）

如果上面 §4 全 grep 完都看不出问题，可以做这两件事的任一：

### 8.1 写日志到文件（不用 logcat）

在 file_sync handler 加几行：

```kotlin
fun onFileSync(payload: FileSyncPayload) {
    val log = File(context.filesDir, "debug.log")
    log.appendText("\n[${System.currentTimeMillis()}] file_sync received, url=${payload.downloadUrl}")
    // ... 现有逻辑 ...
    log.appendText("\n[${System.currentTimeMillis()}] about to call OkHttpClient.newCall")
    val response = okHttp.newCall(request).execute()
    log.appendText("\n[${System.currentTimeMillis()}] response.code=${response.code}")
}
```

跑完把 `/data/data/<pkg>/files/debug.log` 拷出来，10 秒花在哪两次时间戳之间，立刻可见。

### 8.2 在屏幕上看 Toast 时间戳

```kotlin
Toast.makeText(context, "file_sync rx: ${System.currentTimeMillis() % 100000}", LENGTH_SHORT).show()
// ... 等等
Toast.makeText(context, "GET fired: ${System.currentTimeMillis() % 100000}", LENGTH_SHORT).show()
```

肉眼对比就行，不用任何工具。

---

## 9. TL;DR

1. **server 端没问题**：所有事件都准时送达，payload 时间戳干净。
2. **10 秒延迟在 Android 进程内**——独立于 timestamp、独立于 file_available。
3. **请你**先在源码里搜 §4 列的关键字，特别是 `10000` / `postDelayed` / `delay(` / `lan` 类常量，定位那个 sleep。
4. **修法**：收到 `file_need_relay` 标记 relay-only，收到 `file_sync` 立即 download，不 sleep。
5. 修好以后端到端时间从 ~16 秒降到 ~5-6 秒（受手机下行带宽限制，再快不了）。
