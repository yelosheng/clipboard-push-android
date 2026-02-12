package com.example.clipboardman.data.repository

import android.util.Log
import com.example.clipboardman.util.DebugLogger
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.net.URISyntaxException

class RelayRepository {
    companion object {
        private const val TAG = "Relay"
    }
    
    private var socket: Socket? = null
    
    // Events exposed to Service / UI
    private val _events = MutableSharedFlow<RelayEvent>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<RelayEvent> = _events.asSharedFlow()

    private val _connectionStatus = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStatus = _connectionStatus.asSharedFlow()

    private val _peerCount = MutableSharedFlow<Int>(replay = 1)
    val peerCount = _peerCount.asSharedFlow()

    private val _peers = MutableSharedFlow<List<String>>(replay = 1)
    val peers = _peers.asSharedFlow()

    fun connect(serverUrl: String, roomId: String, clientId: String) {
        disconnect() // Close existing

        try {
            val opts = IO.Options()
            opts.transports = arrayOf("websocket") // Force WebSocket (User confirmed stable)
            opts.reconnection = true
            opts.reconnectionAttempts = Int.MAX_VALUE // 无限重试
            opts.reconnectionDelay = 1000
            opts.reconnectionDelayMax = 5000
            opts.timeout = 10000 // Reduce timeout to fail fast
            
            Log.d("RelayRepository", "Socket Connecting: $serverUrl")
            DebugLogger.log("RelayRepository", "Socket Connecting to: $serverUrl")
            
            socket = IO.socket(serverUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("RelayRepository", "Connected to Relay")
                DebugLogger.log("RelayRepository", "Socket Connected (Ref: ${socket?.id()})")
                _connectionStatus.tryEmit(true)
                // Join Room
                try {
                    val joinData = JSONObject()
                    joinData.put("room", roomId)
                    joinData.put("client_id", clientId) // We need to add clientId to connect() param first
                    joinData.put("client_type", "android")
                    
                    socket?.emit("join", joinData)
                    DebugLogger.log("RelayRepository", "Emitted join room: $roomId")
                } catch (e: Exception) {
                    Log.e("RelayRepository", "Failed to join room", e)
                    DebugLogger.log("RelayRepository", "Failed to join room: ${e.message}")
                }
            }?.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = if (args.isNotEmpty()) args[0].toString() else "Unknown"
                Log.d("RelayRepository", "Disconnected from Relay: $reason")
                DebugLogger.log("RelayRepository", "Socket Disconnected: $reason")
                _connectionStatus.tryEmit(false)
            }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown"
                Log.e("RelayRepository", "Connection Error: $error")
                DebugLogger.log("RelayRepository", "Socket Error: $error")
                _connectionStatus.tryEmit(false)
            }?.on("reconnect_attempt") { args ->
                 val attempt = if (args.isNotEmpty()) args[0].toString() else "?"
                 DebugLogger.log("RelayRepository", "Reconnecting... (Attempt $attempt)")
            }?.on("reconnect") {
                 DebugLogger.log("RelayRepository", "Reconnected!")
            }
            
            socket?.on("clipboard_sync") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                        val data = args[0] as JSONObject
                        Log.d("Relay", "Received clipboard_sync: $data")
                        _events.tryEmit(RelayEvent.ClipboardSync(data))
                    } else {
                        Log.w("Relay", "clipboard_sync: Invalid data format: ${args.getOrNull(0)}")
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing clipboard_sync", e)
                }
            }
            
            socket?.on("file_sync") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                        val data = args[0] as JSONObject
                        Log.d("Relay", "Received file_sync: $data")
                        _events.tryEmit(RelayEvent.FileSync(data))
                    } else {
                        Log.w("Relay", "file_sync: Invalid data format: ${args.getOrNull(0)}")
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing file_sync", e)
                }
            }
            
            socket?.on("room_stats") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                        val data = args[0] as JSONObject
                        val count = data.optInt("count", 0)
                        Log.d("Relay", "Received room_stats: $count")
                        _peerCount.tryEmit(count)
                        
                        // Parse clients list
                        val clientsArray = data.optJSONArray("clients")
                        val clientList = mutableListOf<String>()
                        if (clientsArray != null) {
                            for (i in 0 until clientsArray.length()) {
                                clientList.add(clientsArray.getString(i))
                            }
                        }
                        Log.d("Relay", "Clients: $clientList")
                        DebugLogger.log("Relay", "Clients received: $clientList")
                        _peers.tryEmit(clientList)
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing room_stats", e)
                }
            }
            
            socket?.connect()
            
        } catch (e: URISyntaxException) {
            Log.e("Relay", "Invalid URL: $serverUrl", e)
        } catch (e: Exception) {
            Log.e("Relay", "Connection failed: ${e.message}", e)
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        socket = null
        _connectionStatus.tryEmit(false)
        _peerCount.tryEmit(0)
    }

    fun sendClipboardSync(roomId: String, content: String, clientId: String, isEncrypted: Boolean = false) {
        if (socket?.connected() == true) {
            val payload = JSONObject().apply {
                put("room", roomId)
                put("content", content)
                put("client_id", clientId)
                if (isEncrypted) {
                    put("encrypted", true)
                }
            }
            // 注意：服务器端监听 clipboard_push，然后广播 clipboard_sync
            socket?.emit("clipboard_push", payload)
            Log.d(TAG, "Sent clipboard_push to room: $roomId encrypted=$isEncrypted client=$clientId")
        } else {
            Log.w(TAG, "Cannot send: socket not connected")
        }
    }
}

sealed class RelayEvent {
    data class ClipboardSync(val data: JSONObject) : RelayEvent()
    data class FileSync(val data: JSONObject) : RelayEvent()
}
