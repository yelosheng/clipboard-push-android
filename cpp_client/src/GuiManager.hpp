#pragma once

#include <d3d11.h>
#include <imgui.h>
#include <imgui_impl_dx11.h>
#include <imgui_impl_win32.h>
#include <string>
#include <vector>
#include <windows.h>

class GuiManager {
public:
  static GuiManager &Instance();

  bool Init(HINSTANCE hInstance, int nCmdShow);
  void Run();
  void Cleanup();

  // UI State Helpers
  void AddLog(const std::string &message);
  void SetStatus(bool connected, const std::string &statusText);
  void SetNetworkClient(void *client);

private:
  GuiManager() = default;

  void *m_networkClient = nullptr;

  // DX11 Objects
  ID3D11Device *g_pd3dDevice = nullptr;
  ID3D11DeviceContext *g_pd3dDeviceContext = nullptr;
  IDXGISwapChain *g_pSwapChain = nullptr;
  ID3D11RenderTargetView *g_mainRenderTargetView = nullptr;

  HWND m_hwnd = nullptr;
  WNDCLASSEXW m_wc = {0};

  // Helper functions
  bool CreateDeviceD3D(HWND hWnd);
  void CleanupDeviceD3D();
  void CreateRenderTarget();
  void CleanupRenderTarget();

  // UI Rendering
  void RenderUI();
  void RenderSettings();
  void RenderQRCode();
  void RenderLogs();

  // State
  bool m_showSettings = false;
  bool m_connected = false;
  std::string m_statusText = "Disconnected";
  std::vector<std::string> m_logs;

  // Tray Icon
  NOTIFYICONDATA m_nid = {};
  void InitTrayIcon();
  void RemoveTrayIcon();
};
