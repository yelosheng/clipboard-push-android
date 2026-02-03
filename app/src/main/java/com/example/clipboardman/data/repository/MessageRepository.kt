package com.example.clipboardman.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.clipboardman.data.model.PushMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
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
    val messagesFlow: Flow<List<PushMessage>> = context.messageDataStore.data.map { preferences ->
        val json = preferences[KEY_MESSAGES] ?: "[]"
        try {
            val type = object : TypeToken<List<PushMessage>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存消息列表
     */
    suspend fun saveMessages(messages: List<PushMessage>) {
        context.messageDataStore.edit { preferences ->
            val trimmed = messages.take(MAX_MESSAGES)
            preferences[KEY_MESSAGES] = gson.toJson(trimmed)
        }
    }

    /**
     * 添加新消息并保存
     */
    suspend fun addMessage(message: PushMessage, currentMessages: List<PushMessage>): List<PushMessage> {
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
}
