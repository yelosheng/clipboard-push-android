package com.example.clipboardman.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.clipboardman.ClipboardManApp
import com.example.clipboardman.MainActivity
import com.example.clipboardman.R
import com.example.clipboardman.data.model.ConnectionState
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.example.clipboardman.util.DebugLogger

/**
 * 通知助手
 * 管理前台服务通知和推送通知
 */
object NotificationHelper {

    private const val SERVICE_NOTIFICATION_ID = 1001
    private const val PUSH_NOTIFICATION_ID_BASE = 2000
    
    // 推送剪贴板 action
    const val ACTION_PUSH_CLIPBOARD = "com.example.clipboardman.ACTION_PUSH_CLIPBOARD"

    /**
     * 构建前台服务通知 - 使用自定义布局
     */
    fun buildServiceNotification(
        context: Context,
        state: ConnectionState,
        serverAddress: String,
        peerCount: Int = 0,
        peers: List<String> = emptyList()
    ): Notification {
        val contentText = when (state) {
            ConnectionState.CONNECTED -> {
                // peers list is already self-filtered by RelayRepository
                if (peers.isNotEmpty()) {
                    "已连接: ${peers.joinToString(", ")}"
                } else {
                    "已连接 (无设备)"
                }
            }
            ConnectionState.CONNECTING -> "正在连接..."
            ConnectionState.DISCONNECTED -> "未连接"
            ConnectionState.ERROR -> "连接错误"
        }
        
        val color = when (state) {
            ConnectionState.CONNECTED -> {
                if (peers.isNotEmpty()) Color.GREEN else Color.YELLOW
            }
            ConnectionState.CONNECTING -> Color.parseColor("#FFA500") // Orange
            ConnectionState.ERROR -> Color.RED
            ConnectionState.DISCONNECTED -> Color.GRAY
        }

        // Determine icon resource based on state
        val iconResId = when (state) {
            ConnectionState.CONNECTED -> R.drawable.ic_cloud_black_24dp
            ConnectionState.CONNECTING -> R.drawable.ic_cloud_black_24dp // Use same cloud for connecting, maybe sync later
            else -> R.drawable.ic_cloud_off_black_24dp
        }

        val largeIconBitmap = bitmapFromVector(context, iconResId, color)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 创建推送剪贴板按钮的 Intent
        val pushIntent = Intent(context, com.example.clipboardman.QuickPushActivity::class.java).apply {
            action = ACTION_PUSH_CLIPBOARD  // 使用 action 区分
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pushPendingIntent = PendingIntent.getActivity(
            context,
            100,  // 使用不同的 requestCode
            pushIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 创建自定义布局
        val remoteViews = android.widget.RemoteViews(context.packageName, R.layout.notification_service)
        remoteViews.setTextViewText(R.id.notification_title, "Clipboard Push")
        remoteViews.setTextViewText(R.id.notification_text, contentText)
        if (largeIconBitmap != null) {
            remoteViews.setImageViewBitmap(R.id.notification_icon, largeIconBitmap)
        } else {
            // Fallback to vector drawable resource if bitmap fails (though RemoteViews supports limited vector support depending on API)
            // Or just leave it as is (defined in layout) or set to a safe png resource
            remoteViews.setImageViewResource(R.id.notification_icon, R.drawable.ic_launcher_foreground) // Fallback
        }
        remoteViews.setOnClickPendingIntent(R.id.btn_push, pushPendingIntent)

        return NotificationCompat.Builder(context, ClipboardManApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setCustomContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setSilent(true)
            .setColor(color) // Keep accent color for text
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun bitmapFromVector(context: Context, vectorResId: Int, color: Int): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
            val wrappedDrawable = DrawableCompat.wrap(drawable).mutate()
            
            // Apply tint
            DrawableCompat.setTint(wrappedDrawable, color)
            
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            DebugLogger.log("NotificationHelper", "Bitmap generated successfully for $vectorResId")
            bitmap
        } catch (e: Exception) {
            DebugLogger.log("NotificationHelper", "Bitmap generation failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 更新服务通知
     */
    fun updateServiceNotification(
        context: Context,
        state: ConnectionState,
        serverAddress: String,
        peerCount: Int = 0,
        peers: List<String> = emptyList()
    ) {
        val notification = buildServiceNotification(context, state, serverAddress, peerCount, peers)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(SERVICE_NOTIFICATION_ID, notification)
    }

    /**
     * 显示推送消息通知（带声音和弹出）
     */
    fun showPushNotification(
        context: Context,
        title: String,
        content: String,
        notificationId: Int = PUSH_NOTIFICATION_ID_BASE
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 使用推送通知渠道（高优先级，有声音）
        val notification = NotificationCompat.Builder(context, ClipboardManApp.NOTIFICATION_CHANNEL_PUSH)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // 高优先级
            .setDefaults(NotificationCompat.DEFAULT_ALL)    // 默认声音、振动、灯光
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * 获取服务通知 ID
     */
    fun getServiceNotificationId(): Int = SERVICE_NOTIFICATION_ID
}
