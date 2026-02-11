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
        private val KEY_USE_HTTPS = booleanPreferencesKey("use_https")
        private val KEY_FILE_HANDLE_MODE = intPreferencesKey("file_handle_mode")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_MAX_HISTORY_COUNT = intPreferencesKey("max_history_count")
        
        // Pairing Info
        private val KEY_ROOM_ID = stringPreferencesKey("room_id")
        private val KEY_ROOM_KEY = stringPreferencesKey("room_key")

        // 文件处理模式
        const val FILE_MODE_SAVE_LOCAL = 0          // 保存到本地
        const val FILE_MODE_COPY_REFERENCE = 1      // 仅复制引用
        const val FILE_MODE_SAVE_AND_COPY_IMAGE = 2 // 保存并复制图片到剪贴板

        // 默认值
        private const val DEFAULT_SERVER_ADDRESS = "kxkl.tk:5055"
        private const val DEFAULT_USE_HTTPS = false
        private const val DEFAULT_FILE_HANDLE_MODE = FILE_MODE_SAVE_LOCAL // Changed from COPY_REFERENCE
        private const val DEFAULT_AUTO_CONNECT = false
        private const val DEFAULT_MAX_HISTORY_COUNT = 100
    }

    /**
     * 服务器地址 Flow
     */
    val serverAddressFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_SERVER_ADDRESS] ?: DEFAULT_SERVER_ADDRESS
    }

    /**
     * 是否使用 HTTPS Flow
     */
    val useHttpsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_USE_HTTPS] ?: DEFAULT_USE_HTTPS
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
     * 最大历史消息数量 Flow
     */
    val maxHistoryCountFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_MAX_HISTORY_COUNT] ?: DEFAULT_MAX_HISTORY_COUNT
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
     * 保存是否使用 HTTPS
     */
    suspend fun saveUseHttps(useHttps: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_USE_HTTPS] = useHttps
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
     * 保存最大历史消息数量
     */
    suspend fun saveMaxHistoryCount(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MAX_HISTORY_COUNT] = count
        }
    }

    /**
     * 获取完整的 WebSocket URL
     */
    fun getWebSocketUrl(serverAddress: String, useHttps: Boolean): String {
        val address = serverAddress.trim()
        val protocol = if (useHttps) "wss" else "ws"
        return if (address.startsWith("ws://") || address.startsWith("wss://")) {
            "$address/ws"
        } else {
            "$protocol://$address/ws"
        }
    }

    fun getHttpBaseUrl(serverAddress: String, useHttps: Boolean): String {
        val address = serverAddress.trim()
        val protocol = if (useHttps) "https" else "http"
        return if (address.startsWith("http://") || address.startsWith("https://")) {
            address
        } else {
            "$protocol://$address"
        }
    }

    // --- Pairing Info ---

    val roomIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_ROOM_ID]
    }

    val roomKeyFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_ROOM_KEY]
    }

    suspend fun savePairingInfo(server: String, room: String, key: String) {
        com.example.clipboardman.util.DebugLogger.log("SettingsRepo", "Saving - Server: $server Room: $room")
        context.dataStore.edit { preferences ->
            preferences[KEY_SERVER_ADDRESS] = server
            preferences[KEY_ROOM_ID] = room
            preferences[KEY_ROOM_KEY] = key
            preferences[KEY_USE_HTTPS] = false // Default to HTTP for local relay
        }
    }

    suspend fun clearPairingInfo() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_ROOM_ID)
            preferences.remove(KEY_ROOM_KEY)
        }
    }
}
