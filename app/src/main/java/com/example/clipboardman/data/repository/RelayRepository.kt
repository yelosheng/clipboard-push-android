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

    fun connect(serverUrl: String, roomId: String) {
        disconnect() // Close existing

        try {
            val opts = IO.Options()
            opts.reconnection = true
            // opts.forceNew = true // Uncomment if needed
            
            Log.d("RelayRepository", "Socket Connecting: $serverUrl")
            DebugLogger.log("RelayRepository", "Socket Connecting to: $serverUrl")
            
            socket = IO.socket(serverUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("RelayRepository", "Connected to Relay")
                DebugLogger.log("RelayRepository", "Socket Connected")
                _connectionStatus.tryEmit(true)
                // Join Room
                val joinData = JSONObject()
                joinData.put("room", roomId)
                socket?.emit("join", joinData)
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
                if (args.isNotEmpty()) {
                    val data = args[0] as JSONObject
                    Log.d("Relay", "Received clipboard_sync: $data")
                    _events.tryEmit(RelayEvent.ClipboardSync(data))
                }
            }
            
            socket?.on("file_sync") { args ->
                if (args.isNotEmpty()) {
                    val data = args[0] as JSONObject
                    Log.d("Relay", "Received file_sync: $data")
                    _events.tryEmit(RelayEvent.FileSync(data))
                }
            }
            
            socket?.connect()
            
        } catch (e: URISyntaxException) {
            Log.e("Relay", "Invalid URL: $serverUrl", e)
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
            socket?.emit("clipboard_sync", payload)
        }
    }
}

sealed class RelayEvent {
    data class ClipboardSync(val data: JSONObject) : RelayEvent()
    data class FileSync(val data: JSONObject) : RelayEvent()
}
