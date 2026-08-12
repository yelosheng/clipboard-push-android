package com.clipboardpush.plus.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.clipboardpush.plus.ClipboardManApp
import com.clipboardpush.plus.MainActivity
import com.clipboardpush.plus.R
import com.clipboardpush.plus.data.model.ConnectionState
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/**
 * 通知助手
 * 管理前台服务通知和推送通知
 */
object NotificationHelper {

    private const val SERVICE_NOTIFICATION_ID = 1001
    private const val PUSH_NOTIFICATION_ID_BASE = 2000
    
    // 推送剪贴板 action
    const val ACTION_PUSH_CLIPBOARD = "com.clipboardpush.plus.ACTION_PUSH_CLIPBOARD"

    /**
     * 构建前台服务通知 - 使用自定义布局
     */
    fun buildServiceNotification(
        context: Context,
        state: ConnectionState,
        serverAddress: String,
        /** 可送达数 = 在线 + FCM 可唤醒。只决定推送按钮是否可用，**不决定颜色**。 */
        reachablePeerCount: Int = 0,
        /** 真正在线的对端。决定颜色（绿/黄）与「目标设备」文案。 */
        peers: List<String> = emptyList(),
        lastVerifiedAtMs: Long = 0L
    ): Notification {
        // Line 1: server connection status.
        //
        // CONNECTED 时附上「最后确认」时刻。进程被厂商 ROM 冻结时没有任何线程能来重画这条
        // 通知，于是它会一直停在冻结前那一刻的样子——绿色看起来还连着，其实早断了。
        // 颜色本身无法自我修正，但「最后确认 07:15」这句话永远为真：用户扫一眼就知道
        // 这份状态是几点的快照，还作不作数。
        val line1 = when (state) {
            ConnectionState.CONNECTED -> if (lastVerifiedAtMs > 0L) {
                context.getString(
                    R.string.state_server_connected_verified,
                    formatClockTime(lastVerifiedAtMs)
                )
            } else {
                context.getString(R.string.state_server_connected)
            }
            ConnectionState.CONNECTING -> context.getString(R.string.state_connecting)
            ConnectionState.DISCONNECTED -> context.getString(R.string.state_disconnected)
            ConnectionState.ERROR -> context.getString(R.string.state_error)
        }

        // Line 2: PC status — only shown when connected to server
        val line2: String? = when {
            state != ConnectionState.CONNECTED -> null
            peers.isNotEmpty() -> context.getString(R.string.target_device, peers.joinToString(", "))
            else -> context.getString(R.string.state_pc_offline)
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
        val pushIntent = Intent(context, com.clipboardpush.plus.QuickPushActivity::class.java).apply {
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
        remoteViews.setTextViewText(R.id.notification_title, context.getString(R.string.app_name))
        remoteViews.setTextViewText(R.id.notification_text, line1)

        // Second line: show/hide based on connected state
        if (line2 != null) {
            remoteViews.setTextViewText(R.id.notification_subtext, line2)
            remoteViews.setViewVisibility(R.id.notification_subtext, android.view.View.VISIBLE)
        } else {
            remoteViews.setViewVisibility(R.id.notification_subtext, android.view.View.GONE)
        }

        if (largeIconBitmap != null) {
            remoteViews.setImageViewBitmap(R.id.notification_icon, largeIconBitmap)
        } else {
            remoteViews.setImageViewResource(R.id.notification_icon, R.drawable.ic_launcher_foreground)
        }

        // Push button: enabled only when peers are online
        // 推送按钮按「可送达」启用，而不是按「在线」：对端 socket 断了但仍能被 FCM 唤醒时，
        // 推送是有意义的，不该禁用。所以会出现「黄灯 + 按钮可用」的组合——这是正确的，
        // 黄灯表示没有真正在线的对端，按钮可用表示还有 FCM 这条路。
        val canPush = state == ConnectionState.CONNECTED && reachablePeerCount > 0
        remoteViews.setFloat(R.id.btn_push, "setAlpha", if (canPush) 1f else 0.38f)
        if (canPush) {
            remoteViews.setOnClickPendingIntent(R.id.btn_push, pushPendingIntent)
        }

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

    /** 把时间戳格式化成本地习惯的 HH:mm。 */
    private fun formatClockTime(timestampMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestampMs))

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
            bitmap
        } catch (e: Exception) {
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
        reachablePeerCount: Int = 0,
        peers: List<String> = emptyList(),
        lastVerifiedAtMs: Long = 0L
    ) {
        val notification = buildServiceNotification(
            context, state, serverAddress, reachablePeerCount, peers, lastVerifiedAtMs
        )
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
     * 显示加密出错通知，提示用户重新扫码配对
     */
    fun showEncryptionErrorNotification(context: Context) {
        showPushNotification(
            context = context,
            title = context.getString(R.string.notif_send_failed_title),
            content = context.getString(R.string.notif_encryption_error),
            notificationId = ENCRYPTION_ERROR_NOTIFICATION_ID
        )
    }

    private const val ENCRYPTION_ERROR_NOTIFICATION_ID = 1003
    private const val PUSH_TIP_NOTIFICATION_ID = 1005

    /**
     * 首次 peer 上线时，发一次性使用提示通知（方向 B）
     */
    fun showPushTipNotification(context: Context) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ClipboardManApp.NOTIFICATION_CHANNEL_PUSH)
            .setContentTitle(context.getString(R.string.notif_tip_title))
            .setContentText(context.getString(R.string.notif_tip_body))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_tip_body)))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(PUSH_TIP_NOTIFICATION_ID, notification)
    }

    /**
     * 获取服务通知 ID
     */
    fun getServiceNotificationId(): Int = SERVICE_NOTIFICATION_ID
}
