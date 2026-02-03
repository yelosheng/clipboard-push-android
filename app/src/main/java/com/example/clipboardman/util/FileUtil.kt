package com.example.clipboardman.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文件工具类
 */
object FileUtil {

    private const val TAG = "FileUtil"
    private const val APP_FOLDER = "ClipboardMan"

    /**
     * 获取下载目录
     * Android 10+ 使用 App 私有目录
     */
    fun getDownloadDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APP_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 获取缓存目录
     */
    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, APP_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 生成唯一文件名
     */
    fun generateUniqueFileName(originalName: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = originalName.substringAfterLast('.', "")
        val baseName = originalName.substringBeforeLast('.')

        return if (extension.isNotEmpty()) {
            "${baseName}_$timestamp.$extension"
        } else {
            "${originalName}_$timestamp"
        }
    }

    /**
     * 根据文件名获取 MIME 类型
     */
    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // 图片
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"

            // 视频
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"

            // 音频
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"

            // 文档
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"

            // 压缩包
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"

            // 默认
            else -> "application/octet-stream"
        }
    }

    /**
     * 判断是否为图片类型
     */
    fun isImage(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    /**
     * 判断是否为视频类型
     */
    fun isVideo(mimeType: String): Boolean {
        return mimeType.startsWith("video/")
    }

    /**
     * 判断是否为音频类型
     */
    fun isAudio(mimeType: String): Boolean {
        return mimeType.startsWith("audio/")
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * 保存文件到公共 Downloads 目录 (Android 10+)
     * 返回文件 URI
     */
    fun saveToPublicDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + APP_FOLDER)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }

                Log.d(TAG, "Saved to public downloads: $uri")
                uri
            } else {
                // Android 9 及以下，直接复制到 Downloads 目录
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(downloadsDir, APP_FOLDER)
                if (!appDir.exists()) {
                    appDir.mkdirs()
                }

                val destFile = File(appDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)

                Log.d(TAG, "Saved to: ${destFile.absolutePath}")
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to public downloads: ${e.message}", e)
            null
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: ${e.message}")
            false
        }
    }

    /**
     * 清理缓存目录
     */
    fun clearCache(context: Context) {
        try {
            val cacheDir = getCacheDir(context)
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache: ${e.message}")
        }
    }
}
