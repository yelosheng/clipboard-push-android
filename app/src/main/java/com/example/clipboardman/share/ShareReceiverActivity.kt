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
import java.io.File
import java.io.FileOutputStream

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
     * 处理文本分享
     */
    private suspend fun handleTextShare(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            showError("分享内容为空")
            return
        }

        contentDescription.value = text.take(50) + if (text.length > 50) "..." else ""
        uploadState.value = UploadState.Uploading("正在上传文本...")

        val result = apiService?.pushText(text)
        result?.onSuccess {
            uploadState.value = UploadState.Success
            Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show()
            delay(500)
            finish()
        }?.onFailure { error ->
            showError("上传失败: ${error.message}")
        }
    }

    /**
     * 处理文件/图片分享
     */
    /**
     * 处理文件/图片分享（支持单选和多选）
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

        val total = uris.size
        var successCount = 0
        var failCount = 0

        // 显示初始状态
        contentDescription.value = if (total == 1) "准备上传..." else "准备上传 $total 个文件..."

        for ((index, uri) in uris.withIndex()) {
            val fileName = getFileName(uri) ?: "shared_file_${System.currentTimeMillis()}"
            
            // 更新当前状态
            val progressStr = if (total > 1) "(${index + 1}/$total) " else ""
            uploadState.value = UploadState.Uploading("${progressStr}正在上传:\n$fileName")
            if (total == 1) {
                contentDescription.value = fileName
            }

            // 复制到临时文件
            val tempFile = copyUriToTempFile(uri, fileName)
            if (tempFile == null) {
                Log.e(TAG, "Failed to copy file: $fileName")
                failCount++
                continue
            }

            try {
                // 尝试获取具体 MIME 类型
                val specificMimeType = contentResolver.getType(uri) ?: intent.type ?: "*/*"
                
                val result = apiService?.pushFile(tempFile, specificMimeType)
                if (result != null && result.isSuccess) {
                    successCount++
                } else {
                    failCount++
                    Log.e(TAG, "Failed to upload $fileName: ${result?.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Exception uploading $fileName: ${e.message}")
            } finally {
                // 清理临时文件
                tempFile.delete()
            }
        }

        // 最终结果处理
        if (failCount == 0) {
            uploadState.value = UploadState.Success
            Toast.makeText(this, "全部上传成功", Toast.LENGTH_SHORT).show()
            delay(500)
            finish()
        } else {
            if (successCount > 0) {
                Toast.makeText(this, "完成: $successCount 成功, $failCount 失败", Toast.LENGTH_LONG).show()
                finish() // 部分成功也关闭
            } else {
                showError("所有文件上传失败")
            }
        }
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
     * 复制 Uri 内容到临时文件
     */
    private suspend fun copyUriToTempFile(uri: Uri, fileName: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy uri to temp file: ${e.message}")
                null
            }
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
