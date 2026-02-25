package com.clipboardpush.plus

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

        // 推送消息通知渠道（有声音，高优先级）
        const val NOTIFICATION_CHANNEL_PUSH = "clipboard_push"

        // 兼容旧代码
        const val NOTIFICATION_CHANNEL_ID = NOTIFICATION_CHANNEL_SERVICE
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 前台服务通知渠道（静默）
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                getString(R.string.notif_channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 推送消息通知渠道（有声音和弹出）
            val pushChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PUSH,
                getString(R.string.notif_channel_push_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_push_desc)
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
