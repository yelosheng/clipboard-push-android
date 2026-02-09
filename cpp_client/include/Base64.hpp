#pragma once
#include "Common.hpp"

class Base64 {
public:
  static std::string Encode(const std::vector<uint8_t> &data);
  static std::vector<uint8_t> Decode(const std::string &input);
};
