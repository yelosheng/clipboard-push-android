#pragma once
#include "Common.hpp"

namespace ui {

class Window {
public:
  Window() : m_hwnd(nullptr) {}
  virtual ~Window() {
    if (m_hwnd)
      ::DestroyWindow(m_hwnd);
  }

  // Prevents copying
  Window(const Window &) = delete;
  Window &operator=(const Window &) = delete;

  HWND GetHandle() const { return m_hwnd; }

  bool Create(PCWSTR lpWindowName, DWORD dwStyle, DWORD dwExStyle = 0,
              int x = CW_USEDEFAULT, int y = CW_USEDEFAULT,
              int nWidth = CW_USEDEFAULT, int nHeight = CW_USEDEFAULT,
              HWND hWndParent = nullptr, HMENU hMenu = nullptr);

  void Show(int nCmdShow = SW_SHOW) { ::ShowWindow(m_hwnd, nCmdShow); }

protected:
  virtual LRESULT HandleMessage(UINT uMsg, WPARAM wParam, LPARAM lParam);
  virtual PCWSTR ClassName() const = 0;

  HWND m_hwnd;

private:
  static LRESULT CALLBACK WindowProc(HWND hwnd, UINT uMsg, WPARAM wParam,
                                     LPARAM lParam);
};

} // namespace ui
