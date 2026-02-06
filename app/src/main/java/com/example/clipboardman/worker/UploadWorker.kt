package com.example.clipboardman.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.clipboardman.R
import com.example.clipboardman.ClipboardManApp
import com.example.clipboardman.data.remote.ApiService
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.util.CryptoManager
import com.example.clipboardman.util.FileUtil
import com.example.clipboardman.util.NotificationHelper
import kotlinx.coroutines.flow.first
import java.io.File

class UploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_URI_STRING = "key_uri_string"
        const val KEY_MIME_TYPE = "key_mime_type"
        private const val TAG = "UploadWorker"
    }

    private val settingsRepository = SettingsRepository(context)

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI_STRING) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
        val uri = Uri.parse(uriString)

        val notificationId = System.currentTimeMillis().toInt()
        setForeground(createForegroundInfo(notificationId, "Uploading...", "Preparing file..."))

        // Temp files
        var tempSourceFile: File? = null
        var tempEncryptedFile: File? = null

        try {
            val serverAddress = settingsRepository.serverAddressFlow.first()
            val useHttps = settingsRepository.useHttpsFlow.first()
            val roomKey = settingsRepository.roomKeyFlow.first()
            val roomId = settingsRepository.roomIdFlow.first()
            
            if (roomId.isNullOrEmpty() || roomKey.isNullOrEmpty()) {
                throw IllegalStateException("Not paired with PC")
            }

            val baseUrl = settingsRepository.getHttpBaseUrl(serverAddress, useHttps)
            val apiService = ApiService(baseUrl)

            // 1. Copy to temp file (for size calculation & stable reading)
            val fileName = FileUtil.getFileName(applicationContext, uri) ?: "upload.bin"
            tempSourceFile = File.createTempFile("src_", ".tmp", applicationContext.cacheDir)
            
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                tempSourceFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot read file")

            // 2. Encrypt
            setForeground(createForegroundInfo(notificationId, "Uploading...", "Encrypting..."))
            tempEncryptedFile = File.createTempFile("enc_", ".tmp", applicationContext.cacheDir)
            val cryptoManager = CryptoManager(roomKey)
            
            tempSourceFile.inputStream().use { input ->
                tempEncryptedFile.outputStream().use { output ->
                    cryptoManager.encryptFile(input, output)
                }
            }
            
            val encryptedSize = tempEncryptedFile.length()

            // 3. Get Auth
            setForeground(createForegroundInfo(notificationId, "Uploading...", "Requesting Auth..."))
            val authResult = apiService.getUploadAuth(fileName, encryptedSize, "application/octet-stream")
            val auth = authResult.getOrThrow()

            // 4. Upload R2
            setForeground(createForegroundInfo(notificationId, "Uploading...", "Sending to Cloud..."))
            apiService.uploadToR2(auth.upload_url, tempEncryptedFile, "application/octet-stream")

            // 5. Notify Relay
            setForeground(createForegroundInfo(notificationId, "Uploading...", "Finalizing..."))
            val eventData = mapOf(
                "download_url" to auth.download_url,
                "filename" to fileName,
                "size" to encryptedSize, // Or original size if prefer
                "timestamp" to System.currentTimeMillis(),
                "source" to "Android",
                "type" to if (mimeType.startsWith("image")) "image" else "file"
            )
            apiService.relayEvent(roomId, "file_sync", eventData)

            NotificationHelper.showPushNotification(
                applicationContext,
                "Upload Complete",
                "Sent $fileName to PC"
            )

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            NotificationHelper.showPushNotification(
                applicationContext,
                "Upload Failed",
                e.message ?: "Unknown error"
            )
            return Result.failure()
        } finally {
            tempSourceFile?.delete()
            tempEncryptedFile?.delete()
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
