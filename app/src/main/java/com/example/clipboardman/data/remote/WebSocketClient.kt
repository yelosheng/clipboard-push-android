package com.example.clipboardman.data.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端
 * 负责与服务器建立持久连接，接收推送消息
 */
class WebSocketClient(
    private val onMessage: (PushMessage) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit
) {
    companion object {
        private const val TAG = "WebSocketClient"
        private const val PING_INTERVAL = 30L // 秒
        private const val MAX_RECONNECT_DELAY = 30_000L // 毫秒
        private const val INITIAL_RECONNECT_DELAY = 1_000L // 毫秒
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 无限读取超时
        .build()

    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var reconnectAttempts = 0
    private var isManualDisconnect = false
    private var isConnected = false

    /**
     * 连接到 WebSocket 服务器
     */
    fun connect(url: String) {
        if (isConnected || webSocket != null) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        serverUrl = url
        isManualDisconnect = false
        reconnectAttempts = 0

        doConnect()
    }

    private fun doConnect() {
        Log.d(TAG, "Connecting to: $serverUrl")
        onStateChange(ConnectionState.CONNECTING)

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                reconnectAttempts = 0
                handler.post { onStateChange(ConnectionState.CONNECTED) }
                
                // 启动心跳
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Log.d(TAG, "Received message: ${text.take(100)}") // 减少日志
                parseAndDispatchMessage(text)
            }
            
            // ... (rest of listener)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                stopHeartbeat() // 停止心跳
                this@WebSocketClient.webSocket = null
                handler.post { onStateChange(ConnectionState.DISCONNECTED) }

                if (!isManualDisconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                stopHeartbeat() // 停止心跳
                this@WebSocketClient.webSocket = null
                handler.post { onStateChange(ConnectionState.ERROR) }

                if (!isManualDisconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        isManualDisconnect = true
        stopHeartbeat() // 停止心跳
        handler.removeCallbacksAndMessages(null)

        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false

        onStateChange(ConnectionState.DISCONNECTED)
    }

    // 心跳相关
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected && webSocket != null) {
                try {
                    // 发送应用层 Ping
                    val pingJson = "{\"type\":\"ping\", \"timestamp\":\"${System.currentTimeMillis()}\"}"
                    webSocket?.send(pingJson)
                    // Log.d(TAG, "Sent heartbeat")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send heartbeat: ${e.message}")
                }
                // 20秒后再发一次
                handler.postDelayed(this, 20000)
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat() // 先清除旧的
        handler.postDelayed(heartbeatRunnable, 20000)
    }

    private fun stopHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable)
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = isConnected

    /**
     * 安排重连
     * 使用指数退避策略
     */
    private fun scheduleReconnect() {
        val delay = calculateReconnectDelay()
        Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt: $reconnectAttempts)")

        handler.postDelayed({
            if (!isManualDisconnect && !isConnected) {
                reconnectAttempts++
                doConnect()
            }
        }, delay)
    }

    /**
     * 计算重连延迟 (指数退避)
     */
    private fun calculateReconnectDelay(): Long {
        val delay = INITIAL_RECONNECT_DELAY * (1 shl minOf(reconnectAttempts, 5))
        return minOf(delay, MAX_RECONNECT_DELAY)
    }

    /**
     * 解析并分发消息
     */
    private fun parseAndDispatchMessage(text: String) {
        try {
            val message = gson.fromJson(text, PushMessage::class.java)

            // 忽略连接确认消息和 Ping 响应
            if (message.isConnectedMessage || message.type == "ping" || message.type == "pong") {
                return
            }

            handler.post { onMessage(message) }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}")
        }
    }

    /**
     * 发送消息到服务器 (可选，用于心跳等)
     */
    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }
}
