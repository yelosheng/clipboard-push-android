package com.example.clipboardman.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.clipboardman.data.model.PeerEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        private val KEY_RECENT_PEERS = stringPreferencesKey("recent_peers")
        private val KEY_ROOM_KEY = stringPreferencesKey("room_key")
        private val KEY_PEER_LOCAL_IP = stringPreferencesKey("peer_local_ip")
        private val KEY_PEER_LOCAL_PORT = intPreferencesKey("peer_local_port")

        // 文件处理模式
        const val FILE_MODE_SAVE_LOCAL = 0          // 保存到本地
        const val FILE_MODE_COPY_REFERENCE = 1      // 仅复制引用
        const val FILE_MODE_SAVE_AND_COPY_IMAGE = 2 // 保存并复制图片到剪贴板
        const val FILE_MODE_CLIPBOARD_ONLY = 3      // 仅复制到剪贴板（不保存到本地）

        // 默认值
        private const val DEFAULT_SERVER_ADDRESS = "kxkl.tk:5055"
        private const val DEFAULT_USE_HTTPS = false
        private const val DEFAULT_FILE_HANDLE_MODE = FILE_MODE_SAVE_LOCAL
        private const val DEFAULT_AUTO_CONNECT = false
        private const val DEFAULT_MAX_HISTORY_COUNT = 100
    }

    enum class FileHandleMode(val value: Int, val label: String, val description: String) {
        SAVE_LOCAL(FILE_MODE_SAVE_LOCAL, "保存到下载文件夹", "下载的文件将保存到公共 Downloads 目录"),
        COPY_REFERENCE(FILE_MODE_COPY_REFERENCE, "复制文件引用", "文件不保存到本地，仅复制路径引用到剪贴板"),
        SAVE_AND_COPY_IMAGE(FILE_MODE_SAVE_AND_COPY_IMAGE, "保存并复制图片", "图片文件同时保存到本地并复制到剪贴板"),
        CLIPBOARD_ONLY(FILE_MODE_CLIPBOARD_ONLY, "仅复制到剪贴板", "文件不保存到本地，直接复制内容到剪贴板");

        companion object {
            fun fromValue(value: Int) =
                entries.firstOrNull { it.value == value } ?: SAVE_LOCAL
        }
    }

    private fun deserializePeers(json: String?): List<PeerEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<PeerEntry>>() {}.type
            Gson().fromJson<List<PeerEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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

    val peerLocalIpFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_PEER_LOCAL_IP]
    }

    val peerLocalPortFlow: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[KEY_PEER_LOCAL_PORT]
    }

    suspend fun savePairingInfo(server: String, room: String, key: String, localIp: String? = null, localPort: Int? = null) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SERVER_ADDRESS] = server
            preferences[KEY_ROOM_ID] = room
            preferences[KEY_ROOM_KEY] = key
            preferences[KEY_USE_HTTPS] = false // Default to HTTP for local relay
            
            if (localIp != null) preferences[KEY_PEER_LOCAL_IP] = localIp
            else preferences.remove(KEY_PEER_LOCAL_IP)
            
            if (localPort != null) preferences[KEY_PEER_LOCAL_PORT] = localPort
            else preferences.remove(KEY_PEER_LOCAL_PORT)
        }
    }

    suspend fun clearPairingInfo() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_ROOM_ID)
            preferences.remove(KEY_ROOM_KEY)
            preferences.remove(KEY_PEER_LOCAL_IP)
            preferences.remove(KEY_PEER_LOCAL_PORT)
        }
    }

    // --- Recent Peers History ---

    val recentPeersFlow: Flow<List<PeerEntry>> = context.dataStore.data.map { preferences ->
        deserializePeers(preferences[KEY_RECENT_PEERS])
    }

    suspend fun addOrUpdateRecentPeer(peer: PeerEntry) {
        context.dataStore.edit { preferences ->
            val current = deserializePeers(preferences[KEY_RECENT_PEERS])
            val updated = PeerEntry.upsert(current, peer)
            preferences[KEY_RECENT_PEERS] = Gson().toJson(updated)
        }
    }

    suspend fun removeRecentPeer(room: String) {
        context.dataStore.edit { preferences ->
            val current = deserializePeers(preferences[KEY_RECENT_PEERS])
            preferences[KEY_RECENT_PEERS] = Gson().toJson(current.filter { it.room != room })
        }
    }

    suspend fun updateRecentPeerDisplayName(room: String, name: String) {
        context.dataStore.edit { preferences ->
            val current = deserializePeers(preferences[KEY_RECENT_PEERS])
            val updated = current.map { if (it.room == room) it.copy(displayName = name) else it }
            preferences[KEY_RECENT_PEERS] = Gson().toJson(updated)
        }
    }
}
