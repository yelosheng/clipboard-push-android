#include "CryptoManager.hpp"
#include "Base64.hpp"
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <spdlog/spdlog.h>
#include <stdexcept>


// AES-256-GCM Parameters
constexpr int IV_SIZE = 12;
constexpr int TAG_SIZE = 16;
constexpr int KEY_SIZE = 32;

CryptoManager &CryptoManager::Instance() {
  static CryptoManager instance;
  return instance;
}

void CryptoManager::SetKey(const std::string &base64Key) {
  if (base64Key.empty())
    return;

  std::vector<uint8_t> decoded = Base64::Decode(base64Key);
  if (decoded.size() != KEY_SIZE) {
    spdlog::error("Invalid Key Size: {}. Expected 32 for AES-256.",
                  decoded.size());
    return;
  }
  m_key = decoded;
}

std::optional<std::vector<uint8_t>>
CryptoManager::Encrypt(const std::vector<uint8_t> &data) {
  if (m_key.empty())
    return std::nullopt;

  EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
  if (!ctx)
    return std::nullopt;

  std::vector<uint8_t> iv(IV_SIZE);
  if (!RAND_bytes(iv.data(), IV_SIZE)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  std::vector<uint8_t> outBuf(data.size() +
                              TAG_SIZE); // Ciphertext is same length + Tag
  int outLen = 0;
  int cipherLen = 0;

  // Init
  if (1 != EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  // Set Key and IV
  if (1 != EVP_EncryptInit_ex(ctx, NULL, NULL, m_key.data(), iv.data())) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  // Update (Encrypt)
  if (1 != EVP_EncryptUpdate(ctx, outBuf.data(), &outLen, data.data(),
                             data.size())) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }
  cipherLen = outLen;

  // Finalize
  if (1 != EVP_EncryptFinal_ex(ctx, outBuf.data() + outLen, &outLen)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }
  cipherLen += outLen;

  // Get Tag
  std::vector<uint8_t> tag(TAG_SIZE);
  if (1 !=
      EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_SIZE, tag.data())) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  EVP_CIPHER_CTX_free(ctx);

  // Python format: [IV (12)] + [Ciphertext] + [Tag (16)]
  std::vector<uint8_t> result;
  result.reserve(IV_SIZE + cipherLen + TAG_SIZE);
  result.insert(result.end(), iv.begin(), iv.end());
  result.insert(result.end(), outBuf.begin(), outBuf.begin() + cipherLen);
  result.insert(result.end(), tag.begin(), tag.end());

  return result;
}

std::optional<std::vector<uint8_t>>
CryptoManager::Encrypt(const std::string &data) {
  std::vector<uint8_t> vec(data.begin(), data.end());
  return Encrypt(vec);
}

std::optional<std::vector<uint8_t>>
CryptoManager::Decrypt(const std::vector<uint8_t> &encryptedData) {
  if (m_key.empty())
    return std::nullopt;
  if (encryptedData.size() < IV_SIZE + TAG_SIZE)
    return std::nullopt;

  // Parse: [IV (12)] ... [Tag (16)]
  size_t dataLen = encryptedData.size() - IV_SIZE - TAG_SIZE;

  const uint8_t *iv = encryptedData.data();
  const uint8_t *ciphertext = encryptedData.data() + IV_SIZE;
  const uint8_t *tag = encryptedData.data() + IV_SIZE + dataLen;

  EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
  if (!ctx)
    return std::nullopt;

  int outLen = 0;
  int finalLen = 0;
  std::vector<uint8_t> outBuf(dataLen);

  // Init
  if (1 != EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  // Set Key and IV
  if (1 != EVP_DecryptInit_ex(ctx, NULL, NULL, m_key.data(), iv)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  // Process Ciphertext
  if (1 !=
      EVP_DecryptUpdate(ctx, outBuf.data(), &outLen, ciphertext, dataLen)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }
  finalLen = outLen;

  // Set Tag for Verification
  if (1 !=
      EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_SIZE, (void *)tag)) {
    EVP_CIPHER_CTX_free(ctx);
    return std::nullopt;
  }

  // Finalize (Verify Tag)
  int ret = EVP_DecryptFinal_ex(ctx, outBuf.data() + outLen, &outLen);
  EVP_CIPHER_CTX_free(ctx);

  if (ret > 0) {
    finalLen += outLen;
    outBuf.resize(finalLen);
    return outBuf;
  } else {
    spdlog::error("Decryption Failed: Tag mismatch or corrupted data");
    return std::nullopt;
  }
}

std::optional<std::string>
CryptoManager::DecryptToString(const std::vector<uint8_t> &encryptedData) {
  auto result = Decrypt(encryptedData);
  if (result) {
    return std::string(result->begin(), result->end());
  }
  return std::nullopt;
}
