package com.example.clipboardman.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [CryptoManager] (AES-GCM encrypt/decrypt).
 *
 * CryptoManager expects a Base64-encoded AES key.  We generate a deterministic
 * 32-byte (AES-256) raw key and encode it with the standard Java Base64 encoder,
 * which produces the same alphabet that android.util.Base64.DEFAULT expects.
 *
 * Robolectric is required because CryptoManager calls android.util.Base64 in its
 * init block.
 */
@RunWith(RobolectricTestRunner::class)
class CryptoManagerTest {

    companion object {
        // A deterministic 32-byte AES-256 key, Base64-encoded.
        private val KEY_BASE64: String =
            java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        // A different 32-byte key to use for wrong-key tests.
        private val OTHER_KEY_BASE64: String =
            java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    }

    // -----------------------------------------------------------------------
    // Basic round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val cm = CryptoManager(KEY_BASE64)
        val original = "Hello, clipboard!".toByteArray()
        val encrypted = cm.encrypt(original)
        assertNotNull("encrypt() must not return null when key is set", encrypted)
        val decrypted = cm.decrypt(encrypted!!)
        assertNotNull("decrypt() must not return null for valid ciphertext", decrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `encrypt then decrypt works for a long message`() {
        val cm = CryptoManager(KEY_BASE64)
        val original = "A".repeat(10_000).toByteArray()
        val encrypted = cm.encrypt(original)
        assertNotNull(encrypted)
        val decrypted = cm.decrypt(encrypted!!)
        assertNotNull(decrypted)
        assertArrayEquals(original, decrypted)
    }

    // -----------------------------------------------------------------------
    // IV randomness
    // -----------------------------------------------------------------------

    @Test
    fun `encrypt produces different ciphertext on each call due to random IV`() {
        val cm = CryptoManager(KEY_BASE64)
        val data = "same input".toByteArray()
        val enc1 = cm.encrypt(data)
        val enc2 = cm.encrypt(data)
        assertNotNull(enc1)
        assertNotNull(enc2)
        // Different IVs → different output bytes (probability of collision is negligible)
        assertFalse(
            "Two encryptions of the same plaintext must differ (random IV)",
            enc1!!.contentEquals(enc2!!)
        )
    }

    @Test
    fun `encrypted output is longer than plaintext by at least IV size`() {
        // AES-GCM prepends a 12-byte IV and appends a 16-byte authentication tag.
        val cm = CryptoManager(KEY_BASE64)
        val plaintext = "short".toByteArray()
        val encrypted = cm.encrypt(plaintext)
        assertNotNull(encrypted)
        assertTrue(
            "Ciphertext must be at least 28 bytes longer than plaintext (12 IV + 16 tag)",
            encrypted!!.size >= plaintext.size + 28
        )
    }

    // -----------------------------------------------------------------------
    // Empty plaintext edge case
    // -----------------------------------------------------------------------

    @Test
    fun `encrypt and decrypt empty byte array`() {
        val cm = CryptoManager(KEY_BASE64)
        val encrypted = cm.encrypt(ByteArray(0))
        assertNotNull("encrypt() of empty array must succeed", encrypted)
        // For AES-GCM with empty plaintext: output is 12-byte IV + 16-byte tag = 28 bytes
        assertEquals(
            "Encrypted empty plaintext should be 28 bytes (IV + GCM tag)",
            28,
            encrypted!!.size
        )
        val decrypted = cm.decrypt(encrypted)
        assertNotNull(decrypted)
        assertEquals("Decrypting encrypted empty array must yield 0 bytes", 0, decrypted!!.size)
    }

    // -----------------------------------------------------------------------
    // Wrong-key decryption
    // -----------------------------------------------------------------------

    @Test
    fun `decrypt with wrong key returns null or throws`() {
        val cm1 = CryptoManager(KEY_BASE64)
        val cm2 = CryptoManager(OTHER_KEY_BASE64)
        val encrypted = cm1.encrypt("secret message".toByteArray())
        assertNotNull(encrypted)
        try {
            val result = cm2.decrypt(encrypted!!)
            // Some JVM/provider implementations may return null instead of throwing.
            assertNull("Expected null from wrong-key decrypt", result)
        } catch (e: Exception) {
            // javax.crypto.AEADBadTagException (subclass of BadPaddingException) is the
            // expected signal of an authentication tag mismatch — this is also acceptable.
            assertTrue(
                "Expected AEADBadTagException or BadPaddingException, got ${e::class.simpleName}",
                e is javax.crypto.BadPaddingException || e is java.security.GeneralSecurityException
            )
        }
    }

    // -----------------------------------------------------------------------
    // Null-key behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `encrypt returns null when key is null`() {
        val cm = CryptoManager(null)
        val result = cm.encrypt("data".toByteArray())
        assertNull("encrypt() must return null when CryptoManager has no key", result)
    }

    @Test
    fun `decrypt returns null when key is null`() {
        val cm = CryptoManager(null)
        // Build a fake payload that looks plausible (12-byte IV + some bytes).
        val fakePayload = ByteArray(28)
        val result = cm.decrypt(fakePayload)
        assertNull("decrypt() must return null when CryptoManager has no key", result)
    }

    // -----------------------------------------------------------------------
    // Tampered ciphertext (authentication-tag verification)
    // -----------------------------------------------------------------------

    @Test
    fun `decrypt detects tampered ciphertext`() {
        val cm = CryptoManager(KEY_BASE64)
        val encrypted = cm.encrypt("authentic message".toByteArray())
        assertNotNull(encrypted)

        // Flip a bit in the ciphertext portion (after the 12-byte IV)
        val tampered = encrypted!!.copyOf()
        tampered[12] = (tampered[12].toInt() xor 0xFF).toByte()

        try {
            val result = cm.decrypt(tampered)
            assertNull("Tampered ciphertext should yield null", result)
        } catch (e: Exception) {
            assertTrue(
                "Expected a security exception for tampered data, got ${e::class.simpleName}",
                e is javax.crypto.BadPaddingException || e is java.security.GeneralSecurityException
            )
        }
    }

    @Test
    fun `decrypt returns null when data is shorter than IV length`() {
        val cm = CryptoManager(KEY_BASE64)
        // Only 11 bytes — less than the required 12-byte IV
        val tooShort = ByteArray(11)
        val result = cm.decrypt(tooShort)
        assertNull("decrypt() must return null when input is shorter than IV", result)
    }

    // -----------------------------------------------------------------------
    // Stream (file) round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `encryptFile then decryptFile returns original bytes`() {
        val cm = CryptoManager(KEY_BASE64)
        val original = "File content for streaming encrypt/decrypt test.".toByteArray()

        val encBuf = java.io.ByteArrayOutputStream()
        cm.encryptFile(original.inputStream(), encBuf)
        val encryptedBytes = encBuf.toByteArray()

        assertTrue(
            "Encrypted stream must be longer than plaintext",
            encryptedBytes.size > original.size
        )

        val decBuf = java.io.ByteArrayOutputStream()
        cm.decryptFile(encryptedBytes.inputStream(), decBuf)
        assertArrayEquals(original, decBuf.toByteArray())
    }
}
