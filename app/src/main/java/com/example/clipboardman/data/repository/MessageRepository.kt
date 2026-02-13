package com.example.clipboardman.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.clipboardman.data.model.PushMessage
import com.example.clipboardman.util.DebugLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.messageDataStore: DataStore<Preferences> by preferencesDataStore(name = "messages")

/**
 * 消息持久化仓库
 */
class MessageRepository(private val context: Context) {

    companion object {
        private val KEY_MESSAGES = stringPreferencesKey("message_history")
        private const val MAX_MESSAGES = 100
    }

    private val gson = Gson()

    /**
     * 消息列表 Flow
     */
    val messagesFlow: Flow<List<PushMessage>> = context.messageDataStore.data
        .catch { exception ->
            DebugLogger.log("MsgRepo", "CRITICAL ERROR: DataStore read failed: ${exception::class.simpleName} - ${exception.message}")
            if (exception is java.io.IOException) {
                DebugLogger.log("MsgRepo", "Recovering from IOException by emitting empty preferences")
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                DebugLogger.log("MsgRepo", "Re-throwing non-IOException")
                throw exception
            }
        }
        .map { preferences ->
        val json = preferences[KEY_MESSAGES] ?: "[]"
        DebugLogger.log("MsgRepo", "Raw JSON from DataStore: ${json.take(50)}...")
        try {
            val type = object : TypeToken<List<PushMessage>>() {}.type
            val messages: List<PushMessage> = gson.fromJson(json, type) ?: emptyList()
            DebugLogger.log("MsgRepo", "Structurally parsed ${messages.size} messages")
            messages
        } catch (e: Exception) {
            DebugLogger.log("MsgRepo", "JSON Parse Error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 保存消息列表
     */
    suspend fun saveMessages(messages: List<PushMessage>) {
        DebugLogger.log("MsgRepo", "Saving ${messages.size} messages...")
        context.messageDataStore.edit { preferences ->
            val trimmed = messages.take(MAX_MESSAGES)
            val json = gson.toJson(trimmed)
            preferences[KEY_MESSAGES] = json
            DebugLogger.log("MsgRepo", "✓ Saved ${trimmed.size} messages (${json.length} chars)")
        }
    }

    /**
     * 原子性添加新消息 - 在 edit 块中读取当前值并添加
     * 避免 first() 可能返回空值的竞争条件
     */
    suspend fun addMessageAtomic(message: PushMessage) {
        DebugLogger.log("MsgRepo", "addMessageAtomic id=${message.id}")
        context.messageDataStore.edit { preferences ->
            val json = preferences[KEY_MESSAGES] ?: "[]"
            val type = object : TypeToken<MutableList<PushMessage>>() {}.type
            val currentMessages: MutableList<PushMessage> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
            
            // Deduplication Check
            val exists = currentMessages.any { 
                (it.id != null && it.id == message.id) || 
                (it.safeId == message.safeId)
            }
            
            if (exists) {
                DebugLogger.log("MsgRepo", "Duplicate message ignored: ${message.id}")
                return@edit
            }
            
            DebugLogger.log("MsgRepo", "Current: ${currentMessages.size} msgs, adding new...")
            
            // 添加到开头
            currentMessages.add(0, message)
            
            // 限制最大数量
            val trimmed = currentMessages.take(MAX_MESSAGES)
            val newJson = gson.toJson(trimmed)
            preferences[KEY_MESSAGES] = newJson
            
            DebugLogger.log("MsgRepo", "✓ Atomic saved ${trimmed.size} msgs")
        }
    }

    /**
     * 添加新消息并保存 (旧方法，保留兼容)
     */
    suspend fun addMessage(message: PushMessage, currentMessages: List<PushMessage>): List<PushMessage> {
        DebugLogger.log("MsgRepo", "Adding message id=${message.id}")
        val updated = listOf(message) + currentMessages.take(MAX_MESSAGES - 1)
        saveMessages(updated)
        return updated
    }

    /**
     * 清空消息
     */
    suspend fun clearMessages() {
        context.messageDataStore.edit { preferences ->
            preferences[KEY_MESSAGES] = "[]"
        }
    }

    /**
     * 更新消息的本地路径
     */
    suspend fun updateMessageLocalPath(messageId: String, localPath: String) {
        DebugLogger.log("MsgRepo", "updateLocalPath: id=$messageId")
        context.messageDataStore.edit { preferences ->
            val json = preferences[KEY_MESSAGES] ?: "[]"
            try {
                val type = object : TypeToken<MutableList<PushMessage>>() {}.type
                val messages: MutableList<PushMessage> = gson.fromJson(json, type) ?: mutableListOf()
                
                DebugLogger.log("MsgRepo", "Found ${messages.size} msgs, looking for $messageId")
                
                // 找到对应消息并更新
                val index = messages.indexOfFirst { it.id == messageId || it.safeId == messageId }
                DebugLogger.log("MsgRepo", "Index=$index")
                
                if (index >= 0) {
                    val oldMsg = messages[index]
                    messages[index] = oldMsg.copy(localPath = localPath)
                    preferences[KEY_MESSAGES] = gson.toJson(messages)
                    DebugLogger.log("MsgRepo", "✓ Updated localPath!")
                } else {
                    DebugLogger.log("MsgRepo", "✗ Not found! IDs: ${messages.mapNotNull { it.id }.take(3)}")
                }
            } catch (e: Exception) {
                DebugLogger.log("MsgRepo", "Error: ${e.message}")
            }
        }
    }
}
