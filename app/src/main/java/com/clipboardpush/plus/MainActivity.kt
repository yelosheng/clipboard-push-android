package com.clipboardpush.plus

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.clipboardpush.plus.data.model.ConnectionState
import com.clipboardpush.plus.data.model.PeerEntry
import com.clipboardpush.plus.data.model.PushMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import com.clipboardpush.plus.R
import com.clipboardpush.plus.service.ClipboardService
import com.clipboardpush.plus.ui.screens.HomeScreen
import com.clipboardpush.plus.ui.screens.SettingsScreen
import com.clipboardpush.plus.ui.theme.ClipboardManTheme
import com.clipboardpush.plus.ui.viewmodel.MainViewModel
import android.util.Log

class MainActivity : ComponentActivity() {

    private var clipboardService: ClipboardService? = null
    private var isBound = false

    // ViewModel 引用，用于状态同步
    private lateinit var mainViewModel: MainViewModel

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClipboardService.LocalBinder
            clipboardService = binder.getService()
            isBound = true


            // 设置状态回调
            clipboardService?.onStateChanged = { state ->
                mainViewModel.updateConnectionState(state)
            }

            // 设置消息回调
            clipboardService?.onMessageReceived = { message ->
                mainViewModel.addMessage(message)
            }
            
            // 设置 PeerCount 回调
            clipboardService?.onPeerCountChanged = { count ->
                mainViewModel.updatePeerCount(count)
            }
            
            // 设置 Peers 回调
            clipboardService?.onPeersChanged = { roomId, peers ->
                mainViewModel.updatePeers(peers)
                // Update display name for active room once PC reports its identity.
                // Use roomId from the service (authoritative) to avoid a race where
                // activeRoomId StateFlow hasn't yet reflected a recent room switch.
                if (peers.isNotEmpty() && !roomId.isNullOrBlank()) {
                    mainViewModel.updateRecentPeerDisplayName(roomId, peers.first())
                }
            }

            // 设置下载失败回调
            clipboardService?.onMessageDownloadFailed = { messageId ->
                mainViewModel.markDownloadFailed(messageId)
            }

            clipboardService?.onMessageDownloadProgress = { messageId, progress ->
                mainViewModel.updateDownloadProgress(messageId, progress)
            }

            // 同步当前状态
            val currentState = clipboardService?.getConnectionState() ?: ConnectionState.DISCONNECTED
            mainViewModel.updateConnectionState(currentState)
            mainViewModel.updatePeerCount(clipboardService?.getPeerCount() ?: 0)
            mainViewModel.updatePeers(clipboardService?.getPeers() ?: emptyList())
            
            // 不再从 Service 内存同步消息历史
            // ViewModel 通过 messageRepository.messagesFlow.collect 持续观察，更可靠

            // 自动重连：如果启用了自动连接且当前是断开状态，则重新连接
            if (currentState == ConnectionState.DISCONNECTED) {
                checkAndAutoReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clipboardService?.onStateChanged = null
            clipboardService?.onMessageReceived = null
            clipboardService?.onPeerCountChanged = null
            clipboardService?.onMessageDownloadFailed = null
            clipboardService?.onMessageDownloadProgress = null
            clipboardService = null
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // 权限请求结果处理
    }

    private val scanLauncher = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, getString(R.string.toast_scan_cancelled), Toast.LENGTH_SHORT).show()
        } else {
            handleScanResult(result.contents)
        }
    }

    private fun launchQRScanner() {
        val options = com.journeyapps.barcodescanner.ScanOptions()
        options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
        options.setPrompt(getString(R.string.qr_scan_prompt))
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(false)
        options.setOrientationLocked(false)
        options.setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
        scanLauncher.launch(options)
    }

    private fun handleScanResult(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val server = obj.getString("server")
            val room = obj.getString("room")
            val key = obj.getString("key")
            
            // Optional Local Sync Info
            val localIp = if (obj.has("local_ip")) obj.getString("local_ip") else null
            val localPort = if (obj.has("local_port")) obj.getInt("local_port") else null
            
            lifecycleScope.launch {
                val settingsRepo = com.clipboardpush.plus.data.repository.SettingsRepository(applicationContext)
                settingsRepo.savePairingInfo(server, room, key, localIp, localPort)

                // Write to recent peers history (placeholder name until PC connects)
                val displayName = if (!localIp.isNullOrBlank()) "PC @ $localIp"
                                  else "PC (${room.takeLast(8)})"
                mainViewModel.addOrUpdateRecentPeer(
                    PeerEntry(
                        room = room,
                        server = server,
                        key = key,
                        localIp = localIp,
                        localPort = localPort,
                        displayName = displayName,
                        lastConnectedAt = System.currentTimeMillis()
                    )
                )

                
                // --- Immediate Local Connection Test ---
                if (!localIp.isNullOrBlank() && localPort != null && localPort > 0) {
                    launch(Dispatchers.IO) {
                        try {
                            val testUrl = "http://$localIp:$localPort/ping"
                            
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                                
                            val request = okhttp3.Request.Builder()
                                .url(testUrl)
                                .addHeader("X-Room-ID", room)
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val body = response.body?.string() ?: ""
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, getString(R.string.toast_lan_ok), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                     withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, getString(R.string.toast_lan_failed, response.code), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_lan_unreachable), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                // ---------------------------------------
                
                // Restart Service with ACTION_START
                val intent = Intent(this@MainActivity, ClipboardService::class.java).apply {
                    action = ClipboardService.ACTION_START
                }
                stopService(intent)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                
                Toast.makeText(this@MainActivity, getString(R.string.toast_settings_saved), Toast.LENGTH_LONG).show()
                // Update Log UI
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_invalid_code, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewModel synchronously to ensure it's ready for Service callbacks
        mainViewModel = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
        
        // 请求通知权限 (Android 13+)
        requestNotificationPermission()

        setContent {
            ClipboardManTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Use the Activity-scoped ViewModel instance directly
                    // No need to re-initialize or use LaunchedEffect
                    
                    MainNavigation(
                        viewModel = mainViewModel,
                        onStartService = { startClipboardService() },
                        onStopService = { stopClipboardService() },
                        onMessageClick = { message, serverAddr, useHttps -> handleMessageClick(message, serverAddr, useHttps) },
                        onPushClipboard = { handlePushClipboard() },
                        onScanClick = { launchQRScanner() },
                        onPeerSelected = { entry -> handlePeerSelected(entry) },
                        onPeerRemoved = { entry -> mainViewModel.removeRecentPeer(entry.room) },
                        onRetryDownload = { message ->
                            mainViewModel.markDownloadRetrying(message.safeId)
                            clipboardService?.retryFileDownload(message)
                        },
                        onFileOpen = { message -> handleFileOpen(message) },
                        onFileShare = { message -> handleFileShare(message) },
                        onFileCopyName = { message -> handleFileCopyName(message) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 绑定服务
        Intent(this, ClipboardService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // 解绑服务
        if (isBound) {
            clipboardService?.onStateChanged = null
            clipboardService?.onMessageReceived = null
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkAndAutoReconnect() {
        lifecycleScope.launch {
            try {
                val settingsRepository = com.clipboardpush.plus.data.repository.SettingsRepository(this@MainActivity)
                val autoConnect = settingsRepository.autoConnectFlow.first()
                val serverAddress = settingsRepository.serverAddressFlow.first()

                if (autoConnect && serverAddress.isNotBlank()) {
                    startClipboardService()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in checkAndAutoReconnect", e)
            }
        }
    }

    private fun startClipboardService() {
        val intent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopClipboardService() {
        val intent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_STOP
        }
        startService(intent)
        mainViewModel.updateConnectionState(ConnectionState.DISCONNECTED)
    }

    private fun handleMessageClick(message: PushMessage, serverAddress: String, useHttps: Boolean) {
        when {
            // 文本消息 - 复制到剪贴板
            message.isTextType -> {
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = message.content ?: return
                val clip = ClipData.newPlainText("Clipboard Man", text)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }
            // 图片/文件 - 优先使用本地路径
            message.isFileType && message.localPath != null -> {
                // 已有本地文件，直接打开
                openLocalUri(message.localPath!!, message.mimeType ?: "*/*", message.type ?: PushMessage.TYPE_FILE)
            }
            // 图片/文件 - 无本地路径，从网上下载
            message.isFileType && message.fileUrl != null -> {
                openFileWithSystem(message, serverAddress, useHttps)
            }
        }
    }

    private fun handleFileOpen(message: PushMessage) {
        val localPath = message.localPath ?: run {
            Toast.makeText(this, getString(R.string.file_action_downloading), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = android.net.Uri.parse(localPath)
            val mimeType = resolveMimeType(message)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.chooser_open_file)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_open_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFileShare(message: PushMessage) {
        val localPath = message.localPath ?: return
        try {
            val uri = android.net.Uri.parse(localPath)
            val mimeType = resolveMimeType(message)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.file_action_share)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_open_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFileCopyName(message: PushMessage) {
        val name = message.fileName ?: message.content ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("filename", name))
        Toast.makeText(this, getString(R.string.toast_filename_copied), Toast.LENGTH_SHORT).show()
    }

    private fun resolveMimeType(message: PushMessage): String {
        val mime = message.mimeType
        if (!mime.isNullOrBlank() && mime != "application/octet-stream") return mime
        return when (message.type) {
            PushMessage.TYPE_VIDEO -> "video/*"
            PushMessage.TYPE_AUDIO -> "audio/*"
            PushMessage.TYPE_IMAGE -> "image/*"
            else -> {
                val ext = message.fileName?.substringAfterLast('.')?.lowercase()
                when (ext) {
                    "pdf" -> "application/pdf"
                    "doc" -> "application/msword"
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    "xls" -> "application/vnd.ms-excel"
                    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    "ppt" -> "application/vnd.ms-powerpoint"
                    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    "zip" -> "application/zip"
                    "txt" -> "text/plain"
                    "apk" -> "application/vnd.android.package-archive"
                    else -> "application/octet-stream"
                }
            }
        }
    }

    private fun openLocalUri(uriString: String, mimeType: String, messageType: String) {
        try {
            val uri = android.net.Uri.parse(uriString)
            
            // 根据消息类型推断正确的 MIME 类型
            val actualMimeType = when {
                mimeType != "*/*" && mimeType.isNotBlank() -> mimeType
                messageType == PushMessage.TYPE_IMAGE -> "image/*"
                messageType == PushMessage.TYPE_VIDEO -> "video/*"
                messageType == PushMessage.TYPE_AUDIO -> "audio/*"
                else -> "*/*"
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, actualMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 直接启动，不使用 chooser
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // 没有找到可以处理的应用，显示 chooser
                startActivity(Intent.createChooser(intent, getString(R.string.chooser_open_file)))
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_open_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileWithSystem(message: PushMessage, serverAddress: String, useHttps: Boolean) {
        val protocol = if (useHttps) "https" else "http"
        val baseUrl = if (serverAddress.startsWith("http")) serverAddress else "$protocol://$serverAddress"
        val fileUrl = "$baseUrl${message.fileUrl}"
        val fileName = message.fileName ?: "file"
        val mimeType = message.mimeType ?: "*/*"

        Toast.makeText(this, getString(R.string.toast_downloading), Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 下载文件到缓存目录
                val client = OkHttpClient()
                val request = Request.Builder().url(fileUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_download_failed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 保存到缓存目录
                val cacheDir = File(cacheDir, "shared_files")
                cacheDir.mkdirs()
                val localFile = File(cacheDir, fileName)

                response.body?.byteStream()?.use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    openLocalFile(localFile, mimeType, message.type ?: PushMessage.TYPE_FILE)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_open_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openLocalFile(file: File, mimeType: String, messageType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserTitle = when (messageType) {
                PushMessage.TYPE_IMAGE -> getString(R.string.chooser_open_image)
                PushMessage.TYPE_VIDEO -> getString(R.string.chooser_open_video)
                PushMessage.TYPE_AUDIO -> getString(R.string.chooser_open_audio)
                else -> getString(R.string.chooser_open_file)
            }

            val chooser = Intent.createChooser(intent, chooserTitle)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_open_file_failed, e.message), Toast.LENGTH_SHORT).show()
        }

    }

    private fun handlePushClipboard() {
        // Peer guard: block push if no peers online
        if (mainViewModel.peerCount.value <= 0) {
            Toast.makeText(this, getString(R.string.toast_no_peers), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboardManager.hasPrimaryClip() || clipboardManager.primaryClipDescription == null) {
            Toast.makeText(this, getString(R.string.toast_clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val item = clipboardManager.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()

        if (text.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.toast_clipboard_not_text), Toast.LENGTH_SHORT).show()
            return
        }

        if (clipboardService == null) {
            Toast.makeText(this, getString(R.string.toast_service_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        clipboardService?.sendClipboardText(text)
        Toast.makeText(this, getString(R.string.toast_push_success), Toast.LENGTH_SHORT).show()
    }

    private fun handlePeerSelected(entry: PeerEntry) {
        Toast.makeText(this, getString(R.string.toast_switching_peer, entry.displayName), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val settingsRepo = com.clipboardpush.plus.data.repository.SettingsRepository(applicationContext)
            settingsRepo.savePairingInfo(entry.server, entry.room, entry.key, entry.localIp, entry.localPort)
            mainViewModel.addOrUpdateRecentPeer(entry.copy(lastConnectedAt = System.currentTimeMillis()))
            // 如果服务已绑定，直接调用 reconnect()：服务会读取新设置并重新连接，
            // 不经过 stopForeground()/stopSelf()，避免切换后后台断连问题。
            // 否则走正常 start 流程（DataStore 已更新，服务启动时会读到新 room）。
            if (clipboardService != null) {
                clipboardService!!.reconnect()
            } else {
                startClipboardService()
            }
        }
    }
}

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onMessageClick: (PushMessage, String, Boolean) -> Unit,
    onPushClipboard: () -> Unit,
    onScanClick: () -> Unit,
    onPeerSelected: (PeerEntry) -> Unit,
    onPeerRemoved: (PeerEntry) -> Unit,
    onRetryDownload: (PushMessage) -> Unit = {},
    onFileOpen: (PushMessage) -> Unit = {},
    onFileShare: (PushMessage) -> Unit = {},
    onFileCopyName: (PushMessage) -> Unit = {}
) {
    val navController = rememberNavController()

    val connectionState by viewModel.connectionState.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val useHttps by viewModel.useHttps.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val fileHandleMode by viewModel.fileHandleMode.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()
    val maxHistoryCount by viewModel.maxHistoryCount.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val recentPeers by viewModel.recentPeers.collectAsState()
    val activeRoomId by viewModel.activeRoomId.collectAsState()
    val failedDownloadIds by viewModel.failedDownloadIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isFileUploading by viewModel.fileUploadActive.collectAsState()
    val showScanOnboarding by viewModel.showScanOnboarding.collectAsState()
    val showHomeOnboarding by viewModel.showHomeOnboarding.collectAsState()
    val showPushOnboarding by viewModel.showPushOnboarding.collectAsState()

    LaunchedEffect(messages) {
        messages.filter { it.localPath != null }.forEach { viewModel.clearDownloadProgress(it.safeId) }
    }

    // 自动连接 (仅当设置改变时触发，或者首次进入时)
    LaunchedEffect(autoConnect, serverAddress) {
        if (autoConnect && serverAddress.isNotBlank()) {
            // 这里我们只在设置改变且需要连接时尝试连接
            // 如果已经在连接或已连接，Service 会处理忽略
            // 但为了避免手动断开后修改配置立刻重连（如果这是非期望行为），可能需要更复杂的逻辑
            // 目前这样改可以解决"点了断开马上重连"的问题，因为断开时 autoConnect 和 serverAddress 没变
             if (connectionState == ConnectionState.DISCONNECTED) {
                 onStartService()
             }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                connectionState = connectionState,
                peerCount = peerCount,
                serverAddress = serverAddress,
                useHttps = useHttps,
                messages = messages,
                onSettingsClick = {
                    navController.navigate("settings")
                },
                onMessageClick = { message -> onMessageClick(message, serverAddress, useHttps) },
                onDeleteMessages = { messageIds -> viewModel.deleteMessages(messageIds) },
                onPushClipboard = onPushClipboard,
                onReconnectClick = onStartService,
                peers = peers,
                failedDownloadIds = failedDownloadIds,
                downloadProgress = downloadProgress,
                onRetryDownload = onRetryDownload,
                onFileOpen = onFileOpen,
                onFileShare = onFileShare,
                onFileCopyName = onFileCopyName,
                isFileUploading = isFileUploading,
                showOnboarding = showHomeOnboarding,
                onOnboardingDismiss = {
                    viewModel.dismissHomeOnboarding()
                    navController.navigate("settings")
                },
                showPushOnboarding = showPushOnboarding,
                onPushOnboardingDismiss = { viewModel.dismissPushOnboarding() }
            )
        }

        composable("settings") {
            SettingsScreen(
                serverAddress = serverAddress,
                useHttps = useHttps,
                fileHandleMode = fileHandleMode,
                autoConnect = autoConnect,
                maxHistoryCount = maxHistoryCount,
                connectionState = connectionState,
                peers = peers,
                onConnectClick = {
                    if (serverAddress.isNotBlank()) {
                        onStartService()
                    }
                },
                onDisconnectClick = {
                    onStopService()
                },
                onServerAddressChange = { viewModel.saveServerAddress(it) },
                onUseHttpsChange = { viewModel.saveUseHttps(it) },
                onFileHandleModeChange = { viewModel.saveFileHandleMode(it) },
                onAutoConnectChange = { viewModel.saveAutoConnect(it) },
                onMaxHistoryCountChange = { viewModel.saveMaxHistoryCount(it) },
                onScanClick = onScanClick,
                onBackClick = { navController.popBackStack() },
                recentPeers = recentPeers,
                activeRoomId = activeRoomId,
                onPeerSelected = onPeerSelected,
                onPeerRemoved = onPeerRemoved,
                showScanOnboarding = showScanOnboarding,
                onOnboardingDismiss = { viewModel.dismissScanOnboarding() }
            )
        }
    }
}
