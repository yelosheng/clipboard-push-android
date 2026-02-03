package com.example.clipboardman.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.data.remote.ApiService
import com.example.clipboardman.data.remote.WebSocketClient
import com.example.clipboardman.data.repository.MessageRepository
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.util.FileUtil
import com.example.clipboardman.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 剪贴板前台服务
 * 保持 WebSocket 连接，接收推送消息并写入剪贴板
 */
class ClipboardService : Service() {

    companion object {
        private const val TAG = "ClipboardService"
        const val ACTION_START = "com.example.clipboardman.action.START"
        const val ACTION_STOP = "com.example.clipboardman.action.STOP"
    }

    // Binder 用于 Activity 绑定
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ClipboardService = this@ClipboardService
    }

    // 组件
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var clipboardHelper: ClipboardHelper
    private var webSocketClient: WebSocketClient? = null
    private var apiService: ApiService? = null

    // WakeLock 保持后台运行
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 状态
    private var currentState = ConnectionState.DISCONNECTED
    private var serverAddress = ""
    private var useHttps = false

    // 消息历史（保留最近100条）
    private val messageHistory = mutableListOf<PushMessage>()
    private val maxMessages = 100

    // 状态回调
    var onStateChanged: ((ConnectionState) -> Unit)? = null
    var onMessageReceived: ((PushMessage) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        settingsRepository = SettingsRepository(this)
        messageRepository = MessageRepository(this)
        clipboardHelper = ClipboardHelper(this)

        // 加载历史消息
        loadMessageHistory()
    }

    /**
     * 从本地存储加载消息历史
     */
    private fun loadMessageHistory() {
        serviceScope.launch {
            messageRepository.messagesFlow.collect { messages ->
                synchronized(messageHistory) {
                    if (messageHistory.isEmpty() && messages.isNotEmpty()) {
                        messageHistory.addAll(messages)
                        Log.d(TAG, "Loaded ${messages.size} messages from storage")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        releaseWakeLocks()
        stopService()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 启动服务
     */
    private fun startService() {
        serviceScope.launch {
            // 获取服务器地址和协议设置
            serverAddress = settingsRepository.serverAddressFlow.first()
            useHttps = settingsRepository.useHttpsFlow.first()

            if (serverAddress.isBlank()) {
                Log.e(TAG, "Server address is empty")
                updateState(ConnectionState.ERROR)
                return@launch
            }

            // 初始化 ApiService
            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            apiService = ApiService(baseUrl)

            // 启动前台服务
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

            // 获取 WakeLock 保持后台运行
            acquireWakeLocks()

            // 启动 WebSocket 连接
            connectWebSocket()
        }
    }

    /**
     * 停止服务
     */
    private fun stopService() {
        // 释放 WakeLock
        releaseWakeLocks()

        webSocketClient?.disconnect()
        webSocketClient = null
        apiService = null
        updateState(ConnectionState.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 连接 WebSocket
     */
    private fun connectWebSocket() {
        val wsUrl = settingsRepository.getWebSocketUrl(serverAddress, useHttps)
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        webSocketClient = WebSocketClient(
            onMessage = { message -> handleMessage(message) },
            onStateChange = { state -> updateState(state) }
        )

        webSocketClient?.connect(wsUrl)
    }

    /**
     * 获取 WakeLock 保持后台运行
     */
    private fun acquireWakeLocks() {
        // CPU WakeLock
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ClipboardMan::WebSocketWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WakeLock acquired")
        }

        // WiFi WakeLock
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "ClipboardMan::WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        }
    }

    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null

        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WifiLock released")
            }
        }
        wifiLock = null
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(message: PushMessage) {
        Log.d(TAG, "Handling message: type=${message.type}, content=${message.content?.take(50)}")

        // 保存到内存历史记录
        synchronized(messageHistory) {
            messageHistory.add(0, message)
            if (messageHistory.size > maxMessages) {
                messageHistory.removeAt(messageHistory.size - 1)
            }
        }

        // 持久化到本地存储
        serviceScope.launch {
            val currentMessages = getMessageHistory()
            messageRepository.saveMessages(currentMessages)
        }

        // 通知 UI 更新消息列表
        onMessageReceived?.invoke(message)

        serviceScope.launch {
            when {
                message.isTextType -> handleTextMessage(message)
                message.isFileType -> handleFileMessage(message)
            }
        }
    }

    /**
     * 获取消息历史（供 Activity 同步）
     */
    fun getMessageHistory(): List<PushMessage> {
        synchronized(messageHistory) {
            return messageHistory.toList()
        }
    }

    /**
     * 处理文本消息
     */
    private fun handleTextMessage(message: PushMessage) {
        val content = message.content ?: return

        // 写入剪贴板
        val success = clipboardHelper.copyText(content)

        if (success) {
            // 显示通知
            NotificationHelper.showPushNotification(
                this,
                "收到文本",
                content.take(100)
            )
        }
    }

    /**
     * 处理文件消息
     */
    private suspend fun handleFileMessage(message: PushMessage) {
        val fileUrl = message.fileUrl ?: return
        val fileName = message.fileName ?: "unknown_file"
        val mimeType = message.mimeType ?: FileUtil.getMimeType(fileName)

        // 获取文件处理模式
        val fileMode = settingsRepository.fileHandleModeFlow.first()
        val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress)
        val fullUrl = "$baseUrl$fileUrl"

        when (fileMode) {
            SettingsRepository.FILE_MODE_SAVE_LOCAL -> {
                // 下载文件到本地
                downloadAndSaveFile(fullUrl, fileName, mimeType)
            }
            SettingsRepository.FILE_MODE_COPY_REFERENCE -> {
                // 复制文件 URL
                clipboardHelper.copyFileReference(fullUrl, fileName)
                NotificationHelper.showPushNotification(
                    this,
                    "收到文件",
                    "$fileName\nURL已复制到剪贴板"
                )
            }
        }
    }

    /**
     * 下载文件并保存到本地
     */
    private suspend fun downloadAndSaveFile(
        fileUrl: String,
        fileName: String,
        mimeType: String
    ) {
        val api = apiService ?: return

        // 先下载到缓存目录
        val cacheDir = FileUtil.getCacheDir(this)
        val tempFile = File(cacheDir, fileName)

        // 显示下载中通知
        NotificationHelper.showPushNotification(
            this,
            "正在下载",
            fileName,
            NotificationHelper.getServiceNotificationId() + 100
        )

        val result = api.downloadFile(fileUrl, tempFile) { progress ->
            Log.d(TAG, "Download progress: $progress%")
        }

        result.onSuccess { downloadedFile ->
            // 保存到公共目录
            val savedUri = FileUtil.saveToPublicDownloads(
                this,
                downloadedFile,
                FileUtil.generateUniqueFileName(fileName),
                mimeType
            )

            if (savedUri != null) {
                // 复制文件路径到剪贴板
                val filePath = savedUri.toString()
                clipboardHelper.copyFilePath(filePath)

                NotificationHelper.showPushNotification(
                    this,
                    "文件已保存",
                    "$fileName\n路径已复制到剪贴板"
                )
            } else {
                // 保存到公共目录失败，使用私有目录路径
                val privateDir = FileUtil.getDownloadDir(this)
                val privateFile = File(privateDir, FileUtil.generateUniqueFileName(fileName))
                downloadedFile.copyTo(privateFile, overwrite = true)

                clipboardHelper.copyFilePath(privateFile.absolutePath)
                NotificationHelper.showPushNotification(
                    this,
                    "文件已保存",
                    "$fileName\n路径已复制到剪贴板"
                )
            }

            // 清理临时文件
            tempFile.delete()

        }.onFailure { error ->
            Log.e(TAG, "Download failed: ${error.message}")
            NotificationHelper.showPushNotification(
                this,
                "下载失败",
                "$fileName: ${error.message}"
            )

            // 失败时回退到复制 URL
            clipboardHelper.copyFileReference(fileUrl, fileName)
        }
    }

    /**
     * 更新连接状态
     */
    private fun updateState(state: ConnectionState) {
        Log.d(TAG, "State changed: $currentState -> $state")
        currentState = state

        // 更新通知
        NotificationHelper.updateServiceNotification(this, state, serverAddress)

        // 回调通知 Activity
        onStateChanged?.invoke(state)
    }

    /**
     * 获取当前连接状态
     */
    fun getConnectionState(): ConnectionState = currentState

    /**
     * 手动重连
     */
    fun reconnect() {
        webSocketClient?.disconnect()
        connectWebSocket()
    }
}
