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

            // 同步消息历史：从本地存储加载，而不是Service内存
            // 这样可以确保删除操作被正确保留
            CoroutineScope(Dispatchers.Main).launch {
                val settingsRepository = com.example.clipboardman.data.repository.SettingsRepository(this@MainActivity)
                val messageRepository = com.example.clipboardman.data.repository.MessageRepository(this@MainActivity)
                val storedMessages = messageRepository.messagesFlow.first()
                mainViewModel?.syncMessages(storedMessages)
            }

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
                        onMessageClick = { message, serverAddr, useHttps -> handleMessageClick(message, serverAddr, useHttps) }
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
        CoroutineScope(Dispatchers.Main).launch {
            val settingsRepository = com.example.clipboardman.data.repository.SettingsRepository(this@MainActivity)
            val autoConnect = settingsRepository.autoConnectFlow.first()
            val serverAddress = settingsRepository.serverAddressFlow.first()

            if (autoConnect && serverAddress.isNotBlank()) {
                startClipboardService()
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
            // 图片/文件 - 用系统应用打开
            message.isFileType && message.fileUrl != null -> {
                openFileWithSystem(message, serverAddress, useHttps)
            }
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
                    openLocalFile(localFile, mimeType, message.type)
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
}

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onMessageClick: (PushMessage, String, Boolean) -> Unit
) {
    val navController = rememberNavController()

    val connectionState by viewModel.connectionState.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val useHttps by viewModel.useHttps.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val fileHandleMode by viewModel.fileHandleMode.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()
    val maxHistoryCount by viewModel.maxHistoryCount.collectAsState()

    // 自动连接
    LaunchedEffect(autoConnect, serverAddress, connectionState) {
        if (autoConnect &&
            serverAddress.isNotBlank() &&
            connectionState == ConnectionState.DISCONNECTED) {
            onStartService()
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
                onConnectClick = {
                    if (serverAddress.isNotBlank()) {
                        onStartService()
                    }
                },
                onDisconnectClick = {
                    onStopService()
                },
                onSettingsClick = {
                    navController.navigate("settings")
                },
                onMessageClick = { message -> onMessageClick(message, serverAddress, useHttps) },
                onDeleteMessages = { messageIds -> viewModel.deleteMessages(messageIds) }
            )
        }

        composable("settings") {
            SettingsScreen(
                serverAddress = serverAddress,
                useHttps = useHttps,
                fileHandleMode = fileHandleMode,
                autoConnect = autoConnect,
                maxHistoryCount = maxHistoryCount,
                onServerAddressChange = { viewModel.saveServerAddress(it) },
                onUseHttpsChange = { viewModel.saveUseHttps(it) },
                onFileHandleModeChange = { viewModel.saveFileHandleMode(it) },
                onAutoConnectChange = { viewModel.saveAutoConnect(it) },
                onMaxHistoryCountChange = { viewModel.saveMaxHistoryCount(it) },
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
