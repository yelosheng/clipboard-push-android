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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer

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

    // Push animation triggers
    var pushTrigger     by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var pushFailTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }

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
                // 正常模式的 TopAppBar — Phone→Cloud→PC indicator
                CenterAlignedTopAppBar(
                    title = {
                        ConnectionIndicator(
                            connectionState  = connectionState,
                            peerCount        = peerCount,
                            peers            = peers,
                            onPushClick      = {
                                onPushClipboard()
                                pushTrigger++   // trigger dot animation immediately on tap
                            },
                            pushTrigger      = pushTrigger,
                            pushFailTrigger  = pushFailTrigger
                        )
                    },
                    actions = {
                        // Settings button only (Send moved to phone icon tap)
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
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

@Composable
private fun ConnectionIndicator(
    connectionState: ConnectionState,
    peerCount: Int,
    peers: List<String>,
    onPushClick: () -> Unit,
    modifier: Modifier = Modifier,
    pushTrigger: Int = 0,       // increment to trigger success animation
    pushFailTrigger: Int = 0    // increment to trigger fail animation
) {
    val isConnectedWithPeer = connectionState == ConnectionState.CONNECTED && peerCount > 0
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    val cloudColor by animateColorAsState(
        targetValue = when {
            connectionState == ConnectionState.CONNECTED -> Green500
            connectionState == ConnectionState.ERROR -> Red500
            else -> Grey500
        },
        animationSpec = tween(300),
        label = "cloudColor"
    )
    val lineLeftColor by animateColorAsState(
        targetValue = when {
            connectionState == ConnectionState.CONNECTED -> Green500
            connectionState == ConnectionState.ERROR -> Red500
            else -> Grey500
        },
        animationSpec = tween(300),
        label = "lineLeftColor"
    )
    val lineRightColor by animateColorAsState(
        targetValue = when {
            isConnectedWithPeer -> Green500
            connectionState == ConnectionState.ERROR -> Red500
            else -> Grey500
        },
        animationSpec = tween(300),
        label = "lineRightColor"
    )
    val pcAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            isConnectedWithPeer -> 1f
            connectionState == ConnectionState.CONNECTED -> 0.4f
            else -> 0.5f
        },
        animationSpec = tween(300),
        label = "pcAlpha"
    )
    val phoneAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (connectionState == ConnectionState.DISCONNECTED) 0.5f else 1f,
        animationSpec = tween(300),
        label = "phoneAlpha"
    )

    val leftDashed  = connectionState != ConnectionState.CONNECTED
    val rightDashed = !isConnectedWithPeer

    val pcName = peers.firstOrNull() ?: ""

    // Push success dot animation: 0f = at phone, 1f = at PC
    val dotProgress = remember { Animatable(0f) }
    var showDot by remember { mutableStateOf(false) }

    LaunchedEffect(pushTrigger) {
        if (pushTrigger > 0) {
            showDot = true
            dotProgress.snapTo(0f)
            dotProgress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
            showDot = false
        }
    }

    // Failure shake animation
    val shakeOffset = remember { Animatable(0f) }
    var cloudTintOverride: androidx.compose.ui.graphics.Color? by remember { mutableStateOf(null) }

    LaunchedEffect(pushFailTrigger) {
        if (pushFailTrigger > 0) {
            cloudTintOverride = Red500
            repeat(3) {
                shakeOffset.animateTo(4f, tween(50))
                shakeOffset.animateTo(-4f, tween(50))
            }
            shakeOffset.animateTo(0f, tween(50))
            cloudTintOverride = null
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 8.dp)
                    .graphicsLayer { translationX = shakeOffset.value.dp.toPx() }
            ) {
                // Phone icon — tap to push, circle background as tap hint
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = true)
                        .background(
                            color = onPrimary.copy(alpha = if (isConnectedWithPeer) 0.22f else 0.12f),
                            shape = CircleShape
                        )
                        .then(
                            if (isConnectedWithPeer)
                                Modifier.clickable(onClick = onPushClick)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Smartphone,
                        contentDescription = if (isConnectedWithPeer)
                            stringResource(R.string.cd_push_phone_icon)
                        else
                            null,
                        tint = onPrimary.copy(alpha = phoneAlpha),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Left line — flexible, fills available space
                IndicatorLine(
                    color = lineLeftColor.copy(alpha = if (leftDashed) 0.5f else 1f),
                    dashed = leftDashed,
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                )

                // Cloud icon — circle background + shadow
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = true)
                        .background(onPrimary.copy(alpha = 0.12f), CircleShape)
                ) {
                    if (connectionState == ConnectionState.CONNECTING) {
                        val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
                        val rotationAngle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "rotation"
                        )
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer { rotationZ = rotationAngle }
                        )
                    } else {
                        val cloudIcon = when (connectionState) {
                            ConnectionState.DISCONNECTED -> Icons.Default.CloudOff
                            ConnectionState.ERROR        -> Icons.Default.Warning
                            else                         -> Icons.Default.Cloud
                        }
                        Icon(
                            imageVector = cloudIcon,
                            contentDescription = null,
                            tint = cloudColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Right line — flexible, fills available space
                IndicatorLine(
                    color = lineRightColor.copy(alpha = if (rightDashed) 0.5f else 1f),
                    dashed = rightDashed,
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                )

                // PC icon — circle background + shadow; slash overlay when PC is offline
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = true)
                        .background(onPrimary.copy(alpha = 0.12f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.DesktopWindows,
                        contentDescription = null,
                        tint = onPrimary.copy(alpha = pcAlpha),
                        modifier = Modifier.size(24.dp)
                    )
                    // Slash through PC icon when connected to server but PC is not online
                    if (connectionState == ConnectionState.CONNECTED && peerCount == 0) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            drawLine(
                                color = onPrimary.copy(alpha = 0.9f),
                                start = Offset(size.width * 0.1f, size.height * 0.9f),
                                end   = Offset(size.width * 0.9f, size.height * 0.1f),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            // PC name — right-aligned under PC icon
            Box(modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = pcName,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "pcName",
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) { name ->
                    if (name.isNotEmpty()) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = onPrimary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Spacer(modifier = Modifier.height(MaterialTheme.typography.labelSmall.lineHeight.value.dp))
                    }
                }
            }
        }

        // Dot overlay — travels from phone center to PC center (accounts for 8dp side padding)
        if (showDot) {
            val dotX: androidx.compose.ui.unit.Dp = 26.dp + (maxWidth - 52.dp) * dotProgress.value
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .align(Alignment.TopStart)
            ) {
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = 4.dp.toPx(),
                    center = Offset(dotX.toPx(), size.height / 2)
                )
            }
        }
    }
}

@Composable
private fun IndicatorLine(
    color: androidx.compose.ui.graphics.Color,
    dashed: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokePx = 1.5.dp.toPx()
        val dashEffect = if (dashed)
            PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
        else null
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end   = Offset(size.width, size.height / 2),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
            pathEffect = dashEffect
        )
    }
}

