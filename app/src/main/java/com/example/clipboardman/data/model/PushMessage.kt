package com.example.clipboardman.data.model

import com.google.gson.annotations.SerializedName

/**
 * 推送消息数据模型
 * 对应服务器返回的 JSON 格式
 */
data class PushMessage(
    @SerializedName("id")
    val id: String,

    @SerializedName("type")
    val type: String,  // "text", "image", "video", "audio", "file", "connected"

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("file_url")
    val fileUrl: String? = null,

    @SerializedName("file_name")
    val fileName: String? = null,

    @SerializedName("file_size")
    val fileSize: Long? = null,

    @SerializedName("mime_type")
    val mimeType: String? = null,

    @SerializedName("timestamp")
    val timestamp: String? = null,

    @SerializedName("server_time")
    val serverTime: String? = null
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_AUDIO = "audio"
        const val TYPE_FILE = "file"
        const val TYPE_CONNECTED = "connected"
    }

    val isTextType: Boolean
        get() = type == TYPE_TEXT

    val isFileType: Boolean
        get() = type in listOf(TYPE_IMAGE, TYPE_VIDEO, TYPE_AUDIO, TYPE_FILE)

    val isConnectedMessage: Boolean
        get() = type == TYPE_CONNECTED
}
