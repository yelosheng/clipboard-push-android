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
import android.widget.Toast
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
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
                        onCopyMessage = { message -> copyToClipboard(message) }
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

    private fun copyToClipboard(message: PushMessage) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = message.content ?: message.fileUrl ?: return
        val clip = ClipData.newPlainText("Clipboard Man", text)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onCopyMessage: (PushMessage) -> Unit
) {
    val navController = rememberNavController()

    val connectionState by viewModel.connectionState.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val fileHandleMode by viewModel.fileHandleMode.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()

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
                onMessageClick = onCopyMessage
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
