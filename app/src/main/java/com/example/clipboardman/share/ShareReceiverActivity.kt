package com.example.clipboardman.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.clipboardman.data.remote.ApiService
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.ui.theme.ClipboardManTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * 分享接收 Activity
 * 接收系统分享的文本、图片、文件，上传到服务器
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiverActivity"
    }

    private lateinit var settingsRepository: SettingsRepository
    private var apiService: ApiService? = null

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI 状态
    private val uploadState = mutableStateOf<UploadState>(UploadState.Idle)
    private val contentDescription = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsRepository = SettingsRepository(this)

        setContent {
            ClipboardManTheme {
                ShareDialog(
                    uploadState = uploadState.value,
                    contentDescription = contentDescription.value,
                    onDismiss = { finish() }
                )
            }
        }

        // 处理分享 Intent
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }

        activityScope.launch {
            // 初始化 API Service
            val serverAddress = settingsRepository.serverAddressFlow.first()
            val useHttps = settingsRepository.useHttpsFlow.first()
            if (serverAddress.isBlank()) {
                showError("未配置服务器地址")
                return@launch
            }

            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            apiService = ApiService(baseUrl)

            // 根据类型处理
            val mimeType = intent.type ?: ""
            if (mimeType == "text/plain" && action == Intent.ACTION_SEND) {
                handleTextShare(intent)
            } else {
                handleContentShare(intent, action == Intent.ACTION_SEND_MULTIPLE)
            }
        }
    }

    /**
     * 处理文本分享 - 使用 Relay API
     */
    private suspend fun handleTextShare(intent: Intent) {
        // Peer guard: check if any peers are online before sharing
        val currentPeerCount = com.example.clipboardman.data.repository.RelayRepository.peerCount.replayCache.firstOrNull() ?: 0
        if (currentPeerCount <= 0) {
            showError("推送失败：房间内没有其他在线设备")
            return
        }

        var text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            showError("分享内容为空")
            return
        }

        // 移除浏览器附加的 URL (如果用户只想要选中的文本)
        // 简单逻辑：如果文本包含 http 链接且不仅仅是链接，则尝试去除链接
        // 很多浏览器分享格式为: "选中文字 https://url..."
        if (text.contains("http")) {
            // 简单的正则去除 URL
            text = text.replace(Regex("\\s*https?://\\S+"), "").trim()
        }
        
        // 移除可能的首尾双引号 (某些浏览器分享会带)
        text = text.trim().removeSurrounding("\"")

        contentDescription.value = text.take(50) + if (text.length > 50) "..." else ""
        uploadState.value = UploadState.Uploading("正在发送文本...")

        // 获取 roomId
        val roomId = settingsRepository.roomIdFlow.first()
        if (roomId.isNullOrBlank()) {
            showError("未配置房间ID，请先在APP中连接服务器")
            return
        }
        
        // 获取 Client ID 防止回音
        val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android_unknown"
        val clientId = "android_$deviceId"

        // 使用 Relay API 发送
        val data = mapOf(
            "room" to roomId,
            "content" to text,
            "type" to "text",
            "timestamp" to System.currentTimeMillis()
        )
        
        // 传递 clientId
        val result = apiService?.relayEvent(roomId, "clipboard_sync", data, clientId)
        result?.onSuccess {
            uploadState.value = UploadState.Success
            Toast.makeText(this, "推送成功", Toast.LENGTH_SHORT).show()
            delay(500)
            finish()
        }?.onFailure { error ->
            showError("推送失败")
        }
    }

    /**
     * 处理文件/图片分享
     */
    /**
     * 处理文件/图片分享（支持单选和多选）- 使用 WorkManager 后台上传
     */
    private suspend fun handleContentShare(intent: Intent, isMultiple: Boolean) {
        val uris = ArrayList<Uri>()

        if (isMultiple) {
            val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list?.let { uris.addAll(it) }
        } else {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { uris.add(it) }
        }

        if (uris.isEmpty()) {
            showError("无法获取分享内容")
            return
        }

        // 检查配置
        val roomId = settingsRepository.roomIdFlow.first()
        val roomKey = settingsRepository.roomKeyFlow.first()
        if (roomId.isNullOrBlank() || roomKey.isNullOrBlank()) {
            showError("未配置房间，请先在APP中扫码配对")
            return
        }

        val total = uris.size
        contentDescription.value = if (total == 1) {
            getFileName(uris[0]) ?: "准备上传..."
        } else {
            "上传 $total 个文件..."
        }
        uploadState.value = UploadState.Uploading("正在加入上传队列...")

        // 使用 WorkManager 后台上传
        var queuedCount = 0
        for (uri in uris) {
            val mimeType = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.clipboardman.worker.UploadWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        com.example.clipboardman.worker.UploadWorker.KEY_URI_STRING to uri.toString(),
                        com.example.clipboardman.worker.UploadWorker.KEY_MIME_TYPE to mimeType
                    )
                )
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
            queuedCount++
            Log.d(TAG, "Queued upload: $uri")
        }

        // 显示成功并立即关闭
        uploadState.value = UploadState.Success
        val msg = if (queuedCount == 1) "已加入上传队列" else "$queuedCount 个文件已加入上传队列"
        Toast.makeText(this, "$msg\n详情请查看通知栏", Toast.LENGTH_SHORT).show()
        delay(800)
        finish()
    }

    /**
     * 从 Uri 获取文件名
     */
    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file name: ${e.message}")
            null
        }
    }

    /**
     * 显示错误
     */
    private fun showError(message: String) {
        uploadState.value = UploadState.Error(message)
        Log.e(TAG, message)
    }
}

/**
 * 上传状态
 */
sealed class UploadState {
    object Idle : UploadState()
    data class Uploading(val message: String) : UploadState()
    object Success : UploadState()
    data class Error(val message: String) : UploadState()
}

/**
 * 分享对话框 UI
 */
@Composable
fun ShareDialog(
    uploadState: UploadState,
    contentDescription: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = {
        if (uploadState !is UploadState.Uploading) {
            onDismiss()
        }
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "发送到服务器",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 内容描述
                if (contentDescription.isNotEmpty()) {
                    Text(
                        text = contentDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 状态显示
                when (uploadState) {
                    is UploadState.Idle -> {
                        Text("准备上传...")
                    }
                    is UploadState.Uploading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(uploadState.message)
                    }
                    is UploadState.Success -> {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("上传成功")
                    }
                    is UploadState.Error -> {
                        Text(
                            text = "✗",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uploadState.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }
}
