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

    fun connect(serverUrl: String, roomId: String, clientId: String) {
        disconnect() // Close existing

        try {
            val opts = IO.Options()
            opts.reconnection = true
            opts.reconnectionAttempts = Int.MAX_VALUE // 无限重试
            opts.reconnectionDelay = 1000
            opts.reconnectionDelayMax = 5000
            opts.timeout = 20000
            // opts.forceNew = true // Uncomment if needed
            
            Log.d("RelayRepository", "Socket Connecting: $serverUrl")
            DebugLogger.log("RelayRepository", "Socket Connecting to: $serverUrl")
            
            socket = IO.socket(serverUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("RelayRepository", "Connected to Relay")
                DebugLogger.log("RelayRepository", "Socket Connected")
                _connectionStatus.tryEmit(true)
                // Join Room
                try {
                    val joinData = JSONObject()
                    joinData.put("room", roomId)
                    
                    // Add client_id if available (Pass it via connect or get globally? For now hardcode logic here or change connect signature)
                    // Changing signature might break other calls. Let's retrieve it from a helper or just leave it for now?
                    // Better to fix it properly. But RelayRepository doesn't have Context to get ANDROID_ID easily without dependency injection.
                    // For now, let's leave IO socket join as is since IO socket broadcasts naturally exclude self.
                    // The focus was cleaning up HTTP echo.
                    
                    // Actually, if we want the server to track this socket as "android_xxx", we MUST send it.
                    // Let's rely on the caller to pass it? 
                    // Let's modify connect to take clientId.
                    joinData.put("client_id", clientId) // We need to add clientId to connect() param first
                    
                    socket?.emit("join", joinData)
                } catch (e: Exception) {
                    Log.e("RelayRepository", "Failed to join room", e)
                }
            }?.on(Socket.EVENT_DISCONNECT) {
                Log.d("RelayRepository", "Disconnected from Relay")
                DebugLogger.log("RelayRepository", "Socket Disconnected")
                _connectionStatus.tryEmit(false)
            }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown"
                Log.e("RelayRepository", "Connection Error: $error")
                DebugLogger.log("RelayRepository", "Socket Error: $error")
                _connectionStatus.tryEmit(false)
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
        _connectionStatus.tryEmit(false)
    }

    fun sendClipboardSync(roomId: String, content: String) {
        if (socket?.connected() == true) {
            val payload = JSONObject().apply {
                put("room", roomId)
                put("content", content)
            }
            // 注意：服务器端监听 clipboard_push，然后广播 clipboard_sync
            socket?.emit("clipboard_push", payload)
            Log.d(TAG, "Sent clipboard_push to room: $roomId")
        } else {
            Log.w(TAG, "Cannot send: socket not connected")
        }
    }
}

sealed class RelayEvent {
    data class ClipboardSync(val data: JSONObject) : RelayEvent()
    data class FileSync(val data: JSONObject) : RelayEvent()
}
