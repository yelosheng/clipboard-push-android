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
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.ui.theme.*

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
            // 服务器设置
            SettingsSection(title = "服务器设置") {
                Column {
                    // 协议选择
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "协议",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(60.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))

                        FilterChip(
                            selected = !useHttps,
                            onClick = { onUseHttpsChange(false) },
                            label = { Text("HTTP") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = useHttps,
                            onClick = { onUseHttpsChange(true) },
                            label = { Text("HTTPS") }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 服务器地址
                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = onServerAddressChange,
                        label = { Text("服务器地址") },
                        placeholder = { Text("例如: 192.168.1.100:9661") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 完整地址预览
                    val protocol = if (useHttps) "https" else "http"
                    Text(
                        text = "完整地址: $protocol://$serverAddress",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )


                    Spacer(modifier = Modifier.height(16.dp))

                    // 连接状态和按钮
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
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 文件处理方式
            SettingsSection(title = "文件处理方式") {
                Column {
                    RadioButtonOption(
                        text = "保存到本地",
                        description = "下载文件并复制本地路径到剪贴板",
                        selected = fileHandleMode == SettingsRepository.FILE_MODE_SAVE_LOCAL,
                        onClick = { onFileHandleModeChange(SettingsRepository.FILE_MODE_SAVE_LOCAL) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RadioButtonOption(
                        text = "仅复制引用",
                        description = "复制文件 URL 到剪贴板，不下载文件",
                        selected = fileHandleMode == SettingsRepository.FILE_MODE_COPY_REFERENCE,
                        onClick = { onFileHandleModeChange(SettingsRepository.FILE_MODE_COPY_REFERENCE) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RadioButtonOption(
                        text = "保存并复制图片",
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
