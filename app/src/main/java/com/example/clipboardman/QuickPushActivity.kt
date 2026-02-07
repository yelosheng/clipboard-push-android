package com.example.clipboardman

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.clipboardman.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * 透明 Activity 用于快速推送剪贴板
 * 从通知栏按钮启动，读取剪贴板后立即关闭
 */
class QuickPushActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickPushActivity"
    }

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settingsRepository = SettingsRepository(this)
        
        // 立即执行推送
        performQuickPush()
    }

    private fun performQuickPush() {
        activityScope.launch {
            try {
                // 读取剪贴板 (在 Activity 前台上下文中可以正常读取)
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboardManager.primaryClip
                
                if (clipData == null || clipData.itemCount == 0) {
                    showResult(false, "剪贴板为空")
                    return@launch
                }
                
                val text = clipData.getItemAt(0).text?.toString()
                if (text.isNullOrBlank()) {
                    showResult(false, "剪贴板内容不是文本")
                    return@launch
                }
                
                // 获取配置
                val serverAddress = settingsRepository.serverAddressFlow.first()
                val useHttps = settingsRepository.useHttpsFlow.first()
                val roomId = settingsRepository.roomIdFlow.first()
                
                if (serverAddress.isBlank() || roomId.isNullOrBlank()) {
                    showResult(false, "未配置服务器")
                    return@launch
                }
                
                // 发送 - 使用 HTTP Relay API 而不是 WebSocket
                val httpUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
                val apiService = com.example.clipboardman.data.remote.ApiService(httpUrl)
                
                // 使用 Relay API 发送
                val result = apiService.relayEvent(roomId, "clipboard_sync", mapOf(
                    "content" to text,
                    "room" to roomId,
                    "timestamp" to System.currentTimeMillis(),
                    "source" to "Android_Quick"
                ))
                
                if (result.isFailure) {
                    showResult(false, result.exceptionOrNull()?.message ?: "发送失败")
                    return@launch
                }
                
                // 震动反馈
                vibrateSuccess()
                
                showResult(true, text.take(30) + if (text.length > 30) "..." else "")
                
            } catch (e: Exception) {
                Log.e(TAG, "Quick push failed", e)
                showResult(false, e.message ?: "未知错误")
            }
        }
    }
    
    private fun vibrateSuccess() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }
    
    private fun showResult(success: Boolean, message: String) {
        if (success) {
            Toast.makeText(this, "已推送: $message", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "推送失败: $message", Toast.LENGTH_SHORT).show()
        }
        
        // 延迟关闭以显示 Toast
        activityScope.launch {
            delay(300)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
