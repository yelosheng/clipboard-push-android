package com.clipboardpush.plus.service

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object LocalFileServer : NanoHTTPD(0) { // 0 = Random Port
    private const val TAG = "LocalFileServer"
    
    // Map: transferId -> File
    private val servingFiles = ConcurrentHashMap<String, File>()
    
    // Map: transferId -> MimeType
    private val servingMimeTypes = ConcurrentHashMap<String, String>()

    fun startServer() {
        if (!isAlive) {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d(TAG, "Local File Server started on port $listeningPort")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }

    fun stopServer() {
        if (isAlive) {
            stop()
            Log.d(TAG, "Local File Server stopped")
        }
    }
    
    fun getPort(): Int = listeningPort

    fun serveFile(transferId: String, file: File, mimeType: String) {
        servingFiles[transferId] = file
        servingMimeTypes[transferId] = mimeType
    }

    fun stopServing(transferId: String) {
        servingFiles.remove(transferId)
        servingMimeTypes.remove(transferId)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: $uri")
        
        // Expected URI: /files/<transfer_id>/<filename>
        // Or just: /files/<transfer_id> (simplest)
        
        val segments = uri.split("/").filter { it.isNotEmpty() }
        if (segments.size >= 2 && segments[0] == "files") {
            val transferId = segments[1]
            val file = servingFiles[transferId]
            
            if (file != null && file.exists()) {
                val mimeType = servingMimeTypes[transferId] ?: "application/octet-stream"
                return try {
                    val fis = FileInputStream(file)
                    newChunkedResponse(Response.Status.OK, mimeType, fis)
                } catch (e: Exception) {
                     newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Error")
                }
            }
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File Not Found")
    }
}
