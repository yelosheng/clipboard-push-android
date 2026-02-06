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

    fun reconnect() {
        // Force reload config and reconnect
        startService()
    }

    fun sendClipboardText(text: String) {
        serviceScope.launch {
             roomId?.let { id ->
                 // TODO: Encrypt if E2EE enabled
                 relayRepository.sendClipboardSync(id, text)
             }
        }
    }

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var clipboardHelper: ClipboardHelper
    private lateinit var relayRepository: RelayRepository // Replaces WebSocketClient
    private var apiService: ApiService? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentState = ConnectionState.DISCONNECTED
    private var serverAddress = ""
    private var useHttps = false
    private var roomId: String? = null

    private val messageHistory = mutableListOf<PushMessage>()
    private val maxMessages = 100

    var onStateChanged: ((ConnectionState) -> Unit)? = null
    var onMessageReceived: ((PushMessage) -> Unit)? = null

    private val processedMessageIds = Collections.synchronizedSet(HashSet<String>())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        DebugLogger.log(TAG, "Service created")
        
        settingsRepository = SettingsRepository(this)
        messageRepository = MessageRepository(this)
        clipboardHelper = ClipboardHelper(this)
        relayRepository = RelayRepository() // Init Relay

        loadMessageHistory()
        observeRelayEvents()
    }

    private fun loadMessageHistory() {
        serviceScope.launch {
            messageRepository.messagesFlow.collect { messages ->
                synchronized(messageHistory) {
                    if (messageHistory.isEmpty() && messages.isNotEmpty()) {
                        messageHistory.addAll(messages)
                    }
                }
            }
        }
    }

    // Connect relay status to Service State
    private fun observeRelayEvents() {
        serviceScope.launch {
            relayRepository.connectionStatus.collect { isConnected ->
                updateState(if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
            }
        }

        serviceScope.launch {
            relayRepository.events.collect { event ->
                when (event) {
                    is RelayEvent.ClipboardSync -> handleClipboardSync(event.data)
                    is RelayEvent.FileSync -> handleFileSync(event.data)
                }
            }
        }
        
        // Listen for clipboard changes to SEND (Upload)
        clipboardHelper.addPrimaryClipChangedListener(object : ClipboardHelper.OnPrimaryClipChangedListener {
            override fun onPrimaryClipChanged() {
                // Determine if changed by us (ignore) or external
                // For MVP, just try to send whatever is new
                // TODO: Avoid loops
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        DebugLogger.log(TAG, "Service onDestroy")
        releaseWakeLocks()
        stopService()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startService() {
        DebugLogger.log(TAG, "startService() called")
        serviceScope.launch {
            DebugLogger.log(TAG, "Reading settings...")
            serverAddress = settingsRepository.serverAddressFlow.first()
            useHttps = settingsRepository.useHttpsFlow.first()
            roomId = settingsRepository.roomIdFlow.first()
            
            DebugLogger.log(TAG, "Config loaded: $serverAddress / $roomId")

            if (serverAddress.isBlank() || roomId.isNullOrBlank()) {
                Log.e(TAG, "Missing config")
                DebugLogger.log(TAG, "Missing config - Addr: $serverAddress Room: $roomId")
                updateState(ConnectionState.ERROR)
                return@launch
            }
            
            DebugLogger.log(TAG, "Starting service with Addr: $serverAddress")

            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            apiService = ApiService(baseUrl)

            val notification = NotificationHelper.buildServiceNotification(
                this@ClipboardService,
                ConnectionState.CONNECTING,
                serverAddress
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
            Log.d(TAG, "Connecting Relay: $wsUrl Room: $id")
            DebugLogger.log(TAG, "Connecting to Relay: $wsUrl")
            relayRepository.connect(wsUrl, id)
        }
    }

    private fun handleClipboardSync(data: JSONObject) {
        val content = data.optString("content")
        val timestamp = data.optString("timestamp")
        val source = data.optString("source")
        
        DebugLogger.log(TAG, "Received Clipboard Sync len=${content.length}")
        
        if (content.isNotEmpty()) {
            // Save & Notify
            val msg = PushMessage(
                id = System.currentTimeMillis().toString(),
                type = PushMessage.TYPE_TEXT,
                content = content,
                timestamp = timestamp
            )
            saveAndNotifyMessage(msg)
            
            // Write to Clipboard
            clipboardHelper.copyText(content)
        }
    }

    private fun handleFileSync(data: JSONObject) {
        val downloadUrl = data.optString("download_url")
        val fileName = data.optString("filename")
        val mimeType = data.optString("type") // "image" or "file"
        
        DebugLogger.log(TAG, "Received File Sync: $fileName")
        
        if (downloadUrl.isNotEmpty()) {
             val msg = PushMessage(
                id = System.currentTimeMillis().toString(),
                type = if (mimeType == "image") PushMessage.TYPE_IMAGE else PushMessage.TYPE_FILE,
                content = fileName,
                timestamp = data.optString("timestamp"),
                fileUrl = downloadUrl,
                fileName = fileName
            )
            saveAndNotifyMessage(msg)

            // Start Download Worker
            val workData = workDataOf(
                DownloadWorker.KEY_FILE_URL to downloadUrl,
                DownloadWorker.KEY_FILE_NAME to fileName,
                DownloadWorker.KEY_MIME_TYPE to mimeType,
                DownloadWorker.KEY_IS_ENCRYPTED to true
            )
            
            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workData)
                .build()
                
            WorkManager.getInstance(applicationContext).enqueue(workRequest)
        }
    }

    private fun saveAndNotifyMessage(message: PushMessage) {
        synchronized(messageHistory) {
            messageHistory.add(0, message)
            if (messageHistory.size > maxMessages) messageHistory.removeAt(messageHistory.size - 1)
        }
        serviceScope.launch {
            messageRepository.saveMessages(messageHistory)
        }
        onMessageReceived?.invoke(message)
    }

    private fun updateState(state: ConnectionState) {
        Log.d(TAG, "State: $state")
        DebugLogger.log(TAG, "State changed: $state")
        currentState = state
        NotificationHelper.updateServiceNotification(this, state, serverAddress)
        onStateChanged?.invoke(state)
    }

    // --- WakeLock Helpers ---
    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CB:WakeLock").apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CB:WiFiLock").apply { acquire() }
        }
    }

    private fun releaseWakeLocks() {
        wakeLock?.release(); wakeLock = null
        wifiLock?.release(); wifiLock = null
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
