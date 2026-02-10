#pragma once
#include <vector>
#include <string>
#include <optional>
#include <cstdint>

namespace ClipboardPush {
namespace Crypto {

// Input: Key (32 bytes), Plaintext
// Output: [Nonce(12)] + [Ciphertext] + [Tag(16)]
std::optional<std::vector<uint8_t>> Encrypt(const std::vector<uint8_t>& key, const std::vector<uint8_t>& plaintext);

// Input: Key (32 bytes), EncryptedData ([Nonce] + [Ciphertext] + [Tag])
// Output: Plaintext
std::optional<std::vector<uint8_t>> Decrypt(const std::vector<uint8_t>& key, const std::vector<uint8_t>& encryptedData);

// Base64 helpers
std::string ToBase64(const std::vector<uint8_t>& data);
std::vector<uint8_t> FromBase64(const std::string& data);

// Generate random 256-bit key as base64
std::string GenerateKeyBase64();

// Helper to decode the base64 room key
std::vector<uint8_t> DecodeKey(const std::string& base64Key);

}
}
