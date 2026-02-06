package com.example.clipboardman.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager(private val roomKeyBase64: String?) {

    private val KEY_ALGORITHM = "AES"
    private val BLOCK_MODE = "GCM"
    private val PADDING = "NoPadding"
    private val TRANSFORMATION = "$KEY_ALGORITHM/$BLOCK_MODE/$PADDING"
    private val TAG_LENGTH_BIT = 128
    private val IV_LENGTH_BYTE = 12

    private val secretKey: SecretKey?

    init {
        secretKey = if (roomKeyBase64 != null) {
            val decodedKey = Base64.decode(roomKeyBase64, Base64.DEFAULT)
            SecretKeySpec(decodedKey, 0, decodedKey.size, "AES")
        } else {
            null
        }
    }

    fun encrypt(bytes: ByteArray): ByteArray? {
        if (secretKey == null) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(bytes)
        return iv + encryptedBytes
    }

    fun decrypt(encryptedData: ByteArray): ByteArray? {
        if (secretKey == null) return null
        
        // Extract IV (first 12 bytes)
        if (encryptedData.size < IV_LENGTH_BYTE) return null
        val iv = encryptedData.copyOfRange(0, IV_LENGTH_BYTE)
        val ciphertext = encryptedData.copyOfRange(IV_LENGTH_BYTE, encryptedData.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        return cipher.doFinal(ciphertext)
    }

    // Stream Support for Files (To prevent OOM)
    fun encryptFile(inputStream: InputStream, outputStream: OutputStream) {
        if (secretKey == null) return
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        outputStream.write(iv) // Write IV first
        
        val buffer = ByteArray(1024 * 8)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val encryptedChunk = cipher.update(buffer, 0, bytesRead)
            if (encryptedChunk != null) {
                outputStream.write(encryptedChunk)
            }
        }
        val finalChunk = cipher.doFinal()
        if (finalChunk != null) {
            outputStream.write(finalChunk)
        }
    }

    fun decryptFile(inputStream: InputStream, outputStream: OutputStream) {
        if (secretKey == null) return
        
        // Read IV first
        val iv = ByteArray(IV_LENGTH_BYTE)
        if (inputStream.read(iv) != IV_LENGTH_BYTE) {
            throw IllegalArgumentException("Stream too short, cannot read IV")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val buffer = ByteArray(1024 * 8)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val decryptedChunk = cipher.update(buffer, 0, bytesRead)
            if (decryptedChunk != null) {
                outputStream.write(decryptedChunk)
            }
        }
        val finalChunk = cipher.doFinal()
        if (finalChunk != null) {
            outputStream.write(finalChunk)
        }
    }
}
