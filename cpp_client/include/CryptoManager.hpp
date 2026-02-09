#pragma once
#include "Common.hpp"

class CryptoManager {
public:
  static CryptoManager &Instance();

  void SetKey(const std::string &base64Key);
  bool HasKey() const { return !m_key.empty(); }

  // Returns nonce + ciphertext + tag
  std::optional<std::vector<uint8_t>> Encrypt(const std::vector<uint8_t> &data);
  std::optional<std::vector<uint8_t>> Encrypt(const std::string &data);

  // Expects nonce + ciphertext + tag
  std::optional<std::vector<uint8_t>>
  Decrypt(const std::vector<uint8_t> &encryptedData);

  std::optional<std::string>
  DecryptToString(const std::vector<uint8_t> &encryptedData);

private:
  CryptoManager() = default;
  std::vector<uint8_t> m_key;
};
