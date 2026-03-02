package com.clipboardpush.plus.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.clipboardpush.plus.R
import com.clipboardpush.plus.data.model.ConnectionState
import com.clipboardpush.plus.data.model.PeerEntry
import com.clipboardpush.plus.data.repository.SettingsRepository
import com.clipboardpush.plus.ui.theme.*
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
    onBackClick: () -> Unit,
    peers: List<String> = emptyList(),
    recentPeers: List<PeerEntry> = emptyList(),
    activeRoomId: String? = null,
    onPeerSelected: (PeerEntry) -> Unit = {},
    onPeerRemoved: (PeerEntry) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
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
            SettingsSection(title = stringResource(R.string.section_connection)) {
                Column {
                    // 1. 连接状态和控制
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Layer 1: server connection status
                        val serverStatusText = when (connectionState) {
                            ConnectionState.CONNECTED -> stringResource(R.string.state_server_connected)
                            ConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
                            ConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
                            ConnectionState.ERROR -> stringResource(R.string.state_error)
                        }
                        val serverStatusColor = when (connectionState) {
                            ConnectionState.CONNECTED -> {
                                if (peers.isNotEmpty()) Green500
                                else androidx.compose.ui.graphics.Color(0xFFFFC107)
                            }
                            ConnectionState.CONNECTING -> Orange500
                            ConnectionState.ERROR -> Red500
                            ConnectionState.DISCONNECTED -> Grey500
                        }

                        // Layer 2: PC peer sub-text (only shown when connected to server)
                        val pcSubText: String? = when {
                            connectionState != ConnectionState.CONNECTED -> null
                            peers.isNotEmpty() -> stringResource(R.string.target_device, peers.joinToString(", "))
                            else -> stringResource(R.string.state_pc_offline)
                        }
                        val pcSubColor = if (peers.isNotEmpty()) Green500
                                         else androidx.compose.ui.graphics.Color(0xFFFFC107)

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.status_label, serverStatusText),
                                style = MaterialTheme.typography.bodyMedium,
                                color = serverStatusColor
                            )
                            if (pcSubText != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = pcSubText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = pcSubColor
                                )
                            }
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
                            Text(
                                if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING)
                                    stringResource(R.string.btn_disconnect)
                                else
                                    stringResource(R.string.btn_connect)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // 2. 配对按钮
                    Button(
                        onClick = onScanClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_scan_pair))
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.guide_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            val guideStep1Prefix = stringResource(R.string.guide_step1_prefix)
                            val guideStep1Suffix = stringResource(R.string.guide_step1_suffix)
                            val guideStep2 = stringResource(R.string.guide_step2)
                            val guideStep3 = stringResource(R.string.guide_step3)
                            val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                                append(guideStep1Prefix)
                                pushStringAnnotation(tag = "URL", annotation = "https://www.clipboardpush.com/")
                                withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                                    append("https://www.clipboardpush.com/")
                                }
                                pop()
                                append("$guideStep1Suffix\n")
                                append("$guideStep2\n")
                                append(guideStep3)
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

            // 最近连接
            if (recentPeers.isNotEmpty()) {
                SettingsSection(title = stringResource(R.string.section_recent)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recentPeers.forEach { entry ->
                            val isCurrentRoom = entry.room == activeRoomId
                            val isPeerOnline = isCurrentRoom && peers.any { it == entry.displayName }
                            RecentPeerRow(
                                entry = entry,
                                isCurrentRoom = isCurrentRoom,
                                isPeerOnline = isPeerOnline,
                                onSelect = { onPeerSelected(entry) },
                                onRemove = { onPeerRemoved(entry) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 文件处理方式
            SettingsSection(title = stringResource(R.string.section_file_handling)) {
                Column {
                    SettingsRepository.FileHandleMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        RadioButtonOption(
                            text = stringResource(mode.labelRes),
                            description = stringResource(mode.descRes),
                            selected = fileHandleMode == mode.value,
                            onClick = { onFileHandleModeChange(mode.value) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 历史记录设置
            SettingsSection(title = stringResource(R.string.section_history)) {
                Column {
                    Text(
                        text = stringResource(R.string.history_max_count),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.history_max_count_desc),
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
            SettingsSection(title = stringResource(R.string.section_other)) {
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
                                text = stringResource(R.string.auto_connect_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.auto_connect_desc),
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
                                        android.widget.Toast.makeText(context, context.getString(R.string.battery_opt_toast_error), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.battery_opt_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (isIgnoringBattery) stringResource(R.string.battery_opt_enabled)
                                       else stringResource(R.string.battery_opt_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnoringBattery) Green500 else Orange500
                            )
                        }
                        Text(
                            text = stringResource(R.string.battery_opt_action),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 国产手机额外提示
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.battery_warning_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.battery_warning_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 关于
            SettingsSection(title = stringResource(R.string.section_about)) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.about_version),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "v${com.clipboardpush.plus.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("https://clipboardpush.com/privacy") }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.about_privacy),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "→",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("https://www.clipboardpush.com/") }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.about_website),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.about_website_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("https://github.com/yelosheng/clipboard-push-android") }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.about_github),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecentPeerRow(
    entry: PeerEntry,
    isCurrentRoom: Boolean,
    isPeerOnline: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.peer_delete_title)) },
            text = { Text(stringResource(R.string.peer_delete_confirm, entry.displayName)) },
            confirmButton = {
                TextButton(onClick = { onRemove(); showDeleteDialog = false }) {
                    Text(stringResource(R.string.peer_delete_confirm_btn), color = Red500)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Red500, RoundedCornerShape(8.dp))
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = Color.White
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = { showDeleteDialog = true }
                ),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isCurrentRoom && isPeerOnline)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = entry.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isCurrentRoom) {
                            if (isPeerOnline) {
                                Surface(
                                    color = Green500,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.peer_active_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    color = Grey500,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.peer_offline_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.peer_last_connected, formatRelativeTime(entry.lastConnectedAt, context)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRelativeTime(epochMs: Long, context: android.content.Context): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L -> context.getString(R.string.time_just_now)
        diff < 3_600_000L -> context.getString(R.string.time_minutes_ago, diff / 60_000)
        diff < 86_400_000L -> context.getString(R.string.time_hours_ago, diff / 3_600_000)
        else -> context.getString(R.string.time_days_ago, diff / 86_400_000)
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
