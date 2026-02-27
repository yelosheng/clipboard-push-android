package com.clipboardpush.plus.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentCopy
import com.clipboardpush.plus.util.formatFileSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import com.clipboardpush.plus.R
import com.clipboardpush.plus.data.model.ConnectionState
import com.clipboardpush.plus.data.model.PushMessage
import com.clipboardpush.plus.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    peerCount: Int,
    serverAddress: String,
    useHttps: Boolean,
    messages: List<PushMessage>,
    onSettingsClick: () -> Unit,
    onMessageClick: (PushMessage) -> Unit,
    onDeleteMessages: (Set<String>) -> Unit = {},
    onPushClipboard: () -> Unit,
    onReconnectClick: () -> Unit = {},
    peers: List<String> = emptyList(),
    failedDownloadIds: Set<String> = emptySet(),
    downloadProgress: Map<String, Int> = emptyMap(),
    onRetryDownload: (PushMessage) -> Unit = {},
    onFileOpen: (PushMessage) -> Unit = {},
    onFileShare: (PushMessage) -> Unit = {},
    onFileCopyName: (PushMessage) -> Unit = {}
) {
    // 构建基础URL
    val baseUrl = remember(serverAddress, useHttps) {
        if (serverAddress.isBlank()) ""
        else if (serverAddress.startsWith("http")) serverAddress
        else "${if (useHttps) "https" else "http"}://$serverAddress"
    }

    // 选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<String>()) }

    // 文件操作底部弹窗状态
    var fileActionMessage by remember { mutableStateOf<PushMessage?>(null) }

    // 列表滚动状态
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 收到新消息时滚动到顶部
    // Bug fix: only scroll if size INCREASED (new message), not decreased (deletion)
    var previousMessageCount by remember { androidx.compose.runtime.mutableIntStateOf(messages.size) }

    LaunchedEffect(messages.size) {
        if (messages.size > previousMessageCount) {
            coroutineScope.launch {
                listState.animateScrollToItem(0)
            }
        }
        previousMessageCount = messages.size
    }

    // 退出选择模式时清空选择
    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) {
            selectedMessageIds = emptySet()
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                // 选择模式的 TopAppBar
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.selection_count, selectedMessageIds.size))
                    },
                    navigationIcon = {
                        IconButton(onClick = { isSelectionMode = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_exit_selection)
                            )
                        }
                    },
                    actions = {
                        // 全选/取消全选
                        TextButton(
                            onClick = {
                                selectedMessageIds = if (selectedMessageIds.size == messages.size) {
                                    emptySet()
                                } else {
                                    messages.map { it.safeId }.toSet()
                                }
                            }
                        ) {
                            Text(
                                text = if (selectedMessageIds.size == messages.size)
                                    stringResource(R.string.action_deselect_all)
                                else
                                    stringResource(R.string.action_select_all),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        // 删除按钮
                        IconButton(
                            onClick = {
                                if (selectedMessageIds.isNotEmpty()) {
                                    onDeleteMessages(selectedMessageIds)
                                    isSelectionMode = false
                                }
                            },
                            enabled = selectedMessageIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            } else {
                // 正常模式的 TopAppBar
                TopAppBar(
                    title = {
                        if (connectionState == ConnectionState.CONNECTED) {
                            // Filter logic: same as SettingsScreen
                            // peers list is already self-filtered by RelayRepository
                            if (peers.isNotEmpty()) {
                                Text(
                                    text = peers.joinToString(", "),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // 显示连接状态图标
                        val (icon, tint, description) = when (connectionState) {
                            ConnectionState.CONNECTED -> {
                                if (peerCount > 0) {
                                    Triple(Icons.Default.Cloud, Green500, stringResource(R.string.state_connected_with_peer))
                                } else {
                                    Triple(Icons.Default.Cloud, androidx.compose.ui.graphics.Color(0xFFFFC107), stringResource(R.string.state_connected_waiting)) // Yellow for Alone
                                }
                            }
                            ConnectionState.CONNECTING -> Triple(Icons.Default.Sync, Orange500, stringResource(R.string.state_connecting))
                            ConnectionState.ERROR -> Triple(Icons.Default.Warning, Red500, stringResource(R.string.state_error))
                            ConnectionState.DISCONNECTED -> Triple(Icons.Default.CloudOff, Grey500, stringResource(R.string.state_disconnected))
                        }

                        IconButton(
                            onClick = {
                                if (connectionState != ConnectionState.CONNECTED) {
                                    onReconnectClick()
                                } else {
                                    // Connected toast or info?
                                }
                            }
                        ) {
                            if (connectionState == ConnectionState.CONNECTING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = tint,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = description,
                                    tint = tint,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        // 推送剪贴板按钮 - disabled when no peers online
                        val isPushEnabled = connectionState != ConnectionState.CONNECTED || peerCount > 0
                        IconButton(
                            onClick = onPushClipboard,
                            enabled = isPushEnabled
                        ) {
                            // 使用 Send 图标表示推送
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = if (isPushEnabled)
                                    stringResource(R.string.cd_push_clipboard)
                                else
                                    stringResource(R.string.cd_waiting_for_pc)
                            )
                        }
                        // 设置按钮
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 状态横幅
            AnimatedVisibility(
                visible = connectionState == ConnectionState.ERROR || (connectionState == ConnectionState.DISCONNECTED && serverAddress.isNotBlank()),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if(connectionState == ConnectionState.ERROR) Red500 else Orange500)
                        .clickable { onReconnectClick() }
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if(connectionState == ConnectionState.ERROR) Icons.Default.Warning else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if(connectionState == ConnectionState.ERROR)
                                stringResource(R.string.banner_error)
                            else
                                stringResource(R.string.banner_disconnected),
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            // 消息列表
            if (messages.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (connectionState == ConnectionState.CONNECTED) {
                                if (peerCount > 0) stringResource(R.string.empty_waiting_message)
                                else stringResource(R.string.empty_waiting_pc)
                            } else {
                                stringResource(R.string.empty_no_server)
                            },
                            color = TextSecondary,
                            fontSize = 16.sp
                        )
                        
                        if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.empty_go_to_settings), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { _, message -> message.safeId }
                    ) { _, message ->
                        SwipeableMessageItem(
                            modifier = Modifier.animateItem(),
                            message = message,
                            baseUrl = baseUrl,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedMessageIds.contains(message.safeId),
                            isFailed = message.safeId in failedDownloadIds,
                            downloadProgress = downloadProgress[message.safeId],
                            onClick = {
                                if (isSelectionMode) {
                                    // 选择模式下切换选中状态
                                    selectedMessageIds = if (message.safeId in selectedMessageIds) {
                                        selectedMessageIds - message.safeId
                                    } else {
                                        selectedMessageIds + message.safeId
                                    }
                                } else if (message.isFileType && message.type != PushMessage.TYPE_IMAGE) {
                                    fileActionMessage = message
                                } else {
                                    // 普通模式下执行点击操作
                                    onMessageClick(message)
                                }
                            },
                            onLongClick = {
                                // 长按进入选择模式并选中当前项
                                isSelectionMode = true
                                selectedMessageIds = setOf(message.safeId)
                            },
                            onDelete = {
                                onDeleteMessages(setOf(message.safeId))
                            },
                            onRetryDownload = onRetryDownload
                        )
                    }
                }
            }

            // 文件操作底部弹窗
            fileActionMessage?.let { msg ->
                val isFailed = msg.safeId in failedDownloadIds
                FileActionSheet(
                    message = msg,
                    isDownloading = msg.localPath == null && !isFailed,
                    downloadProgress = downloadProgress[msg.safeId],
                    onDismiss = { fileActionMessage = null },
                    onOpen = {
                        fileActionMessage = null
                        onFileOpen(msg)
                    },
                    onShare = {
                        fileActionMessage = null
                        onFileShare(msg)
                    },
                    onCopyName = {
                        fileActionMessage = null
                        onFileCopyName(msg)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableMessageItem(
    message: PushMessage,
    baseUrl: String,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isFailed: Boolean = false,
    downloadProgress: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit,
    onRetryDownload: (PushMessage) -> Unit = {}
) {
    if (isSelectionMode) {
        MessageItem(
            message = message,
            baseUrl = baseUrl,
            modifier = modifier,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            isFailed = isFailed,
            downloadProgress = downloadProgress,
            onClick = onClick,
            onLongClick = onLongClick,
            onRetryDownload = onRetryDownload
        )
        return
    }

    val density = LocalDensity.current
    val actionWidth = 80.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }

    // 使用普通状态跟踪实际偏移（同步更新）
    var currentOffset by remember { mutableFloatStateOf(0f) }
    // 动画控制器（用于释放后的动画）
    val animatedOffset = remember { Animatable(0f) }
    // 是否正在拖动
    var isDragging by remember { mutableStateOf(false) }
    // 显示用的偏移量
    val displayOffset = if (isDragging) currentOffset else animatedOffset.value

    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        // 删除按钮背景
        Box(
            modifier = Modifier
                .width(actionWidth)
                .fillMaxHeight()
                .background(Red500, RoundedCornerShape(12.dp))
                .clickable {
                    onDelete()
                    currentOffset = 0f
                    scope.launch { animatedOffset.snapTo(0f) }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onError
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = 12.sp
                )
            }
        }

        // 前景内容
        Box(
            modifier = Modifier
                .offset { IntOffset(displayOffset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 同步更新，不用 launch
                        currentOffset = (currentOffset + delta).coerceIn(-actionWidthPx, 0f)
                    },
                    onDragStarted = {
                        isDragging = true
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            // 根据当前位置和滑动速度决定目标位置
                            val target = when {
                                // 快速向左滑动，展开
                                velocity < -500f -> -actionWidthPx
                                // 快速向右滑动，收回
                                velocity > 500f -> 0f
                                // 超过一半，展开
                                currentOffset < -actionWidthPx / 2 -> -actionWidthPx
                                // 否则收回
                                else -> 0f
                            }
                            // 先同步动画器到当前位置
                            animatedOffset.snapTo(currentOffset)
                            // 切换到动画模式
                            isDragging = false
                            // 执行动画
                            animatedOffset.animateTo(target)
                            // 更新当前偏移
                            currentOffset = target
                        }
                    }
                )
        ) {
            MessageItem(
                message = message,
                baseUrl = baseUrl,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isFailed = isFailed,
                downloadProgress = downloadProgress,
                onClick = {
                    if (displayOffset < -10f) {
                        // 如果已展开，点击则收回
                        scope.launch {
                            animatedOffset.animateTo(0f)
                            currentOffset = 0f
                        }
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongClick,
                onRetryDownload = onRetryDownload
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: PushMessage,
    baseUrl: String,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isFailed: Boolean = false,
    downloadProgress: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onRetryDownload: (PushMessage) -> Unit = {}
) {
    val isImage = message.type == PushMessage.TYPE_IMAGE

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 选择模式下显示选择指示器
            if (isSelectionMode) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = if (!isSelected) {
                        androidx.compose.foundation.BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else null
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
            // 类型标签属性提升到 Column 作用域，供下方文件图标区复用
            val typeLabel = when (message.type) {
                PushMessage.TYPE_TEXT -> stringResource(R.string.type_text)
                PushMessage.TYPE_IMAGE -> stringResource(R.string.type_image)
                PushMessage.TYPE_VIDEO -> stringResource(R.string.type_video)
                PushMessage.TYPE_AUDIO -> stringResource(R.string.type_audio)
                PushMessage.TYPE_FILE -> stringResource(R.string.type_file)
                else -> stringResource(R.string.type_message)
            }
            val typeColor = when (message.type) {
                PushMessage.TYPE_TEXT -> MaterialTheme.colorScheme.primary
                PushMessage.TYPE_IMAGE -> Green500
                PushMessage.TYPE_VIDEO -> Orange500
                PushMessage.TYPE_AUDIO -> Purple500
                else -> Grey500
            }
            val typeIcon = when (message.type) {
                PushMessage.TYPE_TEXT -> Icons.Default.TextFields
                PushMessage.TYPE_IMAGE -> Icons.Default.Image
                PushMessage.TYPE_VIDEO -> Icons.Default.Videocam
                PushMessage.TYPE_AUDIO -> Icons.Default.MusicNote
                PushMessage.TYPE_FILE -> fileTypeIcon(message.mimeType, message.fileName)
                else -> Icons.Default.InsertDriveFile
            }

            // 消息类型和时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = typeColor.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = typeColor
                        )
                        Text(
                            text = typeLabel,
                            fontSize = 12.sp,
                            color = typeColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
            if (isImage) {
                // 优先使用本地路径
                val imageSource = message.localPath
                
                if (imageSource != null) {
                    // 已下载，使用本地文件
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(android.net.Uri.parse(imageSource))
                            .crossfade(true)
                            .build(),
                        contentDescription = message.fileName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else if (isFailed) {
                    // 下载失败：显示错误态 + 重试按钮
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { onRetryDownload(message) }) {
                                Text(
                                    text = stringResource(R.string.download_failed_retry),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                } else {
                    // 下载中：Shimmer 骨架屏（Compose 内置实现，无需外部库）
                    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
                    val shimmerOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1000f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "shimmerOffset"
                    )
                    val shimmerBrush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        start = Offset(shimmerOffset - 300f, 0f),
                        end = Offset(shimmerOffset, 0f)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (downloadProgress != null) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 文件名
                Text(
                    text = message.fileName ?: "",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (message.isFileType) {
                // 文件/音频/视频：左侧文件名+大小，右侧类型图标
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = message.fileName ?: message.content ?: "",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (message.fileSize != null && message.fileSize > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatFileSize(message.fileSize),
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(typeColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = typeColor
                        )
                    }
                }
                if (message.localPath == null && !isFailed) {
                    Spacer(modifier = Modifier.height(6.dp))
                    if (downloadProgress != null) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        )
                    }
                }
            } else {
                // 纯文本内容
                Text(
                    text = message.content ?: message.fileName ?: message.fileUrl ?: "",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            }
        }
    }
}

private fun fileTypeIcon(mimeType: String?, fileName: String?): androidx.compose.ui.graphics.vector.ImageVector {
    val mime = mimeType?.lowercase().orEmpty()
    val ext  = fileName?.substringAfterLast('.')?.lowercase().orEmpty()
    return when {
        mime.contains("pdf") || ext == "pdf" ->
            Icons.Default.PictureAsPdf
        mime.contains("wordprocessing") || mime.contains("msword") || ext in setOf("doc", "docx") ->
            Icons.Default.Description
        mime.contains("spreadsheet") || mime.contains("excel") || ext in setOf("xls", "xlsx", "csv") ->
            Icons.Default.TableChart
        mime.contains("presentation") || mime.contains("powerpoint") || ext in setOf("ppt", "pptx") ->
            Icons.Default.Slideshow
        mime.contains("zip") || mime.contains("rar") || mime.contains("archive") ||
                ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2") ->
            Icons.Default.Archive
        mime == "application/vnd.android.package-archive" || ext == "apk" ->
            Icons.Default.Android
        else -> Icons.Default.InsertDriveFile
    }
}

private fun formatTimestamp(timestamp: String?): String {
    if (timestamp.isNullOrEmpty()) return ""
    return try {
        val date = timestamp.toLongOrNull()?.let { java.util.Date(it) }
            ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(timestamp)
            ?: return ""
        val now = java.util.Calendar.getInstance()
        val msgCal = java.util.Calendar.getInstance().apply { time = date }
        val isToday = now.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)
                && now.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR)
        if (isToday) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
        }
    } catch (e: Exception) {
        ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    message: PushMessage,
    isDownloading: Boolean,
    downloadProgress: Int?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopyName: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header: icon + filename + size
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val typeColor = when (message.type) {
                    PushMessage.TYPE_VIDEO -> Orange500
                    PushMessage.TYPE_AUDIO -> Purple500
                    else -> Grey500
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(typeColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fileTypeIcon(message.mimeType, message.fileName),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = typeColor
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.fileName ?: message.content ?: "",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (message.fileSize != null && message.fileSize > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatFileSize(message.fileSize),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Status indicator
            when {
                isDownloading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (downloadProgress != null) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.file_action_downloading),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                message.localPath == null -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.file_action_failed),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            // Open
            val canAct = message.localPath != null
            val disabledAlpha = 0.38f
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.file_action_open),
                        color = if (canAct) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.OpenInNew, contentDescription = null,
                        tint = if (canAct) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                    )
                },
                modifier = if (canAct) Modifier.fillMaxWidth().clickable(onClick = onOpen)
                           else Modifier.fillMaxWidth()
            )

            // Share
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.file_action_share),
                        color = if (canAct) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Share, contentDescription = null,
                        tint = if (canAct) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                    )
                },
                modifier = if (canAct) Modifier.fillMaxWidth().clickable(onClick = onShare)
                           else Modifier.fillMaxWidth()
            )

            // Copy filename
            ListItem(
                headlineContent = { Text(stringResource(R.string.file_action_copy_name)) },
                leadingContent = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCopyName)
            )
        }
    }
}

