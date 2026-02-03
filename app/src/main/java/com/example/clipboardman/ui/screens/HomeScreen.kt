package com.example.clipboardman.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    serverAddress: String,
    useHttps: Boolean,
    messages: List<PushMessage>,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMessageClick: (PushMessage) -> Unit
) {
    // 构建基础URL
    val baseUrl = remember(serverAddress, useHttps) {
        if (serverAddress.isBlank()) ""
        else if (serverAddress.startsWith("http")) serverAddress
        else "${if (useHttps) "https" else "http"}://$serverAddress"
    }

    // 列表滚动状态
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 收到新消息时滚动到顶部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(0)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clipboard Man") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 顶部连接状态栏
            ConnectionStatusBar(
                connectionState = connectionState,
                serverAddress = serverAddress,
                onConnectClick = onConnectClick,
                onDisconnectClick = onDisconnectClick
            )

            // 消息列表
            if (messages.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (connectionState == ConnectionState.CONNECTED) {
                            "等待接收消息..."
                        } else {
                            "连接服务器后可接收消息"
                        },
                        color = TextSecondary,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageItem(
                            message = message,
                            baseUrl = baseUrl,
                            onClick = { onMessageClick(message) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(
    connectionState: ConnectionState,
    serverAddress: String,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> Green500
        ConnectionState.CONNECTING -> Orange500
        ConnectionState.ERROR -> Red500
        ConnectionState.DISCONNECTED -> Grey500
    }

    val statusText = when (connectionState) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.CONNECTING -> "连接中..."
        ConnectionState.DISCONNECTED -> "未连接"
        ConnectionState.ERROR -> "连接错误"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示点
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 状态文字和服务器地址
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = statusColor
                )
                Text(
                    text = serverAddress.ifEmpty { "未配置" },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 连接/断开按钮
            Button(
                onClick = {
                    when (connectionState) {
                        ConnectionState.CONNECTED, ConnectionState.CONNECTING -> onDisconnectClick()
                        else -> onConnectClick()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (connectionState) {
                        ConnectionState.CONNECTED, ConnectionState.CONNECTING -> Red500
                        else -> MaterialTheme.colorScheme.primary
                    }
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = when (connectionState) {
                        ConnectionState.CONNECTED, ConnectionState.CONNECTING -> "断开"
                        else -> "连接"
                    },
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun MessageItem(
    message: PushMessage,
    baseUrl: String,
    onClick: () -> Unit
) {
    val isImage = message.type == PushMessage.TYPE_IMAGE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 消息类型和时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 类型标签
                val typeLabel = when (message.type) {
                    PushMessage.TYPE_TEXT -> "文本"
                    PushMessage.TYPE_IMAGE -> "图片"
                    PushMessage.TYPE_VIDEO -> "视频"
                    PushMessage.TYPE_AUDIO -> "音频"
                    PushMessage.TYPE_FILE -> "文件"
                    else -> "消息"
                }
                val typeColor = when (message.type) {
                    PushMessage.TYPE_TEXT -> MaterialTheme.colorScheme.primary
                    PushMessage.TYPE_IMAGE -> Green500
                    PushMessage.TYPE_VIDEO -> Orange500
                    PushMessage.TYPE_AUDIO -> Purple500
                    else -> Grey500
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = typeColor.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = typeLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        color = typeColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 时间
                Text(
                    text = formatTimestamp(message.timestamp),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 图片缩略图
            if (isImage && message.fileUrl != null && baseUrl.isNotBlank()) {
                val imageUrl = "$baseUrl${message.fileUrl}"

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = message.fileName,
                    modifier = Modifier
                        .heightIn(max = 120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 文件名
                Text(
                    text = message.fileName ?: "",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // 文本或其他类型的消息内容
                Text(
                    text = message.content ?: message.fileName ?: message.fileUrl ?: "",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 文件大小信息
            if (message.isFileType && message.fileSize != null && message.fileSize > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatFileSize(message.fileSize),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: String?): String {
    if (timestamp.isNullOrEmpty()) return ""
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val date = inputFormat.parse(timestamp) ?: return ""
        val outputFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        outputFormat.format(date)
    } catch (e: Exception) {
        ""
    }
}

private fun formatFileSize(size: Long?): String {
    if (size == null || size <= 0) return ""
    return when {
        size < 1024 -> "${size} B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
}
