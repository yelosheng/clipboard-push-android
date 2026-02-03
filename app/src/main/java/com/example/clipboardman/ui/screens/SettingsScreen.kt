package com.example.clipboardman.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.clipboardman.data.repository.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverAddress: String,
    fileHandleMode: Int,
    autoConnect: Boolean,
    onServerAddressChange: (String) -> Unit,
    onFileHandleModeChange: (Int) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
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
        ) {
            // 服务器地址
            SettingsSection(title = "服务器设置") {
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = onServerAddressChange,
                    label = { Text("服务器地址") },
                    placeholder = { Text("例如: 192.168.1.100:9661") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
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
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 自动连接
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
