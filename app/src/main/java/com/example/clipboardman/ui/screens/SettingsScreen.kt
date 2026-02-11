package com.example.clipboardman.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.util.DebugLogger
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.ui.theme.*
import androidx.compose.ui.text.withStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverAddress: String,
    useHttps: Boolean,
    fileHandleMode: Int,
    autoConnect: Boolean,

    maxHistoryCount: Int,
    connectionState: ConnectionState,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onServerAddressChange: (String) -> Unit,
    onUseHttpsChange: (Boolean) -> Unit,
    onFileHandleModeChange: (Int) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onMaxHistoryCountChange: (Int) -> Unit,
    onScanClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 连接与配对
            SettingsSection(title = "连接与配对") {
                Column {
                    // 1. 连接状态和控制
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusText = when (connectionState) {
                            ConnectionState.CONNECTED -> "已连接"
                            ConnectionState.CONNECTING -> "连接中..."
                            ConnectionState.DISCONNECTED -> "未连接"
                            ConnectionState.ERROR -> "连接错误"
                        }
                        val statusColor = when (connectionState) {
                            ConnectionState.CONNECTED -> Green500
                            ConnectionState.CONNECTING -> Orange500
                            ConnectionState.ERROR -> Red500
                            ConnectionState.DISCONNECTED -> Grey500
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前状态: $statusText",
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor
                            )
                        }

                        Button(
                            onClick = {
                                when (connectionState) {
                                    ConnectionState.CONNECTED, ConnectionState.CONNECTING -> onDisconnectClick()
                                    else -> onConnectClick()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) Red500 else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) "断开" else "连接")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // 2. 配对按钮
                    Button(
                        onClick = onScanClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("扫描二维码配对 (Scan to Pair)")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. 首次使用指南
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "👋 首次使用指南",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                                append("1. 访问 ")
                                pushStringAnnotation(tag = "URL", annotation = "https://www.clipboardpush.com/")
                                withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                                    append("https://www.clipboardpush.com/")
                                }
                                pop()
                                append(" 下载桌面客户端 (Clipboard Push)\n")
                                append("2. 打开客户端设置页面\n")
                                append("3. 点击上方按钮扫描屏幕上的二维码")
                            }

                            androidx.compose.foundation.text.ClickableText(
                                text = annotatedString,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSecondaryContainer),
                                onClick = { offset ->
                                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                        .firstOrNull()?.let { annotation ->
                                            uriHandler.openUri(annotation.item)
                                        }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 文件处理方式
            SettingsSection(title = "文件处理方式") {
                Column {
                    RadioButtonOption(
                        text = "自动保存到本地",
                        description = "仅下载文件到本地，不修改剪贴板",
                        selected = fileHandleMode == SettingsRepository.FILE_MODE_SAVE_LOCAL || fileHandleMode == SettingsRepository.FILE_MODE_COPY_REFERENCE, // Fallback for legacy setting
                        onClick = { onFileHandleModeChange(SettingsRepository.FILE_MODE_SAVE_LOCAL) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RadioButtonOption(
                        text = "自动保存并复制到剪贴板",
                        description = "下载图片到本地，并复制图片到剪贴板可直接粘贴",
                        selected = fileHandleMode == SettingsRepository.FILE_MODE_SAVE_AND_COPY_IMAGE,
                        onClick = { onFileHandleModeChange(SettingsRepository.FILE_MODE_SAVE_AND_COPY_IMAGE) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 历史记录设置
            SettingsSection(title = "历史记录") {
                Column {
                    Text(
                        text = "最大保存消息数量",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "设置列表中保存的历史消息条数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(50, 100, 200, 500).forEach { count ->
                            FilterChip(
                                selected = maxHistoryCount == count,
                                onClick = { onMaxHistoryCountChange(count) },
                                label = { Text("$count") }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 其他设置
            SettingsSection(title = "其他设置") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAutoConnectChange(!autoConnect) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启动时自动连接",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "App 启动后自动连接到服务器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = onAutoConnectChange
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // 电池优化白名单
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // 某些手机可能不支持，尝试打开电池设置页面
                                    try {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e2: Exception) {
                                        android.widget.Toast.makeText(context, "无法打开电池设置", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "忽略电池优化",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (isIgnoringBattery) "✓ 已忽略（后台正常运行）" else "⚠ 未忽略（可能被系统杀死）",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnoringBattery) Green500 else Orange500
                            )
                        }
                        Text(
                            text = "设置 →",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    // 国产手机额外提示
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Orange500.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "📱 国产手机用户注意",
                                style = MaterialTheme.typography.labelLarge,
                                color = Orange500
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "小米/华为/OPPO/vivo 等手机可能还需要：\n" +
                                    "• 在系统设置中搜索「自启动管理」，允许本 APP 自启动\n" +
                                    "• 在「电池」设置中将 APP 设为「无限制」或允许后台运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            SettingsSection(title = "开发日志 (Development Logs)") {
                val logs by DebugLogger.logs.collectAsState()
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Logs", style = MaterialTheme.typography.labelSmall)
                        
                        Row {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Button(
                                onClick = {
                                    val logText = logs.joinToString("\n")
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Debug Logs", logText)
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, "Logs Copied!", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Copy", style = MaterialTheme.typography.labelSmall)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Button(
                                onClick = { DebugLogger.clear() },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun RadioButtonOption(
    text: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
