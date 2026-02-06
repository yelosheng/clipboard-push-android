package com.example.clipboardman.worker

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.clipboardman.R
import com.example.clipboardman.ClipboardManApp
import com.example.clipboardman.data.repository.SettingsRepository
import com.example.clipboardman.util.CryptoManager
import com.example.clipboardman.util.FileUtil
import com.example.clipboardman.util.NotificationHelper
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
        
        private const val TAG = "DownloadWorker"
    }

    private val settingsRepository = SettingsRepository(context)
    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        val fileUrl = inputData.getString(KEY_FILE_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "unknown_file"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
        
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
            // 1. Download
            val tempEncryptedFile = File.createTempFile("enc_", ".tmp", applicationContext.cacheDir)
            
            Log.d(TAG, "Starting download: $fullUrl")
            downloadToFile(fullUrl, tempEncryptedFile)

            // 2. Decrypt (if needed) - Assume all R2 files are encrypted
            val roomKey = settingsRepository.roomKeyFlow.first()
            if (roomKey.isNullOrEmpty()) {
                Log.e(TAG, "No room key found for decryption")
                return Result.failure()
            }

            val cryptoManager = CryptoManager(roomKey)
            val decryptedFile = File.createTempFile("dec_", ".tmp", applicationContext.cacheDir)
            
            Log.d(TAG, "Decrypting file...")
            tempEncryptedFile.inputStream().use { input ->
                decryptedFile.outputStream().use { output ->
                    cryptoManager.decryptFile(input, output)
                }
            }

            // 3. Save to Public Directory
            Log.d(TAG, "Saving to public directory...")
            val uniqueName = FileUtil.generateUniqueFileName(fileName)
            val savedUri = FileUtil.saveToPublicDownloads(
                applicationContext, 
                decryptedFile, 
                uniqueName, 
                mimeType
            )

            // Cleanup
            tempEncryptedFile.delete()
            decryptedFile.delete()

            if (savedUri != null) {
                // 4. Check file handling mode and copy to clipboard if needed
                val fileHandleMode = settingsRepository.fileHandleModeFlow.first()
                Log.d(TAG, "File handle mode: $fileHandleMode")
                
                val clipboardHelper = com.example.clipboardman.service.ClipboardHelper(applicationContext)
                
                when (fileHandleMode) {
                    SettingsRepository.FILE_MODE_SAVE_LOCAL -> {
                        // Just save, copy file path to clipboard
                        clipboardHelper.copyFilePath(savedUri.toString())
                        Log.d(TAG, "Copied file path to clipboard")
                    }
                    SettingsRepository.FILE_MODE_COPY_REFERENCE -> {
                        // Copy file URI reference
                        clipboardHelper.copyImageUri(savedUri)
                        Log.d(TAG, "Copied file URI to clipboard")
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
                                clipboardHelper.copyFilePath(savedUri.toString())
                            }
                        } else {
                            // For non-images, copy file URI
                            clipboardHelper.copyImageUri(savedUri)
                        }
                    }
                }
                
                // Success notification
                NotificationHelper.showPushNotification(
                    applicationContext,
                    "Download Complete",
                    "Saved to Downloads: $uniqueName"
                )
                Result.success(workDataOf("uri" to savedUri.toString()))
            } else {
                Log.e(TAG, "Failed to save to public downloads")
                Result.failure()
            }

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

    private suspend fun downloadToFile(url: String, file: File) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
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
