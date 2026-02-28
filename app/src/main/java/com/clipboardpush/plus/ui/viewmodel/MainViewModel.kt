package com.clipboardpush.plus.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipboardpush.plus.data.model.ConnectionState
import com.clipboardpush.plus.data.model.PeerEntry
import com.clipboardpush.plus.data.model.PushMessage
import com.clipboardpush.plus.data.repository.MessageRepository
import com.clipboardpush.plus.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val messageRepository = MessageRepository(application)

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Active Peer Count (other devices only, self filtered out)
    // 0 = no other devices online, >0 = at least one peer available for push
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    
    // Convenience: true if at least one peer is online
    val hasPeers: StateFlow<Boolean> = peerCount.map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    // 消息列表
    private val _messages = MutableStateFlow<List<PushMessage>>(emptyList())
    val messages: StateFlow<List<PushMessage>> = _messages.asStateFlow()

    // 下载失败的消息 ID 集合（用于在消息卡片显示错误态和重试按钮）
    private val _failedDownloadIds = MutableStateFlow<Set<String>>(emptySet())
    val failedDownloadIds: StateFlow<Set<String>> = _failedDownloadIds.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    fun updateDownloadProgress(messageId: String, progress: Int) {
        _downloadProgress.update { it + (messageId to progress) }
    }

    fun clearDownloadProgress(messageId: String) {
        _downloadProgress.update { it - messageId }
    }

    fun markDownloadFailed(messageId: String) {
        _failedDownloadIds.update { it + messageId }
        clearDownloadProgress(messageId)
    }

    fun markDownloadRetrying(messageId: String) {
        _failedDownloadIds.update { it - messageId }
    }



    private fun loadMessagesFromStorage() {
        viewModelScope.launch {
            messageRepository.messagesFlow
                .retry(3) { _ ->
                    kotlinx.coroutines.delay(1_000)
                    true
                }
                .catch { e ->
                    android.util.Log.e("MainViewModel", "messagesFlow failed after retries, clearing", e)
                    viewModelScope.launch { messageRepository.clearMessages() }
                    emit(emptyList())
                }
                .collect { storedMessages ->
                    val maxCount = maxHistoryCount.value
                    _messages.value = storedMessages.take(maxCount)
                }
        }
    }

    // 设置
    val serverAddress: StateFlow<String> = settingsRepository.serverAddressFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val useHttps: StateFlow<Boolean> = settingsRepository.useHttpsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val fileHandleMode: StateFlow<Int> = settingsRepository.fileHandleModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.FILE_MODE_COPY_REFERENCE)

    val autoConnect: StateFlow<Boolean> = settingsRepository.autoConnectFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val maxHistoryCount: StateFlow<Int> = settingsRepository.maxHistoryCountFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 100)

    val recentPeers: StateFlow<List<PeerEntry>> = settingsRepository.recentPeersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeRoomId: StateFlow<String?> = settingsRepository.roomIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val fileUploadActive: StateFlow<Boolean> = com.clipboardpush.plus.service.ClipboardService.fileUploadActive
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 更新连接状态 (由 Service 调用)
     */
    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun updatePeerCount(count: Int) {
        _peerCount.value = count
    }

    fun updatePeers(peers: List<String>) {
        _peers.value = peers
    }

    /**
     * 添加新消息 (由 Service 调用)
     */
    fun addMessage(message: PushMessage) {
        // 检查是否已存在相同ID的消息，避免重复
        if (_messages.value.any { it.safeId == message.safeId }) {
            return
        }
        val maxCount = maxHistoryCount.value
        _messages.value = listOf(message) + _messages.value.take(maxCount - 1)
    }

    /**
     * 同步消息列表（从 Service 内存同步）
     * Service 的消息列表已按最新在前排序，直接作为数据源
     */
    fun syncMessages(messages: List<PushMessage>) {
        val maxCount = maxHistoryCount.value
        _messages.value = messages
            .distinctBy { it.safeId }
            .take(maxCount)
    }

    /**
     * 清空消息列表
     */
    fun clearMessages() {
        _messages.value = emptyList()
        viewModelScope.launch {
            messageRepository.clearMessages()
        }
    }

    /**
     * 删除指定消息
     */
    fun deleteMessages(messageIds: Set<String>) {
        _messages.value = _messages.value.filter { it.safeId !in messageIds }
        viewModelScope.launch {
            messageRepository.saveMessages(_messages.value)
        }
    }

    /**
     * 保存服务器地址
     */
    fun saveServerAddress(address: String) {
        viewModelScope.launch {
            settingsRepository.saveServerAddress(address)
        }
    }

    /**
     * 保存是否使用 HTTPS
     */
    fun saveUseHttps(useHttps: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveUseHttps(useHttps)
        }
    }

    /**
     * 保存文件处理模式
     */
    fun saveFileHandleMode(mode: Int) {
        viewModelScope.launch {
            settingsRepository.saveFileHandleMode(mode)
        }
    }

    /**
     * 保存自动连接设置
     */
    fun saveAutoConnect(autoConnect: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveAutoConnect(autoConnect)
        }
    }

    /**
     * 保存最大历史消息数量
     */
    fun saveMaxHistoryCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.saveMaxHistoryCount(count)
        }
    }

    fun addOrUpdateRecentPeer(peer: PeerEntry) {
        viewModelScope.launch {
            settingsRepository.addOrUpdateRecentPeer(peer)
        }
    }

    fun removeRecentPeer(room: String) {
        viewModelScope.launch {
            settingsRepository.removeRecentPeer(room)
        }
    }

    fun updateRecentPeerDisplayName(room: String, name: String) {
        viewModelScope.launch {
            settingsRepository.updateRecentPeerDisplayName(room, name)
        }
    }

    init {
        
        // Auto-Migration for legacy defaults
        viewModelScope.launch {
            try {
                val currentAddress = settingsRepository.serverAddressFlow.first()
                if (currentAddress.contains("localhost") || currentAddress.contains("5000")) {
                    settingsRepository.saveServerAddress("kxkl.tk:5055")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error in auto-migration", e)
            }
        }
        // 启动时加载本地存储的消息
        loadMessagesFromStorage()

        // 如果已有配对信息但 recent_peers 里没有该条目，自动补录（兼容旧版 APK 升级场景）
        viewModelScope.launch {
            try {
                val roomId = settingsRepository.roomIdFlow.first() ?: return@launch
                val key = settingsRepository.roomKeyFlow.first() ?: return@launch
                if (roomId.isBlank() || key.isBlank()) return@launch
                val existing = settingsRepository.recentPeersFlow.first()
                if (existing.none { it.room == roomId }) {
                    val server = settingsRepository.serverAddressFlow.first()
                    val localIp = settingsRepository.peerLocalIpFlow.first()
                    val localPort = settingsRepository.peerLocalPortFlow.first()
                    settingsRepository.addOrUpdateRecentPeer(
                        com.clipboardpush.plus.data.model.PeerEntry(
                            room = roomId,
                            server = server,
                            key = key,
                            localIp = localIp,
                            localPort = localPort,
                            displayName = "PC (${roomId.takeLast(8)})",
                            lastConnectedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error in recent-peers migration", e)
            }
        }
    }
}
