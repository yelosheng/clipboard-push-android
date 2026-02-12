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
        private const val EXPECTED_ACTION = "com.example.clipboardman.ACTION_PUSH_CLIPBOARD"
        @Volatile
        private var lastPushTime = 0L
        private const val DEBOUNCE_MS = 3000L // 3秒防抖
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: android.content.Intent) {
        // 验证 action，忽略非预期的调用
        if (intent.action != EXPECTED_ACTION) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            finish()
            return
        }
        performQuickPush()
    }

    private fun performQuickPush() {
        // 防抖：3秒内不重复执行
        val now = System.currentTimeMillis()
        if (now - lastPushTime < DEBOUNCE_MS) {
            Log.d(TAG, "Debounce: skipping duplicate push")
            finish()
            return
        }
        lastPushTime = now

        val settingsRepository = SettingsRepository(this)

        CoroutineScope(Dispatchers.Main).launch {
            // Delay to ensure window focus / notification shade collapse
            delay(300)

            try {
                // 读取剪贴板
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboardManager.primaryClip
                
                if (clipData == null || clipData.itemCount == 0) {
                    Toast.makeText(this@QuickPushActivity, "剪贴板为空", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                
                // 尝试获取文本
                val item = clipData.getItemAt(0)
                val text = item.text?.toString() ?: item.coerceToText(this@QuickPushActivity)?.toString()
                
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@QuickPushActivity, "剪贴板不是文本", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                
                // 获取配置
                val serverAddress = settingsRepository.serverAddressFlow.first()
                val useHttps = settingsRepository.useHttpsFlow.first()
                val roomId = settingsRepository.roomIdFlow.first()
                val roomKey = settingsRepository.roomKeyFlow.first()
                
                if (serverAddress.isBlank() || roomId.isNullOrBlank()) {
                    Toast.makeText(this@QuickPushActivity, "未配置服务器", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                // Encryption Logic
                var contentToSend = text
                var isEncrypted = false

                if (!roomKey.isNullOrBlank()) {
                    try {
                        val cryptoManager = com.example.clipboardman.util.CryptoManager(roomKey)
                        val encryptedBytes = cryptoManager.encrypt(text.toByteArray(Charsets.UTF_8))
                        if (encryptedBytes != null) {
                            contentToSend = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
                            isEncrypted = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Encryption failed", e)
                        // Fallback to plain text? Or fail?
                        // Let's fallback but log it
                    }
                }
                
                // 发送
                val httpUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
                val apiService = com.example.clipboardman.data.remote.ApiService(httpUrl)
                
                // Get Device ID for anti-echo
                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android_quick"
                val clientId = "android_$deviceId"

                val payload = mutableMapOf<String, Any>(
                    "content" to contentToSend,
                    "room" to roomId,
                    "timestamp" to System.currentTimeMillis(),
                    "source" to "Android_Quick"
                )
                if (isEncrypted) {
                    payload["encrypted"] = true
                }

                val result = apiService.relayEvent(roomId, "clipboard_sync", payload, clientId)
                
                if (result.isFailure) {
                    Toast.makeText(this@QuickPushActivity, "发送失败", Toast.LENGTH_SHORT).show()
                } else {
                    // 震动反馈
                    vibrateSuccess()
                    Toast.makeText(this@QuickPushActivity, "推送成功", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Quick push failed", e)
                Toast.makeText(this@QuickPushActivity, "推送失败", Toast.LENGTH_SHORT).show()
            }
            
            // 完成后关闭
            delay(100)
            finish()
        }
    }
    
    private fun vibrateSuccess() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
