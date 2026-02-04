#pragma once
#include <string>
#include <vector>
#include <optional>

enum class ClipboardType {
    None,
    Text,
    Image,
    FileList
};

class Clipboard {
public:
    // 获取剪贴板当前内容类型
    static ClipboardType GetType();

    // 文本操作
    static bool SetText(const std::string& text);
    static std::optional<std::string> GetText();

    // 文件操作 (CF_HDROP)
    static bool SetFiles(const std::vector<std::string>& filePaths);
    static std::vector<std::string> GetFiles();

    // 图片操作 (简化版，保存到临时文件)
    // 完整的内存 CF_DIB 处理比较复杂，这里为了工程可维护性，建议结合 GDI+ 保存到文件
    static bool SetImageFromFile(const std::string& imagePath);
    static std::optional<std::string> GetImageToTempFile();

    // 编码转换辅助 (公开以便 GUI 使用)
    static std::wstring Utf8ToWide(const std::string& str);
    static std::string WideToUtf8(const std::wstring& wstr);

private:
};
