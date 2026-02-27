package com.clipboardpush.plus.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.clipboardpush.plus.BuildConfig
import java.io.File

/**
 * 剪贴板操作助手
 * 封装剪贴板读写操作
 */
class ClipboardHelper(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardHelper"
        private const val CLIP_LABEL = "Clipboard Man"
    }

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /**
     * 复制文本到剪贴板
     */
    fun copyText(text: String): Boolean {
        return try {
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            clipboardManager.setPrimaryClip(clip)
            if (BuildConfig.DEBUG) Log.d(TAG, "Text copied to clipboard: ${text.take(50)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy text: ${e.message}")
            false
        }
    }

    /**
     * 复制文件 URL 引用到剪贴板
     */
    fun copyFileReference(url: String, fileName: String): Boolean {
        return try {
            // 复制完整 URL
            val clip = ClipData.newPlainText(CLIP_LABEL, url)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "File reference copied: $fileName -> $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file reference: ${e.message}")
            false
        }
    }

    /**
     * 复制本地文件路径到剪贴板
     */
    fun copyFilePath(filePath: String): Boolean {
        return try {
            val clip = ClipData.newPlainText(CLIP_LABEL, filePath)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "File path copied: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file path: ${e.message}")
            false
        }
    }

    /**
     * 复制图片 URI 到剪贴板（可直接粘贴到支持的应用）
     */
    fun copyImageUri(uri: Uri, mimeType: String = "image/*"): Boolean {
        return try {
            val clip = ClipData.newUri(context.contentResolver, CLIP_LABEL, uri)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "Image URI copied: $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image URI: ${e.message}")
            false
        }
    }

    /**
     * 获取当前剪贴板文本内容
     */
    fun getText(): String? {
        return try {
            if (clipboardManager.hasPrimaryClip()) {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).text?.toString()
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get clipboard text: ${e.message}")
            null
        }
    }

    /**
     * 获取当前剪贴板 ClipData（用于自动推送时检测内容类型）
     */
    fun getPrimaryClip(): ClipData? {
        return try {
            if (clipboardManager.hasPrimaryClip()) clipboardManager.primaryClip else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get primary clip: ${e.message}")
            null
        }
    }

    /**
     * 清空剪贴板
     */
    fun clear() {
        try {
            val clip = ClipData.newPlainText("", "")
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "Clipboard cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear clipboard: ${e.message}")
        }
    }

    /**
     * 获取文件的安全 URI (FileProvider)
     */
    fun getUriForFile(file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get URI for file: ${e.message}")
            null
        }
    }
    interface OnPrimaryClipChangedListener {
        fun onPrimaryClipChanged()
    }

    private val listeners = mutableMapOf<OnPrimaryClipChangedListener, ClipboardManager.OnPrimaryClipChangedListener>()

    fun addPrimaryClipChangedListener(listener: OnPrimaryClipChangedListener) {
        val systemListener = ClipboardManager.OnPrimaryClipChangedListener {
            listener.onPrimaryClipChanged()
        }
        listeners[listener] = systemListener
        clipboardManager.addPrimaryClipChangedListener(systemListener)
    }

    fun removePrimaryClipChangedListener(listener: OnPrimaryClipChangedListener) {
        listeners.remove(listener)?.let { systemListener ->
            clipboardManager.removePrimaryClipChangedListener(systemListener)
        }
    }
}
