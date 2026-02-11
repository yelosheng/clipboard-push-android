#include "Crypto.h"
#include "Logger.h"
#include <windows.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <random>
#include <algorithm>

#ifndef NT_SUCCESS
#define NT_SUCCESS(Status) (((NTSTATUS)(Status)) >= 0)
#endif

namespace ClipboardPush {
namespace Crypto {

std::string ToBase64(const std::vector<uint8_t>& data) {
    if (data.empty()) return "";
    DWORD len = 0;
    if (!CryptBinaryToStringA(data.data(), (DWORD)data.size(), CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, NULL, &len)) return "";
    std::string out(len, '\0');
    if (!CryptBinaryToStringA(data.data(), (DWORD)data.size(), CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, &out[0], &len)) return "";
    // Remove null terminator if added by API
    if (!out.empty() && out.back() == '\0') out.pop_back();
    return out;
}

std::vector<uint8_t> FromBase64(const std::string& data) {
    if (data.empty()) return {};
    DWORD len = 0;
    if (!CryptStringToBinaryA(data.c_str(), (DWORD)data.length(), CRYPT_STRING_BASE64, NULL, &len, NULL, NULL)) return {};
    std::vector<uint8_t> out(len);
    if (!CryptStringToBinaryA(data.c_str(), (DWORD)data.length(), CRYPT_STRING_BASE64, out.data(), &len, NULL, NULL)) return {};
    return out;
}

std::string GenerateKeyBase64() {
    std::vector<uint8_t> key(32);
    BCryptGenRandom(NULL, key.data(), (ULONG)key.size(), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    return ToBase64(key);
}

std::vector<uint8_t> DecodeKey(const std::string& base64Key) {
    return FromBase64(base64Key);
}

// Wrapper for CNG Provider
class BCryptProvider {
    BCRYPT_ALG_HANDLE hAlg = NULL;
    bool valid = false;
public:
    BCryptProvider() {
        if (NT_SUCCESS(BCryptOpenAlgorithmProvider(&hAlg, BCRYPT_AES_ALGORITHM, NULL, 0))) {
            if (NT_SUCCESS(BCryptSetProperty(hAlg, BCRYPT_CHAINING_MODE, (PUCHAR)BCRYPT_CHAIN_MODE_GCM, sizeof(BCRYPT_CHAIN_MODE_GCM), 0))) {
                valid = true;
            }
        }
    }
    ~BCryptProvider() { if (hAlg) BCryptCloseAlgorithmProvider(hAlg, 0); }
    operator BCRYPT_ALG_HANDLE() const { return hAlg; }
    bool isValid() const { return valid; }
};

std::optional<std::vector<uint8_t>> Encrypt(const std::vector<uint8_t>& key, const std::vector<uint8_t>& plaintext) {
    static BCryptProvider prov;
    if (!prov.isValid()) {
        LOG_ERROR("BCrypt provider init failed");
        return std::nullopt;
    }

    // Generate Nonce using cryptographically secure RNG
    std::vector<uint8_t> nonce(12);
    if (!NT_SUCCESS(BCryptGenRandom(NULL, nonce.data(), (ULONG)nonce.size(), BCRYPT_USE_SYSTEM_PREFERRED_RNG))) {
        LOG_ERROR("Failed to generate random nonce");
        return std::nullopt;
    }

    // Import Key
    BCRYPT_KEY_HANDLE hKey = NULL;
    DWORD keyObjLen = 0;
    DWORD res = 0;
    BCryptGetProperty(prov, BCRYPT_OBJECT_LENGTH, (PUCHAR)&keyObjLen, sizeof(DWORD), &res, 0);
    std::vector<uint8_t> keyObj(keyObjLen);
    
    if (!NT_SUCCESS(BCryptGenerateSymmetricKey(prov, &hKey, keyObj.data(), keyObjLen, (PUCHAR)key.data(), (ULONG)key.size(), 0))) {
        LOG_ERROR("Failed to generate key");
        return std::nullopt;
    }

    // Auth Info (GCM)
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO authInfo;
    BCRYPT_INIT_AUTH_MODE_INFO(authInfo);
    authInfo.pbNonce = nonce.data();
    authInfo.cbNonce = (ULONG)nonce.size();
    authInfo.cbTag = 16;
    std::vector<uint8_t> tag(16);
    authInfo.pbTag = tag.data();

    // Encrypt
    std::vector<uint8_t> ciphertext(plaintext.size());
    ULONG bytesDone = 0;
    // Note: For GCM, block size doesn't imply padding in the same way, but buffer must be sufficient
    
    if (!NT_SUCCESS(BCryptEncrypt(hKey, (PUCHAR)plaintext.data(), (ULONG)plaintext.size(), &authInfo, NULL, 0, ciphertext.data(), (ULONG)ciphertext.size(), &bytesDone, 0))) {
        LOG_ERROR("Encryption failed");
        BCryptDestroyKey(hKey);
        return std::nullopt;
    }
    
    BCryptDestroyKey(hKey);

    // Combine: Nonce + Ciphertext + Tag
    std::vector<uint8_t> result;
    result.reserve(nonce.size() + ciphertext.size() + tag.size());
    result.insert(result.end(), nonce.begin(), nonce.end());
    result.insert(result.end(), ciphertext.begin(), ciphertext.end());
    result.insert(result.end(), tag.begin(), tag.end());
    return result;
}

std::optional<std::vector<uint8_t>> Decrypt(const std::vector<uint8_t>& key, const std::vector<uint8_t>& encryptedData) {
    if (encryptedData.size() < 12 + 16) return std::nullopt;

    static BCryptProvider prov;
    if (!prov.isValid()) return std::nullopt;

    // Parse
    std::vector<uint8_t> nonce(encryptedData.begin(), encryptedData.begin() + 12);
    // Ciphertext is Middle
    size_t cipherLen = encryptedData.size() - 12 - 16;
    // Caution: empty ciphertext is valid
    std::vector<uint8_t> ciphertext;
    if (cipherLen > 0) {
        ciphertext.assign(encryptedData.begin() + 12, encryptedData.begin() + 12 + cipherLen);
    }
    // Tag is End
    std::vector<uint8_t> tag(encryptedData.end() - 16, encryptedData.end());

    // Import Key
    DWORD keyObjLen = 0;
    DWORD res = 0;
    BCryptGetProperty(prov, BCRYPT_OBJECT_LENGTH, (PUCHAR)&keyObjLen, sizeof(DWORD), &res, 0);
    std::vector<uint8_t> keyObj(keyObjLen);
    BCRYPT_KEY_HANDLE hKey = NULL;
    
    if (!NT_SUCCESS(BCryptGenerateSymmetricKey(prov, &hKey, keyObj.data(), keyObjLen, (PUCHAR)key.data(), (ULONG)key.size(), 0))) {
        return std::nullopt;
    }

    // Auth Info
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO authInfo;
    BCRYPT_INIT_AUTH_MODE_INFO(authInfo);
    authInfo.pbNonce = nonce.data();
    authInfo.cbNonce = (ULONG)nonce.size();
    authInfo.pbTag = tag.data();
    authInfo.cbTag = (ULONG)tag.size();

    // Decrypt
    std::vector<uint8_t> plaintext(cipherLen);
    ULONG bytesDone = 0;
    
    // Handle empty ciphertext case if needed, but BCryptDecrypt should handle 0 length
    PUCHAR pInput = cipherLen > 0 ? ciphertext.data() : NULL;
    PUCHAR pOutput = cipherLen > 0 ? plaintext.data() : NULL;

    if (!NT_SUCCESS(BCryptDecrypt(hKey, pInput, (ULONG)cipherLen, &authInfo, NULL, 0, pOutput, (ULONG)cipherLen, &bytesDone, 0))) {
        LOG_ERROR("Decryption failed");
        BCryptDestroyKey(hKey);
        return std::nullopt;
    }

    BCryptDestroyKey(hKey);
    return plaintext;
}

}
}
