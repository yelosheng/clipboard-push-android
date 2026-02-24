package com.clipboardpush.plus.util

fun formatFileSize(size: Long?): String {
    if (size == null || size <= 0) return ""
    return when {
        size < 1024L -> "$size B"
        size < 1024L * 1024L -> String.format("%.1f KB", size / 1024.0)
        else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
    }
}
