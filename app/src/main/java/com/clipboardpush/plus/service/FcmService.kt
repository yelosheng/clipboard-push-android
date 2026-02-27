package com.clipboardpush.plus.service

import android.util.Log
import com.clipboardpush.plus.R
import com.clipboardpush.plus.data.model.PushMessage
import com.clipboardpush.plus.data.repository.MessageRepository
import com.clipboardpush.plus.data.repository.SettingsRepository
import com.clipboardpush.plus.util.CryptoManager
import com.clipboardpush.plus.util.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object FcmTokenHolder {
    @Volatile
    var token: String? = null
}

class FcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmService"
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed")
        FcmTokenHolder.token = token
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        Log.d(TAG, "FCM message received: $data")

        val type = data["type"] ?: return
        if (type != "clipboard_push") return

        var content = data["content"] ?: return
        val isEncrypted = data["encrypted"] == "true"
        val timestamp = data["timestamp"] ?: System.currentTimeMillis().toString()
        val id = data["id"] ?: timestamp

        if (isEncrypted) {
            val roomKey = runBlocking {
                try {
                    SettingsRepository(applicationContext).roomKeyFlow.first()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read room key", e)
                    null
                }
            }

            if (roomKey.isNullOrBlank()) {
                Log.w(TAG, "FCM: received encrypted message but no room key configured")
                return
            }

            try {
                val manager = CryptoManager(roomKey)
                val encryptedBytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                val decryptedBytes = manager.decrypt(encryptedBytes) ?: run {
                    Log.e(TAG, "FCM: decryption returned null")
                    return
                }
                content = String(decryptedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "FCM: decryption error", e)
                return
            }
        }

        if (content.isEmpty()) return

        val message = PushMessage(
            id = id,
            type = PushMessage.TYPE_TEXT,
            content = content,
            timestamp = timestamp
        )

        runBlocking {
            try {
                MessageRepository(applicationContext).addMessageAtomic(message)
            } catch (e: Exception) {
                Log.e(TAG, "FCM: failed to save message", e)
            }
        }

        ClipboardHelper(applicationContext).copyText(content)

        NotificationHelper.showPushNotification(
            context = applicationContext,
            title = getString(R.string.fcm_received_clipboard_title),
            content = if (content.length > 50) content.take(50) + "…" else content
        )
    }
}
