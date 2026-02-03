package com.example.clipboardman.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.clipboardman.data.model.ConnectionState
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 消息列表
    private val _messages = MutableStateFlow<List<PushMessage>>(emptyList())
    val messages: StateFlow<List<PushMessage>> = _messages.asStateFlow()

    // 设置
    val serverAddress: StateFlow<String> = settingsRepository.serverAddressFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val fileHandleMode: StateFlow<Int> = settingsRepository.fileHandleModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.FILE_MODE_COPY_REFERENCE)

    val autoConnect: StateFlow<Boolean> = settingsRepository.autoConnectFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        _messages.value = listOf(message) + _messages.value.take(99) // 最多保留100条
    }

    /**
     * 同步消息列表（从 Service 获取历史消息）
     */
    fun syncMessages(messages: List<PushMessage>) {
        _messages.value = messages.take(100)
    }

    /**
     * 清空消息列表
     */
    fun clearMessages() {
        _messages.value = emptyList()
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
}
