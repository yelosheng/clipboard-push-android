package com.clipboardpush.plus.worker

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clipboardpush.plus.R
import com.clipboardpush.plus.ClipboardManApp
import com.clipboardpush.plus.data.repository.MessageRepository
import com.clipboardpush.plus.data.repository.SettingsRepository
import com.clipboardpush.plus.util.CryptoManager
import com.clipboardpush.plus.util.FileUtil
import com.clipboardpush.plus.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class DownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_FILE_URL = "key_file_url"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_MIME_TYPE = "key_mime_type"
        const val KEY_IS_ENCRYPTED = "key_is_encrypted"
        const val KEY_MESSAGE_ID = "key_message_id"
        const val KEY_IS_ANNOUNCE = "key_is_announce"
        const val KEY_TRANSFER_ID = "key_transfer_id"
        const val KEY_PROGRESS = "key_progress"

        private const val TAG = "DownloadWorker"
    }

    private val settingsRepository = SettingsRepository(context)
    private val messageRepository = MessageRepository(context)
    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        val fileUrl = inputData.getString(KEY_FILE_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "unknown_file"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
        val messageId = inputData.getString(KEY_MESSAGE_ID)
        val isAnnounce = inputData.getBoolean(KEY_IS_ANNOUNCE, false)
        val transferId = inputData.getString(KEY_TRANSFER_ID)
        
        
        // --- ANNOUNCE MODE (v2) ---
        if (isAnnounce) {
            // In Announce mode, fileUrl IS the local_url. We try it once.
            // If it fails, we fail the worker so Service can request Relay.
            val notificationId = System.currentTimeMillis().toInt()
            setForeground(createForegroundInfo(notificationId, fileName, "Downloading from LAN..."))
            
            return try {
                val roomId = settingsRepository.roomIdFlow.first()
                val tempLocal = File.createTempFile("loc_v2_", ".tmp", applicationContext.cacheDir)
                
                downloadToFile(fileUrl, tempLocal, roomId) { pct ->
                    setProgress(workDataOf(KEY_PROGRESS to pct))
                }
                
                if (tempLocal.length() > 0) {
                     processDownloadedFile(tempLocal, fileName, mimeType, messageId, transferId)
                } else {
                     tempLocal.delete()
                     Result.failure()
                }
            } catch (e: Exception) {
                Result.failure()
            }
        }
        
        // --- LEGACY/FALLBACK MODE (v1) ---

        // Construct full URL if relative
        val serverAddress = settingsRepository.serverAddressFlow.first()
        val useHttps = settingsRepository.useHttpsFlow.first()
        val fullUrl = if (fileUrl.startsWith("http")) {
            fileUrl
        } else {
            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            "$baseUrl$fileUrl"
        }

        val notificationId = System.currentTimeMillis().toInt()
        setForeground(createForegroundInfo(notificationId, fileName, "Downloading..."))

        return try {
            // Get Pairing Info for Local Sync
            val peerIp = settingsRepository.peerLocalIpFlow.first()
            val peerPort = settingsRepository.peerLocalPortFlow.first()
            val roomId = settingsRepository.roomIdFlow.first()
            
            var sourceFile: File? = null
            var isLocalTransfer = false

            // 1. Try Local Download (Plaintext)
            if (!peerIp.isNullOrBlank() && peerPort != null && peerPort > 0) {
                val localUrl = "http://$peerIp:$peerPort/files/$fileName"
                try {
                    val tempLocal = File.createTempFile("loc_", ".tmp", applicationContext.cacheDir)
                    // Short timeout for local check? OkHttpClient defaults are 10s.
                    // Ideally we'd use a shorter timeout client, but for now reuse 'client'
                    downloadToFile(localUrl, tempLocal, roomId) { pct ->
                        setProgress(workDataOf(KEY_PROGRESS to pct))
                    }
                    if (tempLocal.length() > 0) {
                        sourceFile = tempLocal
                        isLocalTransfer = true
                    } else {
                         tempLocal.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Local download failed, falling back to cloud", e)
                }
            }

            // 2. Fallback to Cloud Download (Encrypted)
            if (sourceFile == null) {
                val tempEncryptedFile = File.createTempFile("enc_", ".tmp", applicationContext.cacheDir)
                downloadToFile(fullUrl, tempEncryptedFile) { pct ->
                    setProgress(workDataOf(KEY_PROGRESS to pct))
                }

                // Decrypt
                val roomKey = settingsRepository.roomKeyFlow.first()
                if (roomKey.isNullOrEmpty()) {
                    return Result.failure()
                }

                val cryptoManager = CryptoManager(roomKey)
                val tempDecrypted = File.createTempFile("dec_", ".tmp", applicationContext.cacheDir)
                
                Log.d(TAG, "Decrypting file...")
                tempEncryptedFile.inputStream().use { input ->
                    tempDecrypted.outputStream().use { output ->
                        cryptoManager.decryptFile(input, output)
                    }
                }
                tempEncryptedFile.delete()
                sourceFile = tempDecrypted
            }

            // 3. Save to Public Directory
            if (sourceFile == null || !sourceFile.exists()) {
                 return Result.failure()
            }
            
            processDownloadedFile(sourceFile, fileName, mimeType, messageId, transferId)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            NotificationHelper.showPushNotification(
                applicationContext,
                "Download Failed",
                e.message ?: "Unknown error"
            )
            Result.failure()
        }
    }

    private suspend fun processDownloadedFile(sourceFile: File, fileName: String, mimeType: String, messageId: String?, transferId: String?): Result {
            Log.d(TAG, "Saving to public directory...")
            
            val fileHandleMode = settingsRepository.fileHandleModeFlow.first()
            Log.d(TAG, "File handle mode: $fileHandleMode")

            // --- Clipboard-Only Mode: do NOT save to public Downloads ---
            if (fileHandleMode == SettingsRepository.FILE_MODE_CLIPBOARD_ONLY) {
                val clipboardHelper = com.clipboardpush.plus.service.ClipboardHelper(applicationContext)
                try {
                    if (mimeType.startsWith("image")) {
                        // Save to a content-accessible temp location so ClipboardHelper can read it
                        val uniqueName = FileUtil.generateUniqueFileName(fileName)
                        val savedUri = FileUtil.saveToPublicDownloads(applicationContext, sourceFile, uniqueName, mimeType)
                        sourceFile.delete()
                        if (savedUri != null) {
                            clipboardHelper.copyImageUri(savedUri, mimeType)
                            Log.d(TAG, "Clipboard-only: copied image URI to clipboard")
                            // Update message record if needed
                            if (messageId != null) {
                                messageRepository.updateMessageLocalPath(messageId, savedUri.toString())
                            }
                            NotificationHelper.showPushNotification(
                                applicationContext, applicationContext.getString(R.string.toast_copied), fileName
                            )
                            return Result.success(workDataOf("uri" to savedUri.toString(), KEY_TRANSFER_ID to (transferId ?: "")))
                        } else {
                            return Result.failure()
                        }
                    } else {
                        // 非图片: 复制文件路径到剪贴板，不保存到 Downloads
                        val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("File", sourceFile.absolutePath)
                        clipboard.setPrimaryClip(clip)
                        Log.d(TAG, "Clipboard-only: copied file path to clipboard")
                        sourceFile.delete()
                        NotificationHelper.showPushNotification(
                            applicationContext, applicationContext.getString(R.string.toast_copied), fileName
                        )
                        return Result.success(workDataOf(KEY_TRANSFER_ID to (transferId ?: "")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Clipboard-only mode failed", e)
                    sourceFile.delete()
                    return Result.failure()
                }
            }

            // --- Normal Modes: save to public Downloads first ---
            val uniqueName = FileUtil.generateUniqueFileName(fileName)
            val savedUri = FileUtil.saveToPublicDownloads(
                applicationContext, 
                sourceFile, 
                uniqueName, 
                mimeType
            )

            // Cleanup
            sourceFile.delete()

            if (savedUri != null) {
                // Check file handling mode and copy to clipboard if needed
                val clipboardHelper = com.clipboardpush.plus.service.ClipboardHelper(applicationContext)
                
                // Construct absolute path for clipboard
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(downloadsDir, "ClipboardPush")
                val savedFile = File(appDir, uniqueName)
                val absolutePath = savedFile.absolutePath

                when (fileHandleMode) {
                    SettingsRepository.FILE_MODE_SAVE_LOCAL -> {
                        // Just save, DO NOT copy path to clipboard (User request)
                        // clipboardHelper.copyFilePath(absolutePath)
                        Log.d(TAG, "Saved to local, clipboard not modified")
                    }
                    SettingsRepository.FILE_MODE_COPY_REFERENCE -> {
                        // Copy file URI reference (Legacy/Fallback) - treats as Save Local now? 
                        // Or we can keep it for legacy compatibility if user has old int value
                        // For now, let's just log it or maybe copy path if really needed.
                        // But since we removed the UI option, this case shouldn't be selected.
                        // Let's assume effectively SAVE_LOCAL behavior to be safe.
                         Log.d(TAG, "Legacy mode (Copy Reference): Saved to local, clipboard not modified")
                    }
                    SettingsRepository.FILE_MODE_SAVE_AND_COPY_IMAGE -> {
                        // If it's an image, copy the image to clipboard
                        if (mimeType.startsWith("image")) {
                            try {
                                clipboardHelper.copyImageUri(savedUri, mimeType)
                                Log.d(TAG, "Copied image to clipboard")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to copy image to clipboard", e)
                                // Fallback: copy path
                                clipboardHelper.copyFilePath(absolutePath)
                            }
                        } else {
                            // For non-images, copy file path
                             clipboardHelper.copyFilePath(absolutePath)
                        }
                    }
                }
                
                // Update message with local path
                if (messageId != null) {
                    messageRepository.updateMessageLocalPath(messageId, savedUri.toString())
                    Log.d(TAG, "Updated message $messageId with local path: $savedUri")
                } else {
                }
                
                // Success notification
                NotificationHelper.showPushNotification(
                    applicationContext,
                    "Download Complete",
                    "Saved to Downloads: $uniqueName"
                )
                return Result.success(
                    workDataOf(
                        "uri" to savedUri.toString(),
                        KEY_TRANSFER_ID to (transferId ?: "")
                    )
                )
            } else {
                Log.e(TAG, "Failed to save to public downloads")
                return Result.failure()
            }
    }

    private suspend fun downloadToFile(
        url: String, file: File, roomId: String? = null,
        onProgress: (suspend (Int) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            if (!roomId.isNullOrEmpty()) {
                requestBuilder.addHeader("X-Room-ID", roomId)
            }
            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            response.body?.let { body ->
                val contentLength = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        var bytesRead = 0L
                        var lastReported = -1
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0 && onProgress != null) {
                                val pct = ((bytesRead * 100L) / contentLength).toInt().coerceIn(0, 99)
                                if (pct >= lastReported + 5) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createForegroundInfo(notificationId: Int, title: String, content: String): androidx.work.ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, ClipboardManApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        // Android 14+ 需要指定 foregroundServiceType
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            androidx.work.ForegroundInfo(
                notificationId, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            androidx.work.ForegroundInfo(notificationId, notification)
        }
    }
}
