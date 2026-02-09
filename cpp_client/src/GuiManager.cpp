#include "GuiManager.hpp"
#include "ClipboardManager.hpp"
#include "ConfigManager.hpp"
#include "HotkeyManager.hpp"
#include "NetworkClient.hpp"
#include <imgui.h>
#include <imgui_impl_dx11.h>
#include <imgui_impl_win32.h>
#include <qrencode.h>
#include <spdlog/spdlog.h>
#include <string>
#include <tchar.h>
#include <vector>

// Forward declare message handler from imgui_impl_win32.cpp
extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND hWnd,
                                                             UINT msg,
                                                             WPARAM wParam,
                                                             LPARAM lParam);

#include <shellapi.h>
#define WM_TRAY_ICON (WM_USER + 1)
#define ID_TRAY_ICON 1001
#define ID_MENU_EXIT 1002
#define ID_MENU_SHOW 1003

GuiManager &GuiManager::Instance() {
  static GuiManager instance;
  return instance;
}

void GuiManager::SetNetworkClient(void *client) { m_networkClient = client; }

void GuiManager::AddLog(const std::string &message) {
  m_logs.push_back(message);
  if (m_logs.size() > 100)
    m_logs.erase(m_logs.begin());
}

void GuiManager::SetStatus(bool connected, const std::string &statusText) {
  m_connected = connected;
  m_statusText = statusText;
}

bool GuiManager::Init(HINSTANCE hInstance, int nCmdShow) {
  // Register Class
  WNDCLASSEXW wc = {
      sizeof(wc),
      CS_CLASSDC,
      [](HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) -> LRESULT {
        if (ImGui_ImplWin32_WndProcHandler(hWnd, msg, wParam, lParam))
          return true;

        switch (msg) {
        case WM_CREATE:
          AddClipboardFormatListener(hWnd);
          return 0;
        case WM_DESTROY:
          RemoveClipboardFormatListener(hWnd);
          ::PostQuitMessage(0);
          return 0;
        case WM_CLIPBOARDUPDATE:
          GuiManager::Instance().AddLog("Clipboard Updated");
          // Here we could trigger clipboard read if needed
          // But usually NetworkClient handles incoming, and we need logic for
          // outgoing. For now, assume HotkeyManager handles manual push, and we
          // might add auto-push later.
          return 0;
        case WM_HOTKEY:
          HotkeyManager::Instance().HandleHotkey(wParam);
          return 0;
        case WM_SIZE:
          if (wParam != SIZE_MINIMIZED) {
            // Handled by Render loop logic typically if using SwapChain Resize
          }
          return 0;
        case WM_SYSCOMMAND:
          if ((wParam & 0xfff0) == SC_KEYMENU) // Disable ALT application menu
            return 0;
          break;
        }
        return ::DefWindowProcW(hWnd, msg, wParam, lParam);
      },
      0L,
      0L,
      hInstance,
      NULL,
      NULL,
      NULL,
      NULL,
      L"ClipboardManGui",
      NULL};

  ::RegisterClassExW(&wc);
  m_wc = wc;

  // Create Window
  m_hwnd =
      ::CreateWindowW(wc.lpszClassName, L"Clipboard Man", WS_OVERLAPPEDWINDOW,
                      100, 100, 800, 600, NULL, NULL, wc.hInstance, NULL);

  // Initialize Direct3D
  if (!CreateDeviceD3D(m_hwnd)) {
    CleanupDeviceD3D();
    ::UnregisterClassW(wc.lpszClassName, wc.hInstance);
    return false;
  }

  // Show the window
  ::ShowWindow(m_hwnd, nCmdShow);
  ::UpdateWindow(m_hwnd);

  // Setup Dear ImGui context
  IMGUI_CHECKVERSION();
  ImGui::CreateContext();
  ImGuiIO &io = ImGui::GetIO();
  (void)io;
  io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
  // io.ConfigFlags |= ImGuiConfigFlags_DockingEnable; // Docking not available
  // in standard imgui master

  // Setup Dear ImGui style
  ImGui::StyleColorsDark();
  ImGuiStyle &style = ImGui::GetStyle();
  style.WindowRounding = 5.0f;
  style.FrameRounding = 4.0f;
  style.Colors[ImGuiCol_WindowBg] = ImVec4(0.15f, 0.15f, 0.15f, 1.0f);

  // Setup Platform/Renderer backends
  ImGui_ImplWin32_Init(m_hwnd);
  ImGui_ImplDX11_Init(g_pd3dDevice, g_pd3dDeviceContext);

  InitTrayIcon();

  return true;
}

void GuiManager::InitTrayIcon() {
  m_nid.cbSize = sizeof(NOTIFYICONDATA);
  m_nid.hWnd = m_hwnd;
  m_nid.uID = ID_TRAY_ICON;
  m_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
  m_nid.uCallbackMessage = WM_TRAY_ICON;
  m_nid.hIcon = LoadIcon(NULL, IDI_APPLICATION); // Or load from resource
  wcscpy_s(m_nid.szTip, L"Clipboard Man");
  Shell_NotifyIcon(NIM_ADD, &m_nid);
}

void GuiManager::RemoveTrayIcon() { Shell_NotifyIcon(NIM_DELETE, &m_nid); }

bool GuiManager::CreateDeviceD3D(HWND hWnd) {
  DXGI_SWAP_CHAIN_DESC sd;
  ZeroMemory(&sd, sizeof(sd));
  sd.BufferCount = 2;
  sd.BufferDesc.Width = 0;
  sd.BufferDesc.Height = 0;
  sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
  sd.BufferDesc.RefreshRate.Numerator = 60;
  sd.BufferDesc.RefreshRate.Denominator = 1;
  sd.Flags = DXGI_SWAP_CHAIN_FLAG_ALLOW_MODE_SWITCH;
  sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
  sd.OutputWindow = hWnd;
  sd.SampleDesc.Count = 1;
  sd.SampleDesc.Quality = 0;
  sd.Windowed = TRUE;
  sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

  UINT createDeviceFlags = 0;
  D3D_FEATURE_LEVEL featureLevel;
  const D3D_FEATURE_LEVEL featureLevelArray[2] = {
      D3D_FEATURE_LEVEL_11_0,
      D3D_FEATURE_LEVEL_10_0,
  };

  if (D3D11CreateDeviceAndSwapChain(
          NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, createDeviceFlags,
          featureLevelArray, 2, D3D11_SDK_VERSION, &sd, &g_pSwapChain,
          &g_pd3dDevice, &featureLevel, &g_pd3dDeviceContext) != S_OK)
    return false;

  CreateRenderTarget();
  return true;
}

void GuiManager::CreateRenderTarget() {
  ID3D11Texture2D *pBackBuffer;
  g_pSwapChain->GetBuffer(0, IID_PPV_ARGS(&pBackBuffer));
  g_pd3dDevice->CreateRenderTargetView(pBackBuffer, NULL,
                                       &g_mainRenderTargetView);
  pBackBuffer->Release();
}

void GuiManager::CleanupRenderTarget() {
  if (g_mainRenderTargetView) {
    g_mainRenderTargetView->Release();
    g_mainRenderTargetView = NULL;
  }
}

void GuiManager::CleanupDeviceD3D() {
  CleanupRenderTarget();
  if (g_pSwapChain) {
    g_pSwapChain->Release();
    g_pSwapChain = NULL;
  }
  if (g_pd3dDeviceContext) {
    g_pd3dDeviceContext->Release();
    g_pd3dDeviceContext = NULL;
  }
  if (g_pd3dDevice) {
    g_pd3dDevice->Release();
    g_pd3dDevice = NULL;
  }
}

void GuiManager::Cleanup() {
  ImGui_ImplDX11_Shutdown();
  ImGui_ImplWin32_Shutdown();
  ImGui::DestroyContext();
  CleanupDeviceD3D();
  ::DestroyWindow(m_hwnd);
  ::UnregisterClassW(m_wc.lpszClassName, m_wc.hInstance);
}

void GuiManager::Run() {
  bool done = false;
  while (!done) {
    MSG msg;
    while (::PeekMessage(&msg, NULL, 0U, 0U, PM_REMOVE)) {
      ::TranslateMessage(&msg);
      ::DispatchMessage(&msg);
      if (msg.message == WM_QUIT)
        done = true;
    }
    if (done)
      break;

    RenderUI();
  }
}

void GuiManager::RenderUI() {
  ImGui_ImplDX11_NewFrame();
  ImGui_ImplWin32_NewFrame();
  ImGui::NewFrame();

  ImGui::SetNextWindowPos(ImVec2(0, 0));
  ImGui::SetNextWindowSize(ImGui::GetIO().DisplaySize);
  ImGui::Begin("Dashboard", nullptr,
               ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                   ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoCollapse);

  {
    ImGui::Text("Status: ");
    ImGui::SameLine();
    if (m_connected)
      ImGui::TextColored(ImVec4(0, 1, 0, 1), "Connected");
    else
      ImGui::TextColored(ImVec4(1, 0, 0, 1), "Disconnected");

    ImGui::SameLine();
    ImGui::Text("| %s", m_statusText.c_str());
  }

  ImGui::Separator();
  ImGui::Columns(2, "MainLayout", false);
  ImGui::SetColumnWidth(0, 350);

  RenderSettings();
  ImGui::Dummy(ImVec2(0, 20));
  RenderQRCode();

  ImGui::NextColumn();
  RenderLogs();

  ImGui::Columns(1);
  ImGui::End();

  ImGui::Render();
  const float clear_color_with_alpha[4] = {0.10f, 0.10f, 0.10f, 1.00f};
  g_pd3dDeviceContext->OMSetRenderTargets(1, &g_mainRenderTargetView, NULL);
  g_pd3dDeviceContext->ClearRenderTargetView(g_mainRenderTargetView,
                                             clear_color_with_alpha);
  ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());
  g_pSwapChain->Present(1, 0);
}

void GuiManager::RenderSettings() {
  ImGui::Text("Configuration");
  ImGui::Separator();

  static char server_url[256] = "";
  static char room_key[256] = "";
  static bool init = false;

  if (!init) {
    auto &config = ConfigManager::Instance().GetConfig();
    strcpy_s(server_url, config.server_url.c_str());
    strcpy_s(room_key, config.room_key.c_str());
    init = true;
  }

  ImGui::InputText("Server URL", server_url, 256);
  ImGui::InputText("Room Key", room_key, 256, ImGuiInputTextFlags_Password);

  if (ImGui::Button("Save & Connect")) {
    auto &config = ConfigManager::Instance().GetConfig();
    config.server_url = server_url;
    config.room_key = room_key;
    ConfigManager::Instance().Save();
    AddLog("Settings saved. Reconnecting...");

    if (m_networkClient) {
      static_cast<NetworkClient *>(m_networkClient)->Reconnect();
    }
  }
}

void GuiManager::RenderQRCode() {
  ImGui::Text("Mobile Pairing");
  ImGui::Separator();

  auto &config = ConfigManager::Instance().GetConfig();
  std::string qrContent = nlohmann::json({{"server_url", config.server_url},
                                          {"room_key", config.room_key},
                                          {"device_id", config.device_id}})
                              .dump();

  QRcode *qr =
      QRcode_encodeString(qrContent.c_str(), 0, QR_ECLEVEL_L, QR_MODE_8, 1);
  if (qr) {
    int size = qr->width;
    float pixelSize = 4.0f;
    ImDrawList *drawList = ImGui::GetWindowDrawList();
    ImVec2 p = ImGui::GetCursorScreenPos();

    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        unsigned char b = qr->data[y * size + x];
        if (b & 1) {
          drawList->AddRectFilled(
              ImVec2(p.x + x * pixelSize, p.y + y * pixelSize),
              ImVec2(p.x + (x + 1) * pixelSize, p.y + (y + 1) * pixelSize),
              IM_COL32(255, 255, 255, 255));
        } else {
          drawList->AddRectFilled(
              ImVec2(p.x + x * pixelSize, p.y + y * pixelSize),
              ImVec2(p.x + (x + 1) * pixelSize, p.y + (y + 1) * pixelSize),
              IM_COL32_BLACK);
        }
      }
    }
    ImGui::Dummy(ImVec2(size * pixelSize, size * pixelSize));
    QRcode_free(qr);
  } else {
    ImGui::Text("Failed to generate QR Code");
  }
}

void GuiManager::RenderLogs() {
  ImGui::Text("Activity Log");
  ImGui::Separator();

  ImGui::BeginChild("LogRegion", ImVec2(0, 0), true,
                    ImGuiWindowFlags_HorizontalScrollbar);
  for (const auto &log : m_logs) {
    ImGui::TextUnformatted(log.c_str());
  }
  if (ImGui::GetScrollY() >= ImGui::GetScrollMaxY())
    ImGui::SetScrollHereY(1.0f);
  ImGui::EndChild();
}
