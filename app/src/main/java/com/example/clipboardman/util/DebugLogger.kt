package com.example.clipboardman.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val MAX_LOGS = 200
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun log(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $tag: $message"
        
        // Also log to logcat
        Log.d(tag, message)

        val currentList = _logs.value.toMutableList()
        currentList.add(0, entry) // Add to top
        if (currentList.size > MAX_LOGS) {
            _logs.value = currentList.take(MAX_LOGS)
        } else {
            _logs.value = currentList
        }
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
}
