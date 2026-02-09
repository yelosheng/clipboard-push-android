package com.example.clipboardman

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
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import com.example.clipboardman.service.ClipboardService
import com.example.clipboardman.ui.screens.HomeScreen
import com.example.clipboardman.ui.screens.SettingsScreen
import com.example.clipboardman.ui.theme.ClipboardManTheme
import com.example.clipboardman.ui.viewmodel.MainViewModel
import android.util.Log

class MainActivity : ComponentActivity() {

    private var clipboardService: ClipboardService? = null
    private var isBound = false

    // ViewModel 引用，用于状态同步
    private var mainViewModel: MainViewModel? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClipboardService.LocalBinder
            clipboardService = binder.getService()
            isBound = true

            // 设置状态回调
            clipboardService?.onStateChanged = { state ->
                mainViewModel?.updateConnectionState(state)
            }

            // 设置消息回调
            clipboardService?.onMessageReceived = { message ->
                mainViewModel?.addMessage(message)
            }

            // 同步当前状态
            val currentState = clipboardService?.getConnectionState() ?: ConnectionState.DISCONNECTED
            mainViewModel?.updateConnectionState(currentState)
            
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
            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        } else {
            handleScanResult(result.contents)
        }
    }

    private fun launchQRScanner() {
        val options = com.journeyapps.barcodescanner.ScanOptions()
        options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
        options.setPrompt("Scan Pairing Code from PC")
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
            
            lifecycleScope.launch {
                com.example.clipboardman.util.DebugLogger.log("QR_SCAN", "Saving pairing info...")
                val settingsRepo = com.example.clipboardman.data.repository.SettingsRepository(applicationContext)
                settingsRepo.savePairingInfo(server, room, key)
                
                com.example.clipboardman.util.DebugLogger.log("QR_SCAN", "Restarting Service...")
                
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
                
                Toast.makeText(this@MainActivity, "Settings Saved. Connecting...", Toast.LENGTH_LONG).show()
                // Update Log UI
                com.example.clipboardman.util.DebugLogger.log("MainActivity", "Service restart requested")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid Code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求通知权限 (Android 13+)
        requestNotificationPermission()

        setContent {
            ClipboardManTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel()

                    // 保存 ViewModel 引用
                    LaunchedEffect(viewModel) {
                        mainViewModel = viewModel
                    }

                    MainNavigation(
                        viewModel = viewModel,
                        onStartService = { startClipboardService() },
                        onStopService = { stopClipboardService() },
                        onMessageClick = { message, serverAddr, useHttps -> handleMessageClick(message, serverAddr, useHttps) },
                        onPushClipboard = { handlePushClipboard() },
                        onScanClick = { launchQRScanner() }
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
                val settingsRepository = com.example.clipboardman.data.repository.SettingsRepository(this@MainActivity)
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
        mainViewModel?.updateConnectionState(ConnectionState.DISCONNECTED)
    }

    private fun handleMessageClick(message: PushMessage, serverAddress: String, useHttps: Boolean) {
        when {
            // 文本消息 - 复制到剪贴板
            message.isTextType -> {
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = message.content ?: return
                val clip = ClipData.newPlainText("Clipboard Man", text)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
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
                startActivity(Intent.createChooser(intent, "选择应用打开"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileWithSystem(message: PushMessage, serverAddress: String, useHttps: Boolean) {
        val protocol = if (useHttps) "https" else "http"
        val baseUrl = if (serverAddress.startsWith("http")) serverAddress else "$protocol://$serverAddress"
        val fileUrl = "$baseUrl${message.fileUrl}"
        val fileName = message.fileName ?: "file"
        val mimeType = message.mimeType ?: "*/*"

        Toast.makeText(this, "正在下载...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 下载文件到缓存目录
                val client = OkHttpClient()
                val request = Request.Builder().url(fileUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "下载失败", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                PushMessage.TYPE_IMAGE -> "选择图片查看器"
                PushMessage.TYPE_VIDEO -> "选择视频播放器"
                PushMessage.TYPE_AUDIO -> "选择音频播放器"
                else -> "选择打开方式"
            }

            val chooser = Intent.createChooser(intent, chooserTitle)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }

    }

    private fun handlePushClipboard() {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboardManager.hasPrimaryClip() || clipboardManager.primaryClipDescription == null) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }

        val item = clipboardManager.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板内容为空或非文本", Toast.LENGTH_SHORT).show()
            return
        }

        if (clipboardService == null) {
            Toast.makeText(this, "服务未连接", Toast.LENGTH_SHORT).show()
            return
        }

        clipboardService?.sendClipboardText(text)
        Toast.makeText(this, "推送成功", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onMessageClick: (PushMessage, String, Boolean) -> Unit,

    onPushClipboard: () -> Unit,
    onScanClick: () -> Unit
) {
    val navController = rememberNavController()

    val connectionState by viewModel.connectionState.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val useHttps by viewModel.useHttps.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val fileHandleMode by viewModel.fileHandleMode.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()
    val maxHistoryCount by viewModel.maxHistoryCount.collectAsState()

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
                serverAddress = serverAddress,
                useHttps = useHttps,
                messages = messages,
                onSettingsClick = {
                    navController.navigate("settings")
                },
                onMessageClick = { message -> onMessageClick(message, serverAddress, useHttps) },
                onDeleteMessages = { messageIds -> viewModel.deleteMessages(messageIds) },
                onPushClipboard = onPushClipboard,
                onReconnectClick = onStartService
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
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
