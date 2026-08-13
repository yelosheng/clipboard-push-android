package com.clipboardpush.plus.service

import android.app.Notification
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
import androidx.annotation.RequiresApi
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.clipboardpush.plus.R
import com.clipboardpush.plus.data.model.ConnectionState
import com.clipboardpush.plus.data.model.PushMessage
import com.clipboardpush.plus.data.remote.ApiService
import com.clipboardpush.plus.data.repository.MessageRepository
import com.clipboardpush.plus.data.repository.RelayEvent
import com.clipboardpush.plus.data.repository.RelayRepository
import com.clipboardpush.plus.data.repository.SettingsRepository
import com.clipboardpush.plus.util.FileUtil
import com.clipboardpush.plus.util.NotificationHelper
import com.clipboardpush.plus.util.ReconnectBackoff
import com.clipboardpush.plus.worker.DownloadWorker
import com.clipboardpush.plus.worker.UploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Collections
import java.util.HashSet

class ClipboardService : Service() {

    companion object {
        private const val TAG = "ClipboardService"
        const val ACTION_START = "com.clipboardpush.plus.action.START"
        const val ACTION_STOP = "com.clipboardpush.plus.action.STOP"

        /** 每次重连尝试后留给握手的时间，取值小于 connectRelay() 的 15s 超时。 */
        private const val CONNECT_SETTLE_MS = 12_000L

        /** 常驻通知重画周期，用于刷新「最后确认」时刻。 */
        private const val NOTIFICATION_TICK_MS = 60_000L

        /**
         * 可送达的对端数量 = 真正在线 + 虽离线但能被 FCM 唤醒。由服务更新。
         * 供无绑定的轻量 Activity（QuickPushActivity、ShareReceiverActivity）直接读取，
         * 避免依赖 SharedFlow.replayCache 的过期数据。
         *
         * 用于「现在能不能发送」。**不要**拿它决定通知颜色——亮绿灯必须以真正在线为准，
         * 否则对端明明没上线也会显示绿色。
         */
        @Volatile
        var latestReachablePeerCount: Int = 0
            private set

        /** Tracks concurrent upload count; drives fileUploadActive for AppBar animation. */
        private val _activeUploadCount = java.util.concurrent.atomic.AtomicInteger(0)
        val fileUploadActive = MutableStateFlow(false)

        fun onUploadStarted() {
            _activeUploadCount.incrementAndGet()
            fileUploadActive.value = true
        }

        fun onUploadFinished() {
            if (_activeUploadCount.decrementAndGet() <= 0) {
                _activeUploadCount.set(0)
                fileUploadActive.value = false
            }
        }
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

    /** 真正在线的对端数量。决定通知颜色（绿 vs 黄）与「目标设备」显示。 */
    private var currentPeerCount = 0
    private var currentPeers: List<String> = emptyList()

    /** 可送达数量 = 在线 + FCM 可唤醒。只用于判断「能不能发送」。 */
    private var currentReachablePeerCount = 0

    fun getPeerCount(): Int = currentPeerCount
    fun getPeers(): List<String> = currentPeers

    fun reconnect() {
        userStopped = false
        startJob?.cancel()
        startJob = serviceScope.launch {
            serverAddress = settingsRepository.serverAddressFlow.first()
            useHttps = settingsRepository.useHttpsFlow.first()
            roomId = settingsRepository.roomIdFlow.first()
            val key = settingsRepository.roomKeyFlow.first()
            cryptoManager = if (!key.isNullOrBlank()) {
                try { com.clipboardpush.plus.util.CryptoManager(key) }
                catch (e: Exception) { Log.e(TAG, "CryptoManager init failed", e); null }
            } else {
                Log.w(TAG, "Room key missing after peer switch, encryption disabled")
                null
            }
            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            apiService = ApiService(baseUrl)
            connectRelay()
        }
    }

    private var cryptoManager: com.clipboardpush.plus.util.CryptoManager? = null

    fun sendClipboardText(text: String) {
        serviceScope.launch {
             // Peer guard: 用「可送达」而非「在线」——对端可能 socket 已断但仍可被 FCM 唤醒，
             // 那种情况照样该发，否则正好掐死冻结场景。
             if (currentReachablePeerCount <= 0) {
                 return@launch
             }
             roomId?.let { id ->
                 val manager = cryptoManager
                 if (manager != null) {
                     try {
                         val encryptedBytes = manager.encrypt(text.toByteArray(Charsets.UTF_8))
                         if (encryptedBytes != null) {
                             val encryptedBase64 = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
                             relayRepository.sendClipboardSync(id, encryptedBase64, clientId, true)
                             Log.d(TAG, "Sent encrypted text")
                         } else {
                             Log.e(TAG, "Encryption failed (returned null), aborting send")
                             NotificationHelper.showEncryptionErrorNotification(this@ClipboardService)
                             return@launch
                         }
                     } catch (e: Exception) {
                         Log.e(TAG, "Encryption error, aborting send", e)
                         NotificationHelper.showEncryptionErrorNotification(this@ClipboardService)
                         return@launch
                     }
                 } else {
                     // 未配置密钥（未扫码配对）时允许发送明文
                     Log.w(TAG, "CryptoManager not ready, sending plain text (no key configured)")
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
        // 不崩溃，只记录日志
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private var currentState = ConnectionState.DISCONNECTED
    private var serverAddress = ""
    private var useHttps = false
    private var roomId: String? = null

    // 断线自愈。EVENT_DISCONNECT 会 stopHeartbeat()，而心跳是 reconnectNeeded 的唯一发射源，
    // 所以断线后 reconnect() 是不可达的；socket.io 内部的重连定时器又跑在本进程线程上，
    // 会被厂商 ROM（ColorOS 等）的冻结机制掐断。因此必须有这条独立的重连循环。
    private var autoReconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var notificationTickerJob: Job? = null

    /**
     * 用户主动断开时置位。stopService() 里的 disconnect() 也会发出 connectionStatus=false，
     * 没有这个守卫的话自愈循环会把用户刚断开的连接立刻重连回去。
     */
    @Volatile
    private var userStopped = false

    private val messageHistory = mutableListOf<PushMessage>()
    private val maxMessages = 100

    var onStateChanged: ((ConnectionState) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null
    var onPeersChanged: ((roomId: String?, peers: List<String>) -> Unit)? = null
    var onMessageReceived: ((PushMessage) -> Unit)? = null
    var onMessageDownloadFailed: ((messageId: String) -> Unit)? = null
    var onMessageDownloadProgress: ((messageId: String, progress: Int) -> Unit)? = null

    private val processedMessageIds = Collections.synchronizedSet(HashSet<String>())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        settingsRepository = SettingsRepository(this)
        messageRepository = MessageRepository(this)
        clipboardHelper = ClipboardHelper(this)
         // RelayRepository is now Singleton Object

        loadMessageHistory()
        observeRelayEvents()
        
        LocalFileServer.startServer()
        
        registerNetworkCallback()
    }
    
    private var networkEpoch = 0
    private lateinit var connectivityManager: android.net.ConnectivityManager
    private lateinit var networkCallback: android.net.ConnectivityManager.NetworkCallback
    
    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Wait a bit for IP to settle?
                serviceScope.launch(Dispatchers.IO) {
                    delay(1000)
                    sendNetworkUpdate()
                    // 网络恢复是最可靠的抗冻结唤醒信号：系统为了投递这个回调会强制解冻进程。
                    // 连接不可信时必须借这个时机立刻重连，而不是等退避计时器——它可能正被冻着。
                    // 用 connectionTrustworthy() 而非 currentState，僵尸态下后者是 CONNECTED。
                    if (!roomId.isNullOrBlank() && !connectionTrustworthy()) {
                        Log.w(TAG, "Network available but connection not trustworthy -> immediate reconnect")
                        scheduleAutoReconnect(immediate = true)
                    }
                }
            }
            
            override fun onLost(network: android.net.Network) {
                 serviceScope.launch(Dispatchers.IO) {
                    delay(1000)
                    sendNetworkUpdate()
                }
            }
            
            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
                // Optional: handle strict changes. onAvailable covers most IP changes for our purpose.
            }
        }
        
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }
    
    private suspend fun sendNetworkUpdate() {
        val roomId = settingsRepository.roomIdFlow.first()
        if (!roomId.isNullOrEmpty() && relayRepository.connectionStatus.first()) {
             val info = com.clipboardpush.plus.util.NetworkUtil.getLocalNetworkInfo(applicationContext)
             networkEpoch++
             val ip = info?.ip ?: "0.0.0.0"
             val cidr = info?.cidr ?: "0.0.0.0/0"
             
             relayRepository.sendPeerNetworkUpdate(roomId, ip, cidr, networkEpoch)
        }
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
                    // 断线后必须由这里驱动重连：心跳（reconnectNeeded 的唯一来源）
                    // 已经在 EVENT_DISCONNECT 里被停掉了。
                    if (isConnected) cancelAutoReconnect() else scheduleAutoReconnect()
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
                        refreshNotification()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing peer count", e)
            }
        }

        serviceScope.launch {
            try {
                relayRepository.reachablePeerCount.collect { count ->
                    currentReachablePeerCount = count
                    latestReachablePeerCount = count
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing reachable peer count", e)
            }
        }

        serviceScope.launch {
            try {
                relayRepository.peers.collect { peers ->
                    currentPeers = peers
                    onPeersChanged?.invoke(roomId, peers)
                    if (currentState == ConnectionState.CONNECTED) {
                        refreshNotification()
                    }
                    // Show one-time tip notification when a peer comes online for the first time ever
                    if (peers.isNotEmpty()) {
                        val tipShown = settingsRepository.onboardingNotifTipShownFlow.first()
                        if (!tipShown) {
                            settingsRepository.markOnboardingNotifTipShown()
                            NotificationHelper.showPushTipNotification(this@ClipboardService)
                        }
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
                        is RelayEvent.TransferCommand -> { /* Handled by UploadWorker */ }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing relay event", e)
            }
        }

        serviceScope.launch {
            try {
                relayRepository.reconnectNeeded.collect {
                    Log.w(TAG, "Heartbeat triggered reconnect")
                    reconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing reconnect needed", e)
            }
        }

        // Listen for clipboard changes to SEND (Upload)
        try {
            clipboardHelper.addPrimaryClipChangedListener(object : ClipboardHelper.OnPrimaryClipChangedListener {
                override fun onPrimaryClipChanged() {
                    // Reserved for future use
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error adding clipboard listener", e)
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        // 这里**不能**调 stopService()：它会取消看门狗闹钟，而看门狗的全部意义恰恰是
        // 在服务被系统回收之后还能把它重新拉起来。只有用户主动停止（ACTION_STOP）或
        // 被服务器踢出房间时才该取消闹钟——那两条路径仍然走 stopService()。
        releaseWakeLocks()
        stopNotificationTicker()
        cancelAutoReconnect()
        relayRepository.disconnect()
        serviceScope.cancel()
        LocalFileServer.stopServer()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
        super.onDestroy()
    }

    private var startJob: Job? = null
    private var connectionTimeoutJob: Job? = null

    private fun startService() {
        userStopped = false

        // Cancel previous start attempt if running
        startJob?.cancel()
        
        startJob = serviceScope.launch {
            val newServerAddress = settingsRepository.serverAddressFlow.first()
            val newUseHttps = settingsRepository.useHttpsFlow.first()
            val newRoomId = settingsRepository.roomIdFlow.first()
            val newRoomKey = settingsRepository.roomKeyFlow.first()
            
            // Check active state safely
            if (isActive.not()) return@launch

            // Debounce: 配置没变且连接确实还活着，就什么都不做。
            // 注意必须带 isLivenessStale() 判断：僵尸连接下 currentState 恰好停在 CONNECTED，
            // 只看状态的话，看门狗/FCM/切前台发来的 ACTION_START 会被这里全部吃掉，
            // 于是永远没人重连——这正是"通知显示已连接、服务器上却查无此机"的成因。
            if (newServerAddress == serverAddress &&
                newRoomId == roomId &&
                newUseHttps == useHttps &&
                (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) &&
                !relayRepository.isLivenessStale()) {
                return@launch
            }
            
            // Assign new values
            serverAddress = newServerAddress
            useHttps = newUseHttps
            roomId = newRoomId
            

            if (serverAddress.isBlank() || roomId.isNullOrBlank()) {
                Log.e(TAG, "Missing config")
                updateState(ConnectionState.ERROR)
                return@launch
            }

            // Initialize CryptoManager
            if (!newRoomKey.isNullOrBlank()) {
                try {
                    cryptoManager = com.clipboardpush.plus.util.CryptoManager(newRoomKey)
                    Log.d(TAG, "CryptoManager initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init CryptoManager", e)
                }
            } else {
                Log.w(TAG, "Room Key missing, encryption disabled")
                cryptoManager = null
            }
            

            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            apiService = ApiService(baseUrl)

            val notification = NotificationHelper.buildServiceNotification(
                this@ClipboardService,
                ConnectionState.CONNECTING,
                serverAddress,
                currentReachablePeerCount,
                currentPeers
            )

            if (!startForegroundSafely(notification)) return@launch

            acquireWakeLocks()
            ConnectionWatchdog.schedule(this@ClipboardService)
            startNotificationTicker()
            connectRelay()
        }
    }

    /**
     * 进入前台。**必须捕获异常**——`startForeground()` 会被系统拒绝的场景不止一种
     * （前台服务类型配额耗尽、后台启动限制等），而未捕获的
     * [android.app.ForegroundServiceStartNotAllowedException] 会直接把进程崩掉。
     * 偏偏调用方常常是 FCM 唤醒或看门狗闹钟这类后台路径，崩在那里用户根本看不见，
     * 只会觉得"又莫名其妙断了"。
     *
     * 失败时必须 [stopSelf]：本服务是被 `startForegroundService()` 拉起来的，
     * 若 5 秒内既没进前台也没停掉，系统会抛 ForegroundServiceDidNotStartInTimeException。
     *
     * @return true 表示确实进入前台，调用方可以继续；false 表示已被系统拒绝并已自行停止。
     */
    private fun startForegroundSafely(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.getServiceNotificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NotificationHelper.getServiceNotificationId(), notification)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground was rejected by the system; giving up this attempt", e)
        updateState(ConnectionState.ERROR)
        stopSelf()
        false
    }

    /**
     * 前台服务超时回调（Android 15 新增，Android 16 起改用带类型的重载）。
     *
     * `connectedDevice` 没有运行时长上限，正常永远不会走到这里；留作兜底——万一
     * 类型被改回带配额的类型、或系统将来给更多类型加上限制，**收到回调后必须在
     * 几秒内把服务停掉**，否则系统抛 ForegroundServiceDidNotStopInTimeException 崩溃。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "FGS timeout (startId=$startId) — stopping before the system force-crashes us")
        stopForTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout (startId=$startId, type=$fgsType) — stopping before the system force-crashes us")
        stopForTimeout()
    }

    /**
     * 超时专用的收尾，**刻意不同于** [stopService]：不置 `userStopped`，也不取消看门狗闹钟。
     * 超时是系统强制的，不是用户的意思，所以要留着闹钟——配额恢复后它还得把服务拉回来。
     */
    private fun stopForTimeout() {
        cancelAutoReconnect()
        stopNotificationTicker()
        releaseWakeLocks()
        relayRepository.disconnect()
        updateState(ConnectionState.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopService() {
        userStopped = true
        cancelAutoReconnect()
        stopNotificationTicker()
        ConnectionWatchdog.cancel(this)
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
            
            // Get Client ID (Device ID) - must be stable & unique for self-filtering
            if (clientId.isEmpty()) {
                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android_unknown"
                clientId = "android_$deviceId"
            }
            
            Log.d(TAG, "Connecting Relay: $wsUrl Room: $id Client: $clientId")
            relayRepository.networkEpoch = networkEpoch
            relayRepository.connect(this, wsUrl, id, clientId)

            // 15 秒内未连接成功则自动切换为 ERROR
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = serviceScope.launch {
                delay(15_000)
                if (currentState == ConnectionState.CONNECTING) {
                    Log.w(TAG, "Connection timed out after 15s")
                    updateState(ConnectionState.ERROR)
                }
            }
        }
    }

    /**
     * 断线后持续重连，直到连上或房间配置被清空。
     *
     * 每轮先退避等待、再重连，然后留一段 [CONNECT_SETTLE_MS] 让本次握手跑完，
     * 避免在 socket 还在连的时候就推倒重来。
     *
     * @param immediate 网络刚恢复这类"现在正是好时机"的场景，跳过首次退避并重置计数。
     */
    /**
     * 连接是否可信。**不能只看 [currentState]**：僵尸连接下它恰好停在 CONNECTED，
     * 而服务器早已把会话踢掉，必须再用存活时间戳交叉验证。
     */
    private fun connectionTrustworthy(): Boolean =
        currentState == ConnectionState.CONNECTED && !relayRepository.isLivenessStale()

    private fun scheduleAutoReconnect(immediate: Boolean = false) {
        if (userStopped) return
        if (!immediate && autoReconnectJob?.isActive == true) return
        autoReconnectJob?.cancel()
        if (immediate) reconnectAttempt = 0

        autoReconnectJob = serviceScope.launch {
            while (isActive && !connectionTrustworthy()) {
                if (roomId.isNullOrBlank() || serverAddress.isBlank()) {
                    Log.d(TAG, "Auto-reconnect: no room/server configured, stopping")
                    break
                }

                val waitMs = if (immediate && reconnectAttempt == 0) {
                    0L
                } else {
                    ReconnectBackoff.delayForAttempt(reconnectAttempt)
                }
                if (waitMs > 0) delay(waitMs)
                if (!isActive || connectionTrustworthy()) break

                reconnectAttempt++
                Log.w(TAG, "Auto-reconnect attempt #$reconnectAttempt (waited ${waitMs}ms)")
                connectRelay()

                delay(CONNECT_SETTLE_MS)
            }
            Log.d(TAG, "Auto-reconnect loop exited (state=$currentState)")
        }
    }

    private fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        reconnectAttempt = 0
    }

    private var clientId = ""

    private fun handleClipboardSync(data: JSONObject) {
        var content = data.optString("content")
        val timestamp = data.optString("timestamp")
        val source = data.optString("source")
        val isEncrypted = data.optBoolean("encrypted", false)


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
            // Prefer the server-assigned id so the socket and FCM channels dedup
            // as one message (FcmService uses the same data["id"]). Fall back to a
            // deterministic id for older servers that don't inject one.
            val messageId = data.optString("id").ifEmpty {
                if (!timestamp.isNullOrEmpty()) {
                    "text_${timestamp}_${content.hashCode()}"
                } else {
                    System.currentTimeMillis().toString()
                }
            }
            
            // Save & Notify
            val msg = PushMessage(
                id = messageId,
                type = PushMessage.TYPE_TEXT,
                content = content,
                timestamp = timestamp.ifEmpty { System.currentTimeMillis().toString() }
            )
            saveAndNotifyMessage(msg)
            
            // Write to Clipboard
            clipboardHelper.copyText(content)
            
            // 发送系统通知
            val previewText = if (content.length > 50) content.take(50) + "..." else content
            NotificationHelper.showPushNotification(
                this,
                getString(R.string.type_text),
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
        
        
        if (downloadUrl.isNotEmpty()) {
            val messageId: String
            
            // Check if we are already handling this file (e.g. via file_available fallback)
            val existingId = pendingFiles[fileName]
            if (existingId != null) {
                messageId = existingId
                // No need to create new message or notify, just start worker to update it
                
                // Optional: Update status to "Downloading from Cloud..." via notification?
                NotificationHelper.showPushNotification(
                    this,
                    getString(R.string.notif_downloading),
                    fileName,
                    messageId.hashCode()
                )
            } else {
                messageId = System.currentTimeMillis().toString()
                val isImage = mimeType == "image" || com.clipboardpush.plus.util.FileUtil.isImageFileName(fileName)
                 val msg = PushMessage(
                    id = messageId,
                    type = if (isImage) PushMessage.TYPE_IMAGE else PushMessage.TYPE_FILE,
                    content = fileName,
                    timestamp = data.optString("timestamp"),
                    fileUrl = downloadUrl,
                    fileName = fileName,
                    fileSize = (data.optLong("file_size").takeIf { it > 0 } ?: data.optLong("size").takeIf { it > 0 })
                )
                saveAndNotifyMessage(msg)

                // 发送系统通知
                val typeTitle = if (isImage) getString(R.string.type_image) else getString(R.string.type_file)
                NotificationHelper.showPushNotification(
                    this,
                    typeTitle,
                    fileName,
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

            // 观察云端下载结果，失败时通知 UI
            val capturedMessageId = messageId
            val capturedFileName = fileName
            val workId = workRequest.id
            serviceScope.launch {
                WorkManager.getInstance(applicationContext)
                    .getWorkInfoByIdFlow(workId)
                    .collect { workInfo ->
                        when {
                            workInfo?.state == androidx.work.WorkInfo.State.RUNNING -> {
                                val pct = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, -1)
                                if (pct >= 0) onMessageDownloadProgress?.invoke(capturedMessageId, pct)
                            }
                            workInfo?.state == androidx.work.WorkInfo.State.FAILED -> {
                                Log.w(TAG, "Cloud download failed for message $capturedMessageId")
                                pendingFiles.remove(capturedFileName)
                                onMessageDownloadFailed?.invoke(capturedMessageId)
                                this.cancel()
                            }
                            workInfo?.state?.isFinished == true -> {
                                pendingFiles.remove(capturedFileName)
                                this.cancel()
                            }
                        }
                    }
            }
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
        
        
        if (fileId.isEmpty() || localUrl.isEmpty()) {
             Log.e(TAG, "Invalid file_available payload (missing ID or URL): $dataObj")
             return
        }
        
        // Deduplication: Check if we are already handling this file
        if (pendingFiles.containsKey(fileName)) {
            return
        }
        
        val messageId = System.currentTimeMillis().toString()
        val isImage = mimeType == "image" || com.clipboardpush.plus.util.FileUtil.isImageFileName(fileName)
        val msg = PushMessage(
            id = messageId,
            type = if (isImage) PushMessage.TYPE_IMAGE else PushMessage.TYPE_FILE,
            content = fileName,
            timestamp = System.currentTimeMillis().toString(),
            fileUrl = localUrl, // Temporary, will update
            fileName = fileName,
            fileSize = (dataObj.optLong("size").takeIf { it > 0 } ?: dataObj.optLong("file_size").takeIf { it > 0 })
        )
        // We save message now so user sees something is happening
        saveAndNotifyMessage(msg)

        // Track pending file
        pendingFiles[fileName] = messageId

        NotificationHelper.showPushNotification(
            this,
            getString(if (isImage) R.string.type_image else R.string.type_file),
            fileName,
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
                            val outTransferId = workInfo.outputData.getString(DownloadWorker.KEY_TRANSFER_ID) ?: transferId
                            relayRepository.sendFileSyncCompleted(roomId ?: "", fileId, outTransferId)
                            pendingFiles.remove(fileName)
                            this.cancel()
                        }
                        androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> {
                            relayRepository.sendFileNeedRelay(roomId ?: "", fileId, transferId, "worker_failed")
                            pendingFiles.remove(fileName)
                            onMessageDownloadFailed?.invoke(messageId)
                            this.cancel()
                        }
                        else -> {
                            if (workInfo?.state == androidx.work.WorkInfo.State.RUNNING) {
                                val pct = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, -1)
                                if (pct >= 0) onMessageDownloadProgress?.invoke(messageId, pct)
                            }
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
            }
        }
        onMessageReceived?.invoke(message)
    }

    fun retryFileDownload(message: PushMessage) {
        val fileUrl = message.fileUrl ?: return
        val fileName = message.fileName ?: "file"
        val mimeType = if (message.type == PushMessage.TYPE_IMAGE) "image/*" else "application/octet-stream"
        val messageId = message.safeId

        val workData = workDataOf(
            DownloadWorker.KEY_FILE_URL to fileUrl,
            DownloadWorker.KEY_FILE_NAME to fileName,
            DownloadWorker.KEY_MIME_TYPE to mimeType,
            DownloadWorker.KEY_IS_ENCRYPTED to true,
            DownloadWorker.KEY_MESSAGE_ID to messageId
        )
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workData)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(workRequest)

        val workId = workRequest.id
        serviceScope.launch {
            WorkManager.getInstance(applicationContext)
                .getWorkInfoByIdFlow(workId)
                .collect { workInfo ->
                    when {
                        workInfo?.state == androidx.work.WorkInfo.State.RUNNING -> {
                            val pct = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, -1)
                            if (pct >= 0) onMessageDownloadProgress?.invoke(messageId, pct)
                        }
                        workInfo?.state == androidx.work.WorkInfo.State.FAILED -> {
                            Log.w(TAG, "Retry download failed for message $messageId")
                            onMessageDownloadFailed?.invoke(messageId)
                            this.cancel()
                        }
                        workInfo?.state?.isFinished == true -> {
                            this.cancel()
                        }
                    }
                }
        }
    }

    /**
     * 用当前状态重画常驻通知。
     *
     * 时间戳取自 [RelayRepository.lastProofOfLifeAtMs]——通知上那句「最后确认 HH:mm」
     * 就来自这里。颜色在进程被冻结后无法自我修正，但这个时刻是既成事实，永远不会变成谎话。
     */
    private fun refreshNotification() {
        NotificationHelper.updateServiceNotification(
            this,
            currentState,
            serverAddress,
            currentReachablePeerCount,
            currentPeers,
            relayRepository.lastProofOfLifeAtMs
        )
    }

    /**
     * 连接正常时每分钟重画一次通知，让「最后确认」跟上心跳。
     *
     * 少了这个，时间戳会停在最后一次状态变化的时刻——那反而更误导人：连接明明好好的，
     * 却显示成几小时前确认的。
     */
    private fun startNotificationTicker() {
        if (notificationTickerJob?.isActive == true) return
        notificationTickerJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_TICK_MS)
                if (currentState == ConnectionState.CONNECTED) refreshNotification()
            }
        }
    }

    private fun stopNotificationTicker() {
        notificationTickerJob?.cancel()
        notificationTickerJob = null
    }

    private fun updateState(state: ConnectionState) {
        Log.d(TAG, "State: $state")
        currentState = state
        // 一旦离开 CONNECTING 状态，取消超时计时器
        if (state != ConnectionState.CONNECTING) {
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
        }
        refreshNotification()
        onStateChanged?.invoke(state)
    }

    // --- V4 Probe Handling ---

    private fun handleLanProbeRequest(data: JSONObject) {
        val probeUrl = data.optString("probe_url")
        val probeId = data.optString("probe_id")
        
        
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
                } else {
                    status = "fail"
                    reason = "http_$httpStatus"
                }
            } catch (e: Exception) {
                status = "fail" // or timeout
                reason = e.message ?: "unknown"
            }
            
            relayRepository.sendProbeResult(roomId ?: "", probeId, status, latency, httpStatus, reason)
        }
    }
    
    private fun handlePeerEvicted(data: JSONObject) {
        val reason = data.optString("reason", "unknown")
        Log.w(TAG, "Peer Evicted: $reason")
        
        // 0. Clear Pairing Info (Suspend)
        serviceScope.launch {
            try {
                settingsRepository.clearPairingInfo()
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
            getString(R.string.notif_evicted_title),
            getString(R.string.notif_evicted_body, reason)
        )

        // 4. Toast (Main Thread)
        serviceScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                applicationContext,
                applicationContext.getString(R.string.toast_evicted_from_room, reason),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleRoomStateChanged(data: JSONObject) {
        val state = data.optString("state")
        val lanConf = data.optString("lan_confidence")
        
        // Peer count and list are updated via RelayRepository flows in observeRelayEvents()
        // Notification is updated automatically when peerCount/peers flows emit
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
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CB:WiFiLock").apply { 
                    setReferenceCounted(false)
                    acquire() 
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
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
