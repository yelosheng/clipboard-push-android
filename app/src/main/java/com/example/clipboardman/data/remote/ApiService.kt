package com.example.clipboardman.data.remote

import android.util.Log
import com.example.clipboardman.data.model.PushMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP API 服务
 * 用于文件上传下载和文本推送
 */
class ApiService(private val baseUrl: String) {

    companion object {
        private const val TAG = "ApiService"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
        private const val WRITE_TIMEOUT = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 推送文本到服务器
     */
    suspend fun pushText(content: String): Result<PushMessage> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("content" to content))
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/push/text")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val result = gson.fromJson(responseBody, ApiResponse::class.java)
                if (result.status == "ok" && result.message != null) {
                    Result.success(result.message)
                } else {
                    Result.failure(Exception(result.error ?: "Unknown error"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "pushText failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 上传文件到服务器
     */
    suspend fun pushFile(file: File, mimeType: String? = null): Result<PushMessage> = withContext(Dispatchers.IO) {
        try {
            val mediaType = (mimeType ?: getMimeType(file.name)).toMediaType()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/push/file")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val result = gson.fromJson(responseBody, ApiResponse::class.java)
                if (result.status == "ok" && result.message != null) {
                    Result.success(result.message)
                } else {
                    Result.failure(Exception(result.error ?: "Unknown error"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "pushFile failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 下载文件
     * @param fileUrl 文件相对路径 (如 /files/xxx.jpg)
     * @param destFile 目标文件
     * @param onProgress 下载进度回调 (0-100)
     */
    suspend fun downloadFile(
        fileUrl: String,
        destFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (fileUrl.startsWith("http")) fileUrl else "$baseUrl$fileUrl"
            Log.d(TAG, "Downloading: $fullUrl -> ${destFile.absolutePath}")

            val request = Request.Builder()
                .url(fullUrl)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val contentLength = body.contentLength()

            // 确保父目录存在
            destFile.parentFile?.mkdirs()

            body.byteStream().use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            onProgress?.invoke(progress)
                        }
                    }
                }
            }

            Log.d(TAG, "Download completed: ${destFile.absolutePath}")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed: ${e.message}", e)
            destFile.delete() // 清理失败的文件
            Result.failure(e)
        }
    }

    /**
     * 根据文件名获取 MIME 类型
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * 获取上传授权 (Cloudflare R2)
     */
    suspend fun getUploadAuth(filename: String, size: Long, contentType: String): Result<UploadAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf(
                "filename" to filename,
                "size" to size,
                "content_type" to contentType
            ))
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/file/upload_auth")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                // Server returns { upload_url: "...", download_url: "...", file_id: "..." }
                val auth = gson.fromJson(responseBody, UploadAuthResponse::class.java)
                Result.success(auth)
            } else {
                Result.failure(Exception("Get upload auth failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUploadAuth error", e)
            Result.failure(e)
        }
    }

    /**
     * 上传文件到 R2 (PUT)
     */
    suspend fun uploadToR2(url: String, file: File, contentType: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestBody = file.asRequestBody(contentType.toMediaType())
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("R2 Upload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadToR2 error", e)
            Result.failure(e)
        }
    }

    /**
     * 发送 Relay 事件 (通知服务器广播)
     */
    suspend fun relayEvent(room: String, event: String, data: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf(
                "room" to room,
                "event" to event,
                "data" to data
            )
            val json = gson.toJson(payload)
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/relay")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Relay event failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "relayEvent error", e)
            Result.failure(e)
        }
    }

    data class UploadAuthResponse(
        val upload_url: String,
        val download_url: String,
        val file_id: String
    )

    private data class ApiResponse(
        val status: String,
        val message: PushMessage? = null,
        val error: String? = null
    )
}
