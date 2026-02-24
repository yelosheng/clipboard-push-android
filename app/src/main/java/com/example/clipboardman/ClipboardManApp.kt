package com.example.clipboardman

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class ClipboardManApp : Application() {

    companion object {
        // 前台服务通知渠道（静默，低优先级）
        const val NOTIFICATION_CHANNEL_SERVICE = "clipboard_service"
        const val NOTIFICATION_CHANNEL_SERVICE_NAME = "剪贴板服务"

        // 推送消息通知渠道（有声音，高优先级）
        const val NOTIFICATION_CHANNEL_PUSH = "clipboard_push"
        const val NOTIFICATION_CHANNEL_PUSH_NAME = "消息推送"

        // 兼容旧代码
        const val NOTIFICATION_CHANNEL_ID = NOTIFICATION_CHANNEL_SERVICE
        const val NOTIFICATION_CHANNEL_NAME = NOTIFICATION_CHANNEL_SERVICE_NAME
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Fetch FCM token (GMS graceful degradation — failure is silent)
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    com.example.clipboardman.service.FcmTokenHolder.token = token
                    android.util.Log.d("ClipboardManApp", "FCM token ready")
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("ClipboardManApp", "FCM token unavailable: ${e.message}")
                }
        } catch (e: Exception) {
            android.util.Log.w("ClipboardManApp", "FCM not available on this device: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 前台服务通知渠道（静默）
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                NOTIFICATION_CHANNEL_SERVICE_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持剪贴板推送服务运行"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 推送消息通知渠道（有声音和弹出）
            val pushChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PUSH,
                NOTIFICATION_CHANNEL_PUSH_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收剪贴板推送消息时的通知"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                // 设置默认通知声音
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            notificationManager.createNotificationChannel(pushChannel)
        }
    }
}
