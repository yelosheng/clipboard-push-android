package com.clipboardpush.plus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 进程外的连接看门狗。
 *
 * 为什么非有不可：厂商 ROM（ColorOS 的 OplusHansManager 等）会冻结整个进程的线程，
 * 心跳、退避重连循环、socket.io 内部定时器全部停摆——**被冻结的进程无法检测自己被冻结**。
 * 任何"进程内自检"在这种场景下都是空谈。
 *
 * AlarmManager 是系统服务，投递闹钟时系统会强制解冻本进程，是少数几个不依赖
 * 我们自己线程还能跑起来的外部信号。
 *
 * 用 [AlarmManager.setAndAllowWhileIdle] 而非 setExactAndAllowWhileIdle：后者在
 * API 31+ 需要 SCHEDULE_EXACT_ALARM 权限（要用户授权，且受 Play 政策限制），
 * 而看门狗本就不需要精确到秒。Doze 下系统对前者有约 9 分钟的最小间隔限制，
 * 与这里 15 分钟的周期不冲突。
 */
object ConnectionWatchdog {

    private const val TAG = "ConnWatchdog"
    private const val REQUEST_CODE = 8801

    /** 巡检周期。够稀疏以免耗电，又远短于用户能容忍的掉线时长。 */
    const val INTERVAL_MS = 15 * 60 * 1000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pendingIntent(context)
            )
            Log.d(TAG, "Watchdog scheduled in ${INTERVAL_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog", e)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pendingIntent(context))
            Log.d(TAG, "Watchdog cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel watchdog", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context.applicationContext, ConnectionWatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class ConnectionWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.w("ConnWatchdog", "Watchdog fired -> poking ClipboardService")

        // 先排下一次：setAndAllowWhileIdle 是一次性闹钟，不会自动重复。
        // 放在最前面，保证后面任何异常都不会让看门狗链条断掉。
        ConnectionWatchdog.schedule(context)

        // 走 ACTION_START：startService() 的 debounce 现在带 isLivenessStale() 判断，
        // 连接确实活着就直接返回（几乎零成本），僵尸/已断才会真正重连。
        val serviceIntent = Intent(context.applicationContext, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
        } catch (e: Exception) {
            // Android 12+ 对后台启动前台服务有限制；本应用请求了电池优化豁免，
            // 豁免后不受该限制。未豁免的用户这里会失败，只能等下一次唤醒时机。
            Log.e("ConnWatchdog", "Failed to start service from watchdog", e)
        }
    }
}
