package com.example.clipboardman.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.data.remote.ApiService
import com.example.clipboardman.data.repository.MessageRepository
import com.example.clipboardman.data.repository.RelayEvent
import com.example.clipboardman.data.repository.RelayRepository
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.util.FileUtil
import com.example.clipboardman.util.NotificationHelper
import com.example.clipboardman.util.DebugLogger
import com.example.clipboardman.worker.DownloadWorker
import com.example.clipboardman.worker.UploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Collections
import java.util.HashSet

class ClipboardService : Service() {

    companion object {
        private const val TAG = "ClipboardService"
        const val ACTION_START = "com.example.clipboardman.action.START"
        const val ACTION_STOP = "com.example.clipboardman.action.STOP"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ClipboardService = this@ClipboardService
    }

    // Public API for Clients
    fun getMessageHistory(): List<PushMessage> {
        return synchronized(messageHistory) {
            ArrayList(messageHistory)
        }
    }

    fun getConnectionState(): ConnectionState {
        return currentState
    }

    private var currentPeerCount = 0
    private var currentPeers: List<String> = emptyList()
    fun getPeerCount(): Int = currentPeerCount
    fun getPeers(): List<String> = currentPeers

    fun reconnect() {
        // Force reload config and reconnect
        startService()
    }

    private var cryptoManager: com.example.clipboardman.util.CryptoManager? = null

    fun sendClipboardText(text: String) {
        serviceScope.launch {
             DebugLogger.log(TAG, "Requesting send clipboard text: '${text.take(50)}...' len=${text.length}")
             roomId?.let { id ->
                 val manager = cryptoManager
                 if (manager != null) {
                     try {
                         val encryptedBytes = manager.encrypt(text.toByteArray(Charsets.UTF_8))
                         if (encryptedBytes != null) {
                             val encryptedBase64 = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
                             relayRepository.sendClipboardSync(id, encryptedBase64, clientId, true)
                             Log.d(TAG, "Sent encrypted text")
                             DebugLogger.log(TAG, "Sent encrypted text to room $id")
                         } else {
                             Log.e(TAG, "Encryption failed, sending plain text")
                             DebugLogger.log(TAG, "Encryption failed!")
                             relayRepository.sendClipboardSync(id, text, clientId, false)
                         }
                     } catch (e: Exception) {
                         Log.e(TAG, "Encryption error", e)
                         DebugLogger.log(TAG, "Encryption error: ${e.message}")
                         relayRepository.sendClipboardSync(id, text, clientId, false)
                     }
                 } else {
                     Log.w(TAG, "CryptoManager not ready, sending plain text")
                     DebugLogger.log(TAG, "CryptoManager null, sending plain text")
                     relayRepository.sendClipboardSync(id, text, clientId, false)
                 }
             }
        }
    }

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var clipboardHelper: ClipboardHelper
    private val relayRepository = RelayRepository // Singleton
    private var apiService: ApiService? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // 异常处理器：捕获协程中的未处理异常，防止崩溃
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine exception caught", throwable)
        DebugLogger.log(TAG, "Coroutine exception: ${throwable.message}")
        // 不崩溃，只记录日志
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private var currentState = ConnectionState.DISCONNECTED
    private var serverAddress = ""
    private var useHttps = false
    private var roomId: String? = null

    private val messageHistory = mutableListOf<PushMessage>()
    private val maxMessages = 100

    var onStateChanged: ((ConnectionState) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null
    var onPeersChanged: ((List<String>) -> Unit)? = null
    var onMessageReceived: ((PushMessage) -> Unit)? = null

    private val processedMessageIds = Collections.synchronizedSet(HashSet<String>())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        DebugLogger.log(TAG, "Service created")
        
        settingsRepository = SettingsRepository(this)
        messageRepository = MessageRepository(this)
        clipboardHelper = ClipboardHelper(this)
         // RelayRepository is now Singleton Object

        loadMessageHistory()
        observeRelayEvents()
        
        LocalFileServer.startServer()
    }

    private fun loadMessageHistory() {
        serviceScope.launch {
            try {
                messageRepository.messagesFlow.collect { messages ->
                    synchronized(messageHistory) {
                        if (messageHistory.isEmpty() && messages.isNotEmpty()) {
                            messageHistory.addAll(messages)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading message history", e)
            }
        }
    }

    // Connect relay status to Service State
    private fun observeRelayEvents() {
        serviceScope.launch {
            try {
                relayRepository.connectionStatus.collect { isConnected ->
                    updateState(if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing connection status", e)
            }
        }

        serviceScope.launch {
            try {
                relayRepository.peerCount.collect { count ->
                    currentPeerCount = count
                    onPeerCountChanged?.invoke(count)
                    // Update notification to reflect peer count change (Yellow -> Green)
                    if (currentState == ConnectionState.CONNECTED) {
                        NotificationHelper.updateServiceNotification(this@ClipboardService, currentState, serverAddress, count, currentPeers)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing peer count", e)
            }
        }

        serviceScope.launch {
            try {
                relayRepository.peers.collect { peers ->
                    currentPeers = peers
                    onPeersChanged?.invoke(peers)
                    if (currentState == ConnectionState.CONNECTED) {
                        NotificationHelper.updateServiceNotification(this@ClipboardService, currentState, serverAddress, currentPeerCount, peers)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing peers", e)
            }
        }

        serviceScope.launch {
            try {
                relayRepository.events.collect { event ->
                    when (event) {
                        is RelayEvent.ClipboardSync -> handleClipboardSync(event.data)
                        is RelayEvent.FileSync -> handleFileSync(event.data)
                        is RelayEvent.FileAvailable -> handleFileAvailable(event.data)
                        is RelayEvent.PeerEvicted -> handlePeerEvicted(event.data)
                        is RelayEvent.LanProbeRequest -> handleLanProbeRequest(event.data)
                        is RelayEvent.RoomStateChanged -> handleRoomStateChanged(event.data)
                        is RelayEvent.FileSyncCompleted -> { /* Handled by UploadWorker */ }
                        is RelayEvent.FileNeedRelay -> { /* Handled by UploadWorker */ }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing relay event", e)
            }
        }
        
        // Listen for clipboard changes to SEND (Upload)
        try {
            clipboardHelper.addPrimaryClipChangedListener(object : ClipboardHelper.OnPrimaryClipChangedListener {
                override fun onPrimaryClipChanged() {
                    // Determine if changed by us (ignore) or external
                    // For MVP, just try to send whatever is new
                    // TODO: Avoid loops
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error adding clipboard listener", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            // Push clipboard now handled by QuickPushActivity
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLogger.log(TAG, "onTrimMemory level=$level")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        DebugLogger.log(TAG, "Service onDestroy")
        releaseWakeLocks()
        stopService()
        serviceScope.cancel()
        LocalFileServer.stopServer()
        super.onDestroy()
    }

    private var startJob: Job? = null

    private fun startService() {
        DebugLogger.log(TAG, "startService() called")
        
        // Cancel previous start attempt if running
        startJob?.cancel()
        
        startJob = serviceScope.launch {
            DebugLogger.log(TAG, "Reading settings...")
            val newServerAddress = settingsRepository.serverAddressFlow.first()
            val newUseHttps = settingsRepository.useHttpsFlow.first()
            val newRoomId = settingsRepository.roomIdFlow.first()
            val newRoomKey = settingsRepository.roomKeyFlow.first()
            
            // Check active state safely
            if (isActive.not()) return@launch

            // Debounce: If config is same and we are connected/connecting, do nothing
            if (newServerAddress == serverAddress && 
                newRoomId == roomId && 
                newUseHttps == useHttps &&
                (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING)) {
                DebugLogger.log(TAG, "Service already running with same config. Ignoring start request.")
                return@launch
            }
            
            // Assign new values
            serverAddress = newServerAddress
            useHttps = newUseHttps
            roomId = newRoomId
            
            DebugLogger.log(TAG, "Config loaded: $serverAddress / $roomId")

            if (serverAddress.isBlank() || roomId.isNullOrBlank()) {
                Log.e(TAG, "Missing config")
                DebugLogger.log(TAG, "Missing config - Addr: $serverAddress Room: $roomId")
                updateState(ConnectionState.ERROR)
                return@launch
            }

            // Initialize CryptoManager
            if (!newRoomKey.isNullOrBlank()) {
                try {
                    cryptoManager = com.example.clipboardman.util.CryptoManager(newRoomKey)
                    Log.d(TAG, "CryptoManager initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init CryptoManager", e)
                }
            } else {
                Log.w(TAG, "Room Key missing, encryption disabled")
                cryptoManager = null
            }
            
            DebugLogger.log(TAG, "Starting service with Addr: $serverAddress")

            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            apiService = ApiService(baseUrl)

            val notification = NotificationHelper.buildServiceNotification(
                this@ClipboardService,
                ConnectionState.CONNECTING,
                serverAddress,
                currentPeerCount,
                currentPeers
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.getServiceNotificationId(),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.getServiceNotificationId(), notification)
            }

            acquireWakeLocks()
            connectRelay()
        }
    }

    private fun stopService() {
        releaseWakeLocks()
        relayRepository.disconnect()
        apiService = null
        updateState(ConnectionState.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun connectRelay() {
        val wsUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps) // Socket.IO uses HTTP base
        roomId?.let { id ->
            val wsUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps) // Socket.IO uses HTTP base
            
            // Get Client ID (Device ID) - make it a property so we can reuse
            if (clientId.isEmpty()) {
                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android_unknown"
                clientId = "android_$deviceId"
            }
            
            Log.d(TAG, "Connecting Relay: $wsUrl Room: $id Client: $clientId")
            DebugLogger.log(TAG, "Connecting to Relay: $wsUrl ($clientId)")
            relayRepository.connect(this, wsUrl, id, clientId)
        }
    }

    private var clientId = ""

    private fun handleClipboardSync(data: JSONObject) {
        var content = data.optString("content")
        val timestamp = data.optString("timestamp")
        val source = data.optString("source")
        val isEncrypted = data.optBoolean("encrypted", false)

        DebugLogger.log(TAG, "Received Clipboard Sync len=${content.length} encrypted=$isEncrypted")

        if (isEncrypted) {
            val manager = cryptoManager
            if (manager != null) {
                try {
                    val encryptedBytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                    val decryptedBytes = manager.decrypt(encryptedBytes)
                    if (decryptedBytes != null) {
                        content = String(decryptedBytes, Charsets.UTF_8)
                        Log.d(TAG, "Decrypted content successfully")
                    } else {
                        Log.e(TAG, "Decryption returned null")
                        return // Decryption failed, ignore message
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decryption error", e)
                    return // Error, ignore
                }
            } else {
                Log.w(TAG, "Received encrypted content but CryptoManager is null")
                return
            }
        }
        
        if (content.isNotEmpty()) {
            // Generate deterministic ID for deduplication
            // If timestamp is available, use it + content hash
            val messageId = if (!timestamp.isNullOrEmpty()) {
                "text_${timestamp}_${content.hashCode()}"
            } else {
                System.currentTimeMillis().toString()
            }
            
            // Save & Notify
            val msg = PushMessage(
                id = messageId,
                type = PushMessage.TYPE_TEXT,
                content = content,
                timestamp = timestamp ?: System.currentTimeMillis().toString()
            )
            saveAndNotifyMessage(msg)
            
            // Write to Clipboard
            clipboardHelper.copyText(content)
            
            // 发送系统通知
            val previewText = if (content.length > 50) content.take(50) + "..." else content
            NotificationHelper.showPushNotification(
                this,
                "收到文本",
                previewText,
                System.currentTimeMillis().toInt()
            )
        }
    }

    private val pendingFiles = Collections.synchronizedMap(java.util.HashMap<String, String>())

    private fun handleFileSync(data: JSONObject) {
        val downloadUrl = data.optString("download_url")
        val fileName = data.optString("filename")
        val mimeType = data.optString("type") // "image" or "file"
        
        DebugLogger.log(TAG, "Received File Sync: $fileName")
        
        if (downloadUrl.isNotEmpty()) {
            val messageId: String
            
            // Check if we are already handling this file (e.g. via file_available fallback)
            val existingId = pendingFiles[fileName]
            if (existingId != null) {
                DebugLogger.log(TAG, "Reusing existing message ID for fallback: $existingId")
                messageId = existingId
                // No need to create new message or notify, just start worker to update it
                
                // Optional: Update status to "Downloading from Cloud..." via notification?
                NotificationHelper.showPushNotification(
                    this,
                    "下载中 (云端)",
                    "正在从云端下载: $fileName",
                    messageId.hashCode()
                )
            } else {
                messageId = System.currentTimeMillis().toString()
                 val msg = PushMessage(
                    id = messageId,
                    type = if (mimeType == "image") PushMessage.TYPE_IMAGE else PushMessage.TYPE_FILE,
                    content = fileName,
                    timestamp = data.optString("timestamp"),
                    fileUrl = downloadUrl,
                    fileName = fileName
                )
                saveAndNotifyMessage(msg)
                
                // 发送系统通知
                val typeLabel = if (mimeType == "image") "图片" else "文件"
                NotificationHelper.showPushNotification(
                    this,
                    "收到$typeLabel",
                    "正在下载: $fileName",
                    messageId.hashCode()
                )
                
                // Add to pending
                pendingFiles[fileName] = messageId
            }

            // Start Download Worker with message ID
            val workData = workDataOf(
                DownloadWorker.KEY_FILE_URL to downloadUrl,
                DownloadWorker.KEY_FILE_NAME to fileName,
                DownloadWorker.KEY_MIME_TYPE to mimeType,
                DownloadWorker.KEY_IS_ENCRYPTED to true,
                DownloadWorker.KEY_MESSAGE_ID to messageId
            )
            
            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workData)
                .build()
                
            WorkManager.getInstance(applicationContext).enqueue(workRequest)
        }
    }

    private fun handleFileAvailable(data: JSONObject) {
        // V3.3 Protocol: Flat Payload. Legacy/Wrapper: Wrapped in "data".
        // We handle both by checking if "data" exists as a child object.
        val dataObj = data.optJSONObject("data") ?: data
        
        val fileId = dataObj.optString("file_id")
        val transferId = dataObj.optString("transfer_id")
        val fileName = dataObj.optString("filename")
        val localUrl = dataObj.optString("local_url")
        val mimeType = dataObj.optString("type")
        
        DebugLogger.log(TAG, "Received File Invite (v2): $fileName ($localUrl) ID=$fileId TR=$transferId")
        
        if (fileId.isEmpty() || localUrl.isEmpty()) {
             Log.e(TAG, "Invalid file_available payload (missing ID or URL): $dataObj")
             return
        }
        
        // Deduplication: Check if we are already handling this file
        if (pendingFiles.containsKey(fileName)) {
            DebugLogger.log(TAG, "Ignoring duplicate file announcement: $fileName")
            return
        }
        
        val messageId = System.currentTimeMillis().toString()
        val msg = PushMessage(
            id = messageId,
            type = if (mimeType == "image") PushMessage.TYPE_IMAGE else PushMessage.TYPE_FILE,
            content = fileName,
            timestamp = System.currentTimeMillis().toString(),
            fileUrl = localUrl, // Temporary, will update
            fileName = fileName
        )
        // We save message now so user sees something is happening
        saveAndNotifyMessage(msg)
        
        // Track pending file
        pendingFiles[fileName] = messageId

        NotificationHelper.showPushNotification(
            this,
            "收到局域网文件",
            "正在高速下载: $fileName",
            messageId.hashCode()
        )

        val workData = workDataOf(
            DownloadWorker.KEY_FILE_URL to localUrl,
            DownloadWorker.KEY_FILE_NAME to fileName,
            DownloadWorker.KEY_MIME_TYPE to mimeType,
            DownloadWorker.KEY_IS_ENCRYPTED to false, // Local is plaintext
            DownloadWorker.KEY_MESSAGE_ID to messageId,
            DownloadWorker.KEY_IS_ANNOUNCE to true,
            DownloadWorker.KEY_TRANSFER_ID to transferId
        )

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workData)
            .build()
            
        val workManager = WorkManager.getInstance(applicationContext)
        workManager.enqueue(workRequest)
        
        // Observe result
        serviceScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            DebugLogger.log(TAG, "v2 Download Sucesss! Sending Ack.")
                            val outTransferId = workInfo.outputData.getString(DownloadWorker.KEY_TRANSFER_ID) ?: transferId
                            relayRepository.sendFileSyncCompleted(roomId ?: "", fileId, outTransferId)
                            // Remove from pendingFiles eventually? 
                            // Keep it for a bit in case PC still sends fallback?
                            // For simplicity, we can leave it or remove it delayed. 
                            // Let's leave it for this session to prevent dupes.
                            this.cancel() 
                        }
                        androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> {
                            DebugLogger.log(TAG, "v2 Download Failed. Requesting Relay.")
                            relayRepository.sendFileNeedRelay(roomId ?: "", fileId, transferId, "worker_failed")
                            this.cancel()
                        }
                        else -> {
                            // Running/Enqueued... wait
                        }
                    }
                }
            }
        }
    }

    private fun saveAndNotifyMessage(message: PushMessage) {
        // 使用原子操作保存，避免竞争条件
        serviceScope.launch {
            try {
                messageRepository.addMessageAtomic(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving message", e)
                DebugLogger.log(TAG, "Save error: ${e.message}")
            }
        }
        onMessageReceived?.invoke(message)
    }

    private fun updateState(state: ConnectionState) {
        Log.d(TAG, "State: $state")
        DebugLogger.log(TAG, "State changed: $state")
        currentState = state
        NotificationHelper.updateServiceNotification(this, state, serverAddress, currentPeerCount, currentPeers)
        onStateChanged?.invoke(state)
    }

    // --- V4 Probe Handling ---

    private fun handleLanProbeRequest(data: JSONObject) {
        val probeUrl = data.optString("probe_url")
        val probeId = data.optString("probe_id")
        
        DebugLogger.log(TAG, "Received Probe Request: $probeUrl (ID: $probeId)")
        
        if (probeUrl.isEmpty() || probeId.isEmpty()) {
            Log.e(TAG, "Invalid probe request")
            return
        }
        
        // Execute Probe in background
        serviceScope.launch(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS) // Short timeout
                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                
            val startTime = System.currentTimeMillis()
            var status = "fail"
            var latency = 0L
            var httpStatus = 0
            var reason = ""
            
            try {
                // Add header to identify self
                val request = okhttp3.Request.Builder()
                    .url(probeUrl)
                    .addHeader("X-Probe-ID", probeId)
                    .build()
                    
                val response = client.newCall(request).execute()
                latency = System.currentTimeMillis() - startTime
                httpStatus = response.code
                response.close()
                
                if (response.isSuccessful) {
                    status = "ok"
                    DebugLogger.log(TAG, "Probe Success: ${latency}ms")
                } else {
                    status = "fail"
                    reason = "http_$httpStatus"
                    DebugLogger.log(TAG, "Probe Failed: HTTP $httpStatus")
                }
            } catch (e: Exception) {
                status = "fail" // or timeout
                reason = e.message ?: "unknown"
                DebugLogger.log(TAG, "Probe Error: ${e.message}")
            }
            
            relayRepository.sendProbeResult(roomId ?: "", probeId, status, latency, httpStatus, reason)
        }
    }
    
    private fun handlePeerEvicted(data: JSONObject) {
        val reason = data.optString("reason", "unknown")
        DebugLogger.log(TAG, "PEER EVICTED! Reason: $reason. Disconnecting...")
        Log.w(TAG, "Peer Evicted: $reason")
        
        // 0. Clear Pairing Info (Suspend)
        serviceScope.launch {
            try {
                settingsRepository.clearPairingInfo()
                DebugLogger.log(TAG, "Pairing info cleared.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear pairing info", e)
            }
        }
        
        // 1. Force Disconnect
        stopService()
        
        // 2. Update State (Explicitly Error to prevent auto-reconnect loops if any)
        updateState(ConnectionState.ERROR)
        
        // 3. Notify User
        NotificationHelper.showPushNotification(
            this,
            "已从房间移除",
            "您已被移出房间 (原因: $reason)。请重新连接。"
        )
        
        // 4. Toast (Main Thread)
        serviceScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(applicationContext, "您已被移出房间! ($reason)", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun handleRoomStateChanged(data: JSONObject) {
        val state = data.optString("state")
        val lanConf = data.optString("lan_confidence")
        DebugLogger.log(TAG, "Room State: $state (Lan: $lanConf)")
        
        // TODO: Update UI or notification icon based on state
    }

    // --- WakeLock Helpers ---
    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CB:WakeLock").apply { 
                    setReferenceCounted(false)
                    acquire() 
                }
                DebugLogger.log(TAG, "WakeLock acquired")
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CB:WiFiLock").apply { 
                    setReferenceCounted(false)
                    acquire() 
                }
                DebugLogger.log(TAG, "WiFiLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
            DebugLogger.log(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLogger.log(TAG, "WakeLock released")
            }
            wakeLock = null
            
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                DebugLogger.log(TAG, "WiFiLock released")
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    // --- Upload Trigger (Called by UI or Intent) ---
    fun uploadFile(uri: Uri, mimeType: String) {
        val workData = workDataOf(
            UploadWorker.KEY_URI_STRING to uri.toString(),
            UploadWorker.KEY_MIME_TYPE to mimeType
        )
        val workRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workData)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(workRequest)
    }
}
