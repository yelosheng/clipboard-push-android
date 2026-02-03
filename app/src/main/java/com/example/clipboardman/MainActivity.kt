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
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.service.ClipboardService
import com.example.clipboardman.ui.screens.HomeScreen
import com.example.clipboardman.ui.screens.SettingsScreen
import com.example.clipboardman.ui.theme.ClipboardManTheme
import com.example.clipboardman.ui.viewmodel.MainViewModel

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
            mainViewModel?.updateConnectionState(
                clipboardService?.getConnectionState() ?: ConnectionState.DISCONNECTED
            )

            // 同步消息历史（恢复后台收到的消息）
            clipboardService?.getMessageHistory()?.let { history ->
                mainViewModel?.syncMessages(history)
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
                        onMessageClick = { message, serverAddr -> handleMessageClick(message, serverAddr) }
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

    private fun handleMessageClick(message: PushMessage, serverAddress: String) {
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
                openFileWithSystem(message, serverAddress)
            }
        }
    }

    private fun openFileWithSystem(message: PushMessage, serverAddress: String) {
        val baseUrl = if (serverAddress.startsWith("http")) serverAddress else "http://$serverAddress"
        val fileUrl = "$baseUrl${message.fileUrl}"
        val mimeType = message.mimeType ?: "*/*"

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(fileUrl), mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserTitle = when {
                message.type == PushMessage.TYPE_IMAGE -> "选择图片查看器"
                message.type == PushMessage.TYPE_VIDEO -> "选择视频播放器"
                message.type == PushMessage.TYPE_AUDIO -> "选择音频播放器"
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
    onMessageClick: (PushMessage, String) -> Unit
) {
    val navController = rememberNavController()

    val connectionState by viewModel.connectionState.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val fileHandleMode by viewModel.fileHandleMode.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()

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
                onMessageClick = { message -> onMessageClick(message, serverAddress) }
            )
        }

        composable("settings") {
            SettingsScreen(
                serverAddress = serverAddress,
                fileHandleMode = fileHandleMode,
                autoConnect = autoConnect,
                onServerAddressChange = { viewModel.saveServerAddress(it) },
                onFileHandleModeChange = { viewModel.saveFileHandleMode(it) },
                onAutoConnectChange = { viewModel.saveAutoConnect(it) },
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
