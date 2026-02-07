package com.example.clipboardman.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.data.repository.MessageRepository
import com.example.clipboardman.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val messageRepository = MessageRepository(application)

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 消息列表
    private val _messages = MutableStateFlow<List<PushMessage>>(emptyList())
    val messages: StateFlow<List<PushMessage>> = _messages.asStateFlow()

    init {
        com.example.clipboardman.util.DebugLogger.log("ViewModel", "INIT - ViewModel Created!")
        
        // Auto-Migration for legacy defaults
        viewModelScope.launch {
            try {
                val currentAddress = settingsRepository.serverAddressFlow.first()
                
                // 如果是 "localhost" (模拟器/旧缓存) 或者空或者包含端口 5000 (旧默认)
                if (currentAddress.contains("localhost") || currentAddress.contains("5000")) {
                    com.example.clipboardman.util.DebugLogger.log("ViewModel", "Auto-Migrating config: $currentAddress -> kxkl.tk:5055")
                    settingsRepository.saveServerAddress("kxkl.tk:5055")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error in auto-migration", e)
            }
        }
        // 启动时加载本地存储的消息
        loadMessagesFromStorage()
    }

    private fun loadMessagesFromStorage() {
        viewModelScope.launch {
            try {
                var firstEmission = true
                // 持续观察 MessageRepository 的变化（包括 localPath 更新）
                messageRepository.messagesFlow.collect { storedMessages ->
                    val maxCount = maxHistoryCount.value
                    val result = storedMessages.take(maxCount)
                    
                    com.example.clipboardman.util.DebugLogger.log(
                        "ViewModel", 
                        "Flow emit: stored=${storedMessages.size}, maxCount=$maxCount, result=${result.size}, first=$firstEmission"
                    )
                    
                    // 只有当结果非空，或者这不是第一次发射时才更新
                    // 这避免了 DataStore 初始化时可能的空发射覆盖真实数据
                    if (result.isNotEmpty() || !firstEmission) {
                        _messages.value = result
                    }
                    firstEmission = false
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading messages from storage", e)
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

    /**
     * 更新连接状态 (由 Service 调用)
     */
    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
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
}
