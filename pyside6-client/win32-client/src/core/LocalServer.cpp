#include "LocalServer.h"
#include "Utils.h"
#include "Logger.h"
#include "Config.h"
#include "Crypto.h"
#include "SyncLogic.h"
#include "httplib.h"
#include <filesystem>
#include <fstream>
#include <random>

namespace fs = std::filesystem;

namespace ClipboardPush {

LocalServer& LocalServer::Instance() {
    static LocalServer instance;
    return instance;
}

LocalServer::LocalServer() {
    m_ip = Utils::GetLocalIPAddress();
    
    // Pick a random port between 50000 and 60000
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(50000, 60000);
    m_port = dis(gen);
}

LocalServer::~LocalServer() {
    Stop();
}

void LocalServer::Start() {
    if (m_running) return;
    LOG_INFO("LAN Sync: Local Server starting at http://%s:%d", m_ip.c_str(), m_port);
    m_running = true;
    m_thread = std::thread(&LocalServer::Run, this);
}

void LocalServer::Stop() {
    m_running = false;
    if (m_thread.joinable()) {
        m_thread.detach(); 
    }
}

void LocalServer::Run() {
    httplib::Server svr;

    svr.set_logger([](const httplib::Request& req, const httplib::Response& res) {
        LOG_INFO("LAN Req: %s %s -> %d", req.method.c_str(), req.path.c_str(), res.status);
    });

    svr.Post("/upload", [this](const httplib::Request& req, httplib::Response& res) {
        auto& config = Config::Instance().Data();
        if (req.get_header_value("X-Room-ID") != config.room_id) {
            res.status = 401;
            return;
        }

        if (req.form.has_file("file")) {
            auto file = req.form.get_file("file");
            std::string filename = file.filename;
            fs::path downloadDir(Utils::ToWide(config.download_path));
            if (!fs::exists(downloadDir)) fs::create_directories(downloadDir);

            fs::path filePath = downloadDir / Utils::ToWide(filename);
            int count = 1;
            std::wstring stem = filePath.stem().wstring();
            std::wstring ext = filePath.extension().wstring();
            while (fs::exists(filePath)) {
                filePath = downloadDir / (stem + L"_" + std::to_wstring(count++) + ext);
            }

            std::ofstream ofs(filePath, std::ios::binary);
            ofs.write(file.content.data(), file.content.size());
            ofs.close();

            LOG_INFO("LAN Upload: Saved %s", filename.c_str());
            
            // Basic type detection
            std::string type = "file";
            std::string extStr = filePath.extension().string();
            std::transform(extStr.begin(), extStr.end(), extStr.begin(), ::tolower);
            if (extStr == ".png" || extStr == ".jpg" || extStr == ".jpeg" || extStr == ".bmp") {
                type = "image";
            }

            ProcessReceivedFile(filePath.string(), filename, type);

            res.status = 200;
            res.set_content("OK", "text/plain");
        } else {
            res.status = 400;
        }
    });

    svr.Get("/files/(.*)", [this](const httplib::Request& req, httplib::Response& res) {
        auto& config = Config::Instance().Data();
        if (req.get_header_value("X-Room-ID") != config.room_id) {
            res.status = 401;
            return;
        }

        std::string filename = req.matches[1];
        fs::path downloadDir(Utils::ToWide(config.download_path));
        fs::path tempDir = fs::path(Utils::GetAppDir()) / L"temp";
        
        fs::path filePath = downloadDir / Utils::ToWide(filename);
        if (!fs::exists(filePath)) {
            filePath = tempDir / Utils::ToWide(filename);
        }

        std::error_code ec;
        if (fs::exists(filePath, ec) && fs::is_regular_file(filePath, ec)) {
            std::ifstream ifs(filePath, std::ios::binary);
            std::string content((std::istreambuf_iterator<char>(ifs)), (std::istreambuf_iterator<char>()));
            res.set_content(content, "application/octet-stream");
        } else {
            res.status = 404;
        }
    });

    svr.Get("/ping", [](const httplib::Request&, httplib::Response& res) {
        res.set_content("pong", "text/plain");
    });

    svr.Get("/probe", [](const httplib::Request&, httplib::Response& res) {
        res.set_content("ok", "text/plain");
    });

    if (!svr.listen("0.0.0.0", m_port)) {
        LOG_ERROR("Local Server failed to start on port %d", m_port);
        m_running = false;
    }
}

}
