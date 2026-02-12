#pragma once

#include <QByteArray>
#include <QString>
#include <vector>
#include <optional>

namespace ClipboardPush {

/**
 * AES-256-GCM encryption compatible with Python cryptography library.
 *
 * Encrypted format: [12-byte nonce] + [ciphertext] + [16-byte tag]
 *
 * The Python side uses:
 *   from cryptography.hazmat.primitives.ciphers.aead import AESGCM
 *   nonce = os.urandom(12)
 *   ciphertext = aesgcm.encrypt(nonce, data, None)  # Returns ciphertext+tag
 *   return nonce + ciphertext
 */
class CryptoManager {
public:
    static constexpr size_t KEY_SIZE = 32;     // 256 bits
    static constexpr size_t NONCE_SIZE = 12;   // 96 bits
    static constexpr size_t TAG_SIZE = 16;     // 128 bits

    CryptoManager() = default;
    explicit CryptoManager(const QString& keyBase64);
    explicit CryptoManager(const QByteArray& key);
    ~CryptoManager() = default;

    // Key management
    bool setKey(const QString& keyBase64);
    bool setKey(const QByteArray& key);
    QString keyBase64() const;
    bool hasKey() const { return m_key.size() == KEY_SIZE; }

    // Generate new 256-bit key
    static QByteArray generateKey();
    static QString generateKeyBase64();

    /**
     * Encrypt data using AES-256-GCM.
     * Returns: [12-byte nonce] + [ciphertext] + [16-byte tag]
     */
    std::optional<QByteArray> encrypt(const QByteArray& plaintext) const;

    /**
     * Decrypt data using AES-256-GCM.
     * Expects: [12-byte nonce] + [ciphertext] + [16-byte tag]
     */
    std::optional<QByteArray> decrypt(const QByteArray& ciphertext) const;

    // Convenience methods for string content
    std::optional<QString> encryptToBase64(const QString& text) const;
    std::optional<QString> decryptFromBase64(const QString& base64Ciphertext) const;

private:
    QByteArray m_key;
};

} // namespace ClipboardPush
