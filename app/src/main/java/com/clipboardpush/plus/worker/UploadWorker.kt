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
        private const val LARGE_FILE_THRESHOLD_BYTES = 50L * 1024 * 1024 // 50 MB

        /** Returns adaptive LAN ACK timeout: 2s per MB, min 30s, max 10min. */
        fun lanAckTimeoutMs(fileSizeBytes: Long): Long =
            minOf(10 * 60_000L, maxOf(30_000L, fileSizeBytes / (1024 * 1024) * 2_000L))

        /** Returns true if the device is on WiFi or Ethernet (i.e., can reach LAN peers). */
        fun isOnLan(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                   caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    private val settingsRepository = SettingsRepository(context)

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI_STRING) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
        val uri = Uri.parse(uriString)

        // Quick early reject: if ContentResolver reports size > 50MB and not on LAN, fail fast
        // (avoids copying a huge file before rejecting; post-copy check is the definitive one)
        val onLan = isOnLan(applicationContext)
        if (!onLan) {
            val approxSize = try {
                applicationContext.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
            } catch (_: Exception) { -1L }
            if (approxSize > LARGE_FILE_THRESHOLD_BYTES) {
                NotificationHelper.showPushNotification(
                    applicationContext,
                    applicationContext.getString(R.string.worker_upload_failed_title),
                    applicationContext.getString(R.string.worker_upload_lan_required)
                )
                return Result.failure()
            }
        }

        val notificationId = System.currentTimeMillis().toInt()
        setForeground(createForegroundInfo(notificationId, applicationContext.getString(R.string.worker_upload_title), applicationContext.getString(R.string.worker_upload_preparing)))

        com.clipboardpush.plus.service.ClipboardService.onUploadStarted()

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

            val pcName = settingsRepository.recentPeersFlow.first()
                .firstOrNull { it.room == roomId }?.displayName
                ?: applicationContext.getString(R.string.default_pc_name)

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
            
            // Definitive large-file check using actual size from temp file (reliable)
            val actualSize = tempSourceFile!!.length()
            val isLargeFile = actualSize > LARGE_FILE_THRESHOLD_BYTES
            if (isLargeFile && !onLan) {
                NotificationHelper.showPushNotification(
                    applicationContext,
                    applicationContext.getString(R.string.worker_upload_failed_title),
                    applicationContext.getString(R.string.worker_upload_lan_required)
                )
                return Result.failure()
            }

            val localNetwork = com.clipboardpush.plus.util.NetworkUtil.getLocalNetworkInfo(applicationContext)

            if (localNetwork != null) {
                 setForeground(createForegroundInfo(notificationId, applicationContext.getString(R.string.worker_upload_title), applicationContext.getString(R.string.worker_upload_announcing)))
                 
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
                     
                     
                     // Wait for ACK (adaptive timeout: 2s per MB based on actual file size)
                     val lanTimeout = lanAckTimeoutMs(actualSize)
                     Log.d(TAG, "LAN ACK timeout: ${lanTimeout / 1000}s for ${actualSize / (1024*1024)}MB")
                     var lanSuccess = false
                     try {
                         withTimeout(lanTimeout) {
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
                            applicationContext.getString(R.string.worker_upload_success_title),
                            applicationContext.getString(R.string.worker_upload_success_lan, fileName, pcName)
                        )
                        return Result.success()
                     } else if (isLargeFile) {
                         // Large file: LAN failed, do NOT fall back to cloud
                         NotificationHelper.showPushNotification(
                             applicationContext,
                             applicationContext.getString(R.string.worker_upload_failed_title),
                             applicationContext.getString(R.string.worker_upload_lan_large_failed)
                         )
                         return Result.failure()
                     }
                 }
            } else if (isLargeFile) {
                // Large file, no LAN port available — should not reach here due to early check,
                // but guard just in case
                NotificationHelper.showPushNotification(
                    applicationContext,
                    applicationContext.getString(R.string.worker_upload_failed_title),
                    applicationContext.getString(R.string.worker_upload_lan_required)
                )
                return Result.failure()
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
                setForeground(createForegroundInfo(notificationId, applicationContext.getString(R.string.worker_upload_title), applicationContext.getString(R.string.worker_upload_encrypting)))
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
            setForeground(createForegroundInfo(notificationId, applicationContext.getString(R.string.worker_upload_title), applicationContext.getString(R.string.worker_upload_auth)))
            val authResult = apiService.getUploadAuth(fileName, encryptedSize, "application/octet-stream")
            val auth = authResult.getOrThrow()

            // 4. Upload R2
            setForeground(createForegroundInfo(notificationId, applicationContext.getString(R.string.worker_upload_title), applicationContext.getString(R.string.worker_upload_sending)))
            apiService.uploadToR2(auth.upload_url, tempEncryptedFile, "application/octet-stream")
            

            // 5. Notify Relay
            setForeground(createForegroundInfo(notificationId, applicationContext.getString(R.string.worker_upload_title), applicationContext.getString(R.string.worker_upload_finalizing)))
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
                applicationContext.getString(R.string.worker_upload_success_title),
                applicationContext.getString(R.string.worker_upload_success_cloud, fileName, pcName)
            )

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            NotificationHelper.showPushNotification(
                applicationContext,
                applicationContext.getString(R.string.worker_upload_failed_title),
                e.message ?: "Unknown error"
            )
            return Result.failure()
        } finally {
            com.clipboardpush.plus.service.ClipboardService.onUploadFinished()
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
