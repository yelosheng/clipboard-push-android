package com.clipboardpush.plus.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clipboardpush.plus.R
import com.clipboardpush.plus.ClipboardManApp
import com.clipboardpush.plus.data.remote.ApiService
import com.clipboardpush.plus.data.repository.SettingsRepository
import com.clipboardpush.plus.util.CryptoManager
import com.clipboardpush.plus.util.FileUtil
import com.clipboardpush.plus.util.NotificationHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class UploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_URI_STRING = "key_uri_string"
        const val KEY_MIME_TYPE = "key_mime_type"
        private const val TAG = "UploadWorker"
        private const val LAN_ACK_TIMEOUT_MS = 8_000L
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
            
            val fileName = FileUtil.getFileName(applicationContext, uri) ?: "upload.bin"
            
            // 0. Prepare Source File (Copy URI to temp file for stable reading)
            if (tempSourceFile == null) {
                tempSourceFile = File.createTempFile("src_", ".tmp", applicationContext.cacheDir)
                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    tempSourceFile!!.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Cannot read file")
            }

            // --- 1. Try Encrypted LAN Upload (V4 Sender Mode) ---
            val peerIp = settingsRepository.peerLocalIpFlow.first()
            val peerPort = settingsRepository.peerLocalPortFlow.first()
            
            // Generate IDs
            val fileId = "f_" + System.currentTimeMillis()
            val transferId = "tr_" + System.currentTimeMillis() + "_" + (10..99).random()
            
            // Generate Device ID
            val deviceId = android.provider.Settings.Secure.getString(applicationContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android_unknown"
            val senderId = "android_$deviceId"
            
            // Check if we have network info (implies LAN capability)
            val localNetwork = com.clipboardpush.plus.util.NetworkUtil.getLocalNetworkInfo(applicationContext)
            
            
            if (localNetwork != null) {
                 setForeground(createForegroundInfo(notificationId, "Uploading...", "Announcing to LAN..."))
                 
                 // Encrypt to temp file
                 tempEncryptedFile = File.createTempFile("enc_", ".tmp", applicationContext.cacheDir)
                 val cryptoManager = CryptoManager(roomKey)
                 tempSourceFile!!.inputStream().use { input ->
                    tempEncryptedFile!!.outputStream().use { output ->
                        cryptoManager.encryptFile(input, output)
                    }
                 }
                 val encryptedSize = tempEncryptedFile!!.length()
                 
                 // Start Serving (Encrypted File)
                 var myPort = com.clipboardpush.plus.service.LocalFileServer.getPort()
                 if (myPort <= 0) {
                     com.clipboardpush.plus.service.LocalFileServer.startServer()
                     myPort = com.clipboardpush.plus.service.LocalFileServer.getPort()
                 }
                 

                 if (myPort > 0) {
                     com.clipboardpush.plus.service.LocalFileServer.serveFile(transferId, tempEncryptedFile!!, "application/octet-stream")
                     
                     val myIp = localNetwork.ip
                     val localUrl = "http://$myIp:$myPort/files/$transferId"
                     
                     // Announce
                     com.clipboardpush.plus.data.repository.RelayRepository.sendFileAvailable(
                         roomId, fileId, transferId, localUrl, fileName, 
                         if (mimeType.startsWith("image")) "image" else "file", 
                         encryptedSize, senderId
                     )
                     
                     
                     // Wait for ACK
                     var lanSuccess = false
                     try {
                         withTimeout(LAN_ACK_TIMEOUT_MS) { // LAN ACK timeout
                             com.clipboardpush.plus.data.repository.RelayRepository.events.collect { event ->
                                 if (event is com.clipboardpush.plus.data.repository.RelayEvent.FileSyncCompleted) {
                                      // Check if it matches our transfer
                                      val receivedTransferId = event.data.optString("transfer_id")
                                      val reason = event.data.optString("method", "lan")
                                      if (receivedTransferId == transferId) {
                                          lanSuccess = true
                                          throw java.util.concurrent.CancellationException("Completed") // Break collect
                                      }
                                 } else if (event is com.clipboardpush.plus.data.repository.RelayEvent.FileNeedRelay) {
                                      val receivedTransferId = event.data.optString("transfer_id")
                                      val reason = event.data.optString("reason")
                                      if (receivedTransferId == transferId) {
                                          lanSuccess = false
                                          throw java.util.concurrent.CancellationException("NeedRelay") // Break collect
                                      }
                                 } else if (event is com.clipboardpush.plus.data.repository.RelayEvent.TransferCommand) {
                                      val cmdTransferId = event.data.optString("transfer_id")
                                      val action = event.data.optString("action")
                                      val reason = event.data.optString("reason", "")
                                      
                                      if (cmdTransferId == transferId) {
                                          if (action == "finish") {
                                              lanSuccess = true
                                              throw java.util.concurrent.CancellationException("Completed")
                                          } else if (action == "upload_relay") {
                                              lanSuccess = false // Fallback
                                              throw java.util.concurrent.CancellationException("NeedRelay")
                                          }
                                      }
                                 }
                             }
                         }
                     } catch (e: TimeoutCancellationException) {
                     } catch (e: java.util.concurrent.CancellationException) {
                         // Expected flow for success
                     }
                     
                     com.clipboardpush.plus.service.LocalFileServer.stopServing(transferId)
                     
                     if (lanSuccess) {
                         NotificationHelper.showPushNotification(
                            applicationContext,
                            "Upload Complete",
                            "Sent $fileName to PC (LAN)"
                        )
                        return Result.success()
                     }
                 } else {
                 }
            } else {
            }
            
            // If we are here, LAN failed or skipped. Fallback to Cloud.
            // Reset temp files if needed (we might have encrypted already)
            
            if (tempEncryptedFile == null) {
                // 1. Copy to temp file (for size calculation & stable reading)
                if (tempSourceFile == null) {
                    tempSourceFile = File.createTempFile("src_", ".tmp", applicationContext.cacheDir)
                    applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                        tempSourceFile!!.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Cannot read file")
                }

                // 2. Encrypt
                setForeground(createForegroundInfo(notificationId, "Uploading...", "Encrypting..."))
                tempEncryptedFile = File.createTempFile("enc_", ".tmp", applicationContext.cacheDir)
                val cryptoManager = CryptoManager(roomKey)
                
                tempSourceFile!!.inputStream().use { input ->
                    tempEncryptedFile!!.outputStream().use { output ->
                        cryptoManager.encryptFile(input, output)
                    }
                }
            }
            val encryptedSize = tempEncryptedFile!!.length()

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
            
            
            apiService.relayEvent(roomId, "file_sync", eventData, senderId)

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
