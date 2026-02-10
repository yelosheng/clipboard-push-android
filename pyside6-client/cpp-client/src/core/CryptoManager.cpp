#include "CryptoManager.h"
#include "Logger.h"

#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/err.h>
#include <cstring>

namespace ClipboardPush {

CryptoManager::CryptoManager(const QString& keyBase64) {
    setKey(keyBase64);
}

CryptoManager::CryptoManager(const QByteArray& key) {
    setKey(key);
}

bool CryptoManager::setKey(const QString& keyBase64) {
    QByteArray decoded = QByteArray::fromBase64(keyBase64.toUtf8());
    return setKey(decoded);
}

bool CryptoManager::setKey(const QByteArray& key) {
    if (key.size() != KEY_SIZE) {
        LOG_ERROR("Invalid key size: {} (expected {})", key.size(), KEY_SIZE);
        return false;
    }
    m_key = key;
    return true;
}

QString CryptoManager::keyBase64() const {
    return QString::fromUtf8(m_key.toBase64());
}

QByteArray CryptoManager::generateKey() {
    QByteArray key(KEY_SIZE, 0);
    if (RAND_bytes(reinterpret_cast<unsigned char*>(key.data()), KEY_SIZE) != 1) {
        LOG_ERROR("Failed to generate random key");
        return QByteArray();
    }
    return key;
}

QString CryptoManager::generateKeyBase64() {
    QByteArray key = generateKey();
    return QString::fromUtf8(key.toBase64());
}

std::optional<QByteArray> CryptoManager::encrypt(const QByteArray& plaintext) const {
    if (!hasKey()) {
        LOG_ERROR("Cannot encrypt: no key set");
        return std::nullopt;
    }

    // Generate random nonce
    QByteArray nonce(NONCE_SIZE, 0);
    if (RAND_bytes(reinterpret_cast<unsigned char*>(nonce.data()), NONCE_SIZE) != 1) {
        LOG_ERROR("Failed to generate nonce");
        return std::nullopt;
    }

    // Create cipher context
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        LOG_ERROR("Failed to create cipher context");
        return std::nullopt;
    }

    // Initialize encryption
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to initialize encryption");
        return std::nullopt;
    }

    // Set nonce length
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to set nonce length");
        return std::nullopt;
    }

    // Set key and nonce
    if (EVP_EncryptInit_ex(ctx, nullptr, nullptr,
            reinterpret_cast<const unsigned char*>(m_key.constData()),
            reinterpret_cast<const unsigned char*>(nonce.constData())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to set key and nonce");
        return std::nullopt;
    }

    // Encrypt
    QByteArray ciphertext(plaintext.size() + TAG_SIZE, 0);
    int outLen = 0;

    if (EVP_EncryptUpdate(ctx,
            reinterpret_cast<unsigned char*>(ciphertext.data()),
            &outLen,
            reinterpret_cast<const unsigned char*>(plaintext.constData()),
            plaintext.size()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Encryption update failed");
        return std::nullopt;
    }

    int cipherLen = outLen;

    // Finalize
    if (EVP_EncryptFinal_ex(ctx,
            reinterpret_cast<unsigned char*>(ciphertext.data()) + outLen,
            &outLen) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Encryption finalization failed");
        return std::nullopt;
    }

    cipherLen += outLen;

    // Get tag
    QByteArray tag(TAG_SIZE, 0);
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_SIZE, tag.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to get authentication tag");
        return std::nullopt;
    }

    EVP_CIPHER_CTX_free(ctx);

    // Format: nonce + ciphertext + tag (compatible with Python AESGCM)
    ciphertext.resize(cipherLen);
    QByteArray result = nonce + ciphertext + tag;

    return result;
}

std::optional<QByteArray> CryptoManager::decrypt(const QByteArray& encryptedData) const {
    if (!hasKey()) {
        LOG_ERROR("Cannot decrypt: no key set");
        return std::nullopt;
    }

    if (encryptedData.size() < NONCE_SIZE + TAG_SIZE) {
        LOG_ERROR("Encrypted data too short: {} bytes", encryptedData.size());
        return std::nullopt;
    }

    // Extract components
    QByteArray nonce = encryptedData.left(NONCE_SIZE);
    QByteArray tag = encryptedData.right(TAG_SIZE);
    QByteArray ciphertext = encryptedData.mid(NONCE_SIZE, encryptedData.size() - NONCE_SIZE - TAG_SIZE);

    // Create cipher context
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        LOG_ERROR("Failed to create cipher context");
        return std::nullopt;
    }

    // Initialize decryption
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to initialize decryption");
        return std::nullopt;
    }

    // Set nonce length
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to set nonce length");
        return std::nullopt;
    }

    // Set key and nonce
    if (EVP_DecryptInit_ex(ctx, nullptr, nullptr,
            reinterpret_cast<const unsigned char*>(m_key.constData()),
            reinterpret_cast<const unsigned char*>(nonce.constData())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to set key and nonce");
        return std::nullopt;
    }

    // Decrypt
    QByteArray plaintext(ciphertext.size(), 0);
    int outLen = 0;

    if (EVP_DecryptUpdate(ctx,
            reinterpret_cast<unsigned char*>(plaintext.data()),
            &outLen,
            reinterpret_cast<const unsigned char*>(ciphertext.constData()),
            ciphertext.size()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Decryption update failed");
        return std::nullopt;
    }

    int plainLen = outLen;

    // Set expected tag
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_SIZE,
            const_cast<char*>(tag.constData())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Failed to set authentication tag");
        return std::nullopt;
    }

    // Finalize and verify tag
    if (EVP_DecryptFinal_ex(ctx,
            reinterpret_cast<unsigned char*>(plaintext.data()) + outLen,
            &outLen) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        LOG_ERROR("Decryption failed: authentication tag mismatch");
        return std::nullopt;
    }

    plainLen += outLen;
    plaintext.resize(plainLen);

    EVP_CIPHER_CTX_free(ctx);

    return plaintext;
}

std::optional<QString> CryptoManager::encryptToBase64(const QString& text) const {
    auto encrypted = encrypt(text.toUtf8());
    if (!encrypted) {
        return std::nullopt;
    }
    return QString::fromUtf8(encrypted->toBase64());
}

std::optional<QString> CryptoManager::decryptFromBase64(const QString& base64Ciphertext) const {
    QByteArray ciphertext = QByteArray::fromBase64(base64Ciphertext.toUtf8());
    auto decrypted = decrypt(ciphertext);
    if (!decrypted) {
        return std::nullopt;
    }
    return QString::fromUtf8(*decrypted);
}

} // namespace ClipboardPush
