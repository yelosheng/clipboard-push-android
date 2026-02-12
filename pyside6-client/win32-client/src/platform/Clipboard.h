#pragma once
#include <string>
#include <vector>
#include <optional>
#include <cstdint>

namespace ClipboardPush {
namespace Platform {

enum class ClipboardType { None, Text, Image, Files };

struct ClipboardData {
    ClipboardType type = ClipboardType::None;
    std::string text; // UTF-8
    std::vector<std::string> files; // UTF-8 paths
    std::vector<uint8_t> image_data; // PNG bytes
};

class Clipboard {
public:
    static ClipboardData Get();
    static bool SetText(const std::string& text);
    static bool SetFiles(const std::vector<std::string>& files);
    static bool SetImage(const std::vector<uint8_t>& pngData);
    static bool SetImageFromFile(const std::string& filePath);
};

}
}
