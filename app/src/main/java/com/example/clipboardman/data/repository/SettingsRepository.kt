package com.example.clipboardman.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore 实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置存储仓库
 * 使用 DataStore 持久化用户设置
 */
class SettingsRepository(private val context: Context) {

    companion object {
        // 设置键
        private val KEY_SERVER_ADDRESS = stringPreferencesKey("server_address")
        private val KEY_FILE_HANDLE_MODE = intPreferencesKey("file_handle_mode")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")

        // 文件处理模式
        const val FILE_MODE_SAVE_LOCAL = 0
        const val FILE_MODE_COPY_REFERENCE = 1

        // 默认值
        private const val DEFAULT_SERVER_ADDRESS = "192.168.1.100:9661"
        private const val DEFAULT_FILE_HANDLE_MODE = FILE_MODE_COPY_REFERENCE
        private const val DEFAULT_AUTO_CONNECT = false
    }

    /**
     * 服务器地址 Flow
     */
    val serverAddressFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_SERVER_ADDRESS] ?: DEFAULT_SERVER_ADDRESS
    }

    /**
     * 文件处理模式 Flow
     */
    val fileHandleModeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_FILE_HANDLE_MODE] ?: DEFAULT_FILE_HANDLE_MODE
    }

    /**
     * 自动连接 Flow
     */
    val autoConnectFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_CONNECT] ?: DEFAULT_AUTO_CONNECT
    }

    /**
     * 保存服务器地址
     */
    suspend fun saveServerAddress(address: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SERVER_ADDRESS] = address
        }
    }

    /**
     * 保存文件处理模式
     */
    suspend fun saveFileHandleMode(mode: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FILE_HANDLE_MODE] = mode
        }
    }

    /**
     * 保存自动连接设置
     */
    suspend fun saveAutoConnect(autoConnect: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_CONNECT] = autoConnect
        }
    }

    /**
     * 获取完整的 WebSocket URL
     */
    fun getWebSocketUrl(serverAddress: String): String {
        val address = serverAddress.trim()
        return if (address.startsWith("ws://") || address.startsWith("wss://")) {
            "$address/ws"
        } else {
            "ws://$address/ws"
        }
    }

    /**
     * 获取 HTTP 基础 URL
     */
    fun getHttpBaseUrl(serverAddress: String): String {
        val address = serverAddress.trim()
        return if (address.startsWith("http://") || address.startsWith("https://")) {
            address
        } else {
            "http://$address"
        }
    }
}
