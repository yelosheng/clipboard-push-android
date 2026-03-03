package com.clipboardpush.plus.data.repository

import android.util.Log
import com.clipboardpush.plus.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

object RelayRepository {
    private const val TAG = "Relay"
    
    private var socket: Socket? = null
    private var currentClientId: String = ""
    var networkEpoch: Int = 0
    
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

    private val _reconnectNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnectNeeded: SharedFlow<Unit> = _reconnectNeeded.asSharedFlow()

    private val heartbeatScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    @Volatile private var pongReceived = false

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = heartbeatScope.launch {
            while (isActive) {
                delay(15_000)
                if (socket?.connected() != true) break
                pongReceived = false
                socket?.emit("client_ping")
                delay(8_000)
                if (!pongReceived) {
                    Log.w(TAG, "Heartbeat: no pong in 8s, zombie connection detected")
                    _connectionStatus.tryEmit(false)
                    _reconnectNeeded.tryEmit(Unit)
                    break
                }
                Log.d(TAG, "Heartbeat: pong received, connection alive")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun connect(context: android.content.Context, serverUrl: String, roomId: String, clientId: String) {
        disconnect() // Close existing
        currentClientId = clientId

        try {
            val opts = IO.Options()
            opts.forceNew = true // Ensure new connection instance to avoid listener accumulation
            opts.transports = arrayOf("websocket") // Force WebSocket (User confirmed stable)
            opts.reconnection = true
            opts.reconnectionAttempts = Int.MAX_VALUE // 无限重试
            opts.reconnectionDelay = 1000
            opts.reconnectionDelayMax = 5000
            opts.timeout = 10000 // Reduce timeout to fail fast
            
            Log.d("RelayRepository", "Socket Connecting: $serverUrl")
            
            socket = IO.socket(serverUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("RelayRepository", "Connected to Relay")
                _connectionStatus.tryEmit(true)
                startHeartbeat()
                // Join Room with V4 PeerMeta
                try {
                    val networkInfo = com.clipboardpush.plus.util.NetworkUtil.getLocalNetworkInfo(context)
                    
                    val joinData = JSONObject()
                    joinData.put("protocol_version", "4.0")
                    joinData.put("room", roomId)
                    joinData.put("client_id", clientId)
                    joinData.put("client_type", "app")
                    
                    // Friendly device name: prefer user-set name, fallback to manufacturer+model
                    val userDeviceName = try {
                        android.provider.Settings.Global.getString(
                            context.contentResolver, "device_name"
                        )
                    } catch (_: Exception) { null }
                    val friendlyName = if (!userDeviceName.isNullOrBlank()) {
                        userDeviceName
                    } else {
                        val manufacturer = android.os.Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
                        val model = android.os.Build.MODEL ?: "Android"
                        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
                    }
                    joinData.put("device_name", friendlyName)
                    joinData.put("joined_at_ms", System.currentTimeMillis())
                    
                    val netObj = JSONObject()
                    netObj.put("private_ip", networkInfo?.ip ?: "0.0.0.0")
                    netObj.put("cidr", networkInfo?.cidr ?: "0.0.0.0/0")
                    netObj.put("network_epoch", networkEpoch)
                    joinData.put("network", netObj)
                    
                    // App does not have 'probe' section
                    
                    socket?.emit("join", joinData)
                } catch (e: Exception) {
                    Log.e("RelayRepository", "Failed to join room", e)
                }
            }?.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = if (args.isNotEmpty()) args[0].toString() else "Unknown"
                Log.d("RelayRepository", "Disconnected from Relay: $reason")
                stopHeartbeat()
                _connectionStatus.tryEmit(false)
            }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown"
                Log.e("RelayRepository", "Connection Error: $error")
                _connectionStatus.tryEmit(false)
            }?.on("reconnect_attempt") { args ->
                 val attempt = if (args.isNotEmpty()) args[0].toString() else "?"
            }?.on("reconnect") {
            }
            
            socket?.on("server_pong") {
                pongReceived = true
                Log.d(TAG, "server_pong received")
            }

            socket?.on("clipboard_sync") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                        val data = args[0] as JSONObject
                        if (BuildConfig.DEBUG) Log.d("Relay", "Received clipboard_sync: $data")
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
                    if (BuildConfig.DEBUG) Log.d("Relay", "Raw file_sync: ${args.joinToString()}")
                    if (args.isNotEmpty()) {
                        val arg = args[0]
                        val data = when (arg) {
                            is JSONObject -> arg
                            is String -> JSONObject(arg)
                            else -> null
                        }
                        
                        if (data != null) {
                            if (BuildConfig.DEBUG) Log.d("Relay", "Received file_sync: $data")
                            _events.tryEmit(RelayEvent.FileSync(data))
                        } else {
                            Log.w("Relay", "file_sync: Invalid data format: $arg")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing file_sync", e)
                }
            }

            // Handler for file_available (and alias file_announcement)
            val fileAvailableHandler: (Array<Any>) -> Unit = { args ->
                try {
                    if (BuildConfig.DEBUG) Log.d("Relay", "Raw file_available/announcement: ${args.joinToString()}")
                    if (args.isNotEmpty()) {
                         val arg = args[0]
                        val data = when (arg) {
                            is JSONObject -> arg
                            is String -> JSONObject(arg)
                            else -> null
                        }
                        
                        if (data != null) {
                            if (BuildConfig.DEBUG) Log.d("Relay", "Received file_available: $data")
                            _events.tryEmit(RelayEvent.FileAvailable(data))
                        } else {
                             Log.w("Relay", "file_available: Invalid data format: $arg")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing file_available", e)
                }
            }
            
            socket?.on("file_sync_completed") { args ->
                try {
                     if (args.isNotEmpty() && args[0] is JSONObject) {
                         val data = args[0] as JSONObject
                         if (BuildConfig.DEBUG) Log.d("Relay", "Received file_sync_completed: $data")
                         _events.tryEmit(RelayEvent.FileSyncCompleted(data))
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing file_sync_completed", e)
                }
            }
            
            socket?.on("file_need_relay") { args ->
                try {
                     if (args.isNotEmpty() && args[0] is JSONObject) {
                         val data = args[0] as JSONObject
                         if (BuildConfig.DEBUG) Log.d("Relay", "Received file_need_relay: $data")
                         _events.tryEmit(RelayEvent.FileNeedRelay(data))
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing file_need_relay", e)
                }
            }
            
            socket?.on("file_available", fileAvailableHandler)
            socket?.on("file_announcement", fileAvailableHandler) // Dual-listen to be safe
            
            // V4: Probe Request
            socket?.on("lan_probe_request") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                         val data = args[0] as JSONObject
                         if (BuildConfig.DEBUG) Log.d("Relay", "Received lan_probe_request: $data")
                         _events.tryEmit(RelayEvent.LanProbeRequest(data))
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing lan_probe_request", e)
                }
            }

            // V4: Transfer Command
            socket?.on("transfer_command") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                         val data = args[0] as JSONObject
                         if (BuildConfig.DEBUG) Log.d("Relay", "Received transfer_command: $data")
                         _events.tryEmit(RelayEvent.TransferCommand(data))
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing transfer_command", e)
                }
            }

            // V4: Room State - Primary source for peer tracking
            socket?.on("room_state_changed") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                         val data = args[0] as JSONObject
                         
                         // Parse peers array, filter out self
                         val peersArray = data.optJSONArray("peers")
                         val peerNames = mutableListOf<String>()
                         if (peersArray != null) {
                             for (i in 0 until peersArray.length()) {
                                 val peer = peersArray.getJSONObject(i)
                                 val cid = peer.optString("client_id", "")
                                 if (cid.isNotEmpty() && cid != currentClientId) {
                                     // Use device_name if available, otherwise client_id
                                     val displayName = peer.optString("device_name", cid)
                                     peerNames.add(displayName)
                                 }
                             }
                         }
                         
                         _peerCount.tryEmit(peerNames.size)
                         _peers.tryEmit(peerNames)
                         
                         _events.tryEmit(RelayEvent.RoomStateChanged(data))
                    }
                } catch (e: Exception) {
                    Log.e("Relay", "Error processing room_state_changed", e)
                }
            }
            
            // V4: Peer Evicted
            socket?.on("peer_evicted") { args ->
                 try {
                     val data = if (args.isNotEmpty() && args[0] is JSONObject) args[0] as JSONObject else JSONObject()
                     Log.w("Relay", "Received peer_evicted event")
                     isEvicted = true
                     _events.tryEmit(RelayEvent.PeerEvicted(data))
                 } catch (e: Exception) {
                     Log.e("Relay", "Error processing peer_evicted", e)
                 }
            }
            
            socket?.on("room_stats") { args ->
                try {
                    if (args.isNotEmpty() && args[0] is JSONObject) {
                        val data = args[0] as JSONObject
                        
                        // Parse clients list, filtering out self
                        val clientsArray = data.optJSONArray("clients")
                        val clientList = mutableListOf<String>()
                        if (clientsArray != null) {
                            for (i in 0 until clientsArray.length()) {
                                val cid = clientsArray.getString(i)
                                // Filter out self by client_id
                                if (cid != currentClientId) {
                                    clientList.add(cid)
                                }
                            }
                        }
                        _peerCount.tryEmit(clientList.size)
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
        stopHeartbeat()
        socket?.disconnect()
        socket?.off()
        socket = null
        currentClientId = ""
        _connectionStatus.tryEmit(false)
        _peerCount.tryEmit(0)
        _peers.tryEmit(emptyList())
    }

    private var isEvicted = false

    fun sendClipboardSync(roomId: String, content: String, clientId: String, isEncrypted: Boolean = false) {
        if (isEvicted) {
             Log.w(TAG, "Evicted: Cannot send clipboard_sync")
             return
        }
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

    fun sendFileSyncCompleted(roomId: String, fileId: String, transferId: String, method: String = "lan") {
        if (isEvicted) return
        if (socket?.connected() == true) {
            // Strict V4.0 Protocol: Include transfer_id
            val payload = JSONObject().apply {
                put("protocol_version", "4.0")
                put("room", roomId)
                put("file_id", fileId)
                put("transfer_id", transferId)
                put("method", method)
                put("received_at_ms", System.currentTimeMillis())
            }
            socket?.emit("file_sync_completed", payload)
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent file_sync_completed: $payload")
        }
    }

    fun sendFileNeedRelay(roomId: String, fileId: String, transferId: String, reason: String) {
        if (isEvicted) return
        if (socket?.connected() == true) {
             // Strict V4.0 Protocol: Include transfer_id
             val payload = JSONObject().apply {
                put("protocol_version", "4.0")
                put("room", roomId)
                put("file_id", fileId)
                put("transfer_id", transferId)
                put("reason", reason)
                put("reported_at_ms", System.currentTimeMillis())
            }
            socket?.emit("file_need_relay", payload)
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent file_need_relay: $payload")
        }
    }

    fun sendFileAvailable(roomId: String, fileId: String, transferId: String, localUrl: String, filename: String, type: String, size: Long, senderId: String) {
        if (isEvicted) return
        if (socket?.connected() == true) {
            val payload = JSONObject().apply {
                put("protocol_version", "4.0")
                put("room", roomId)
                put("file_id", fileId)
                put("transfer_id", transferId)
                put("filename", filename)
                put("local_url", localUrl)
                put("type", type)
                put("size", size)
                put("sender_id", senderId)
                put("announced_at_ms", System.currentTimeMillis())
            }
            socket?.emit("file_available", payload)
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent file_available: $payload")
        }
    }

    fun sendProbeResult(roomId: String, probeId: String, result: String, latencyMs: Long, httpStatus: Int? = null, reason: String = "") {
        if (isEvicted) return
        if (socket?.connected() == true) {
            val payload = JSONObject().apply {
                put("protocol_version", "4.0")
                put("room", roomId)
                put("probe_id", probeId)
                put("result", result)
                if (result == "ok") {
                    put("latency_ms", latencyMs)
                }
                if (httpStatus != null) {
                    put("http_status", httpStatus)
                }
                if (reason.isNotEmpty()) {
                    put("reason", reason)
                }
                put("reported_at_ms", System.currentTimeMillis())
            }
            socket?.emit("lan_probe_result", payload)
            Log.d(TAG, "Sent lan_probe_result: $result ($latencyMs ms)")
        }
    }
    
    fun sendPeerNetworkUpdate(roomId: String, privateIp: String, cidr: String, networkEpoch: Int) {
        if (isEvicted) return
        if (socket?.connected() == true) {
            val payload = JSONObject().apply {
                put("protocol_version", "4.0")
                put("room", roomId)
                put("private_ip", privateIp)
                put("cidr", cidr)
                put("network_epoch", networkEpoch)
                put("updated_at_ms", System.currentTimeMillis())
            }
            socket?.emit("peer_network_update", payload)
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent peer_network_update: $payload")
        }
    }
}

sealed class RelayEvent {
    data class ClipboardSync(val data: JSONObject) : RelayEvent()
    data class FileSync(val data: JSONObject) : RelayEvent()
    data class FileAvailable(val data: JSONObject) : RelayEvent()
    data class FileSyncCompleted(val data: JSONObject) : RelayEvent()
    data class FileNeedRelay(val data: JSONObject) : RelayEvent()
    data class PeerEvicted(val data: JSONObject) : RelayEvent()
    data class LanProbeRequest(val data: JSONObject) : RelayEvent()
    data class RoomStateChanged(val data: JSONObject) : RelayEvent()
    data class TransferCommand(val data: JSONObject) : RelayEvent()
}
