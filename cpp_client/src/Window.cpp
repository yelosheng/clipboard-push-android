#include "Window.hpp"
#include "Common.hpp"


namespace ui {

bool Window::Create(PCWSTR lpWindowName, DWORD dwStyle, DWORD dwExStyle, int x,
                    int y, int nWidth, int nHeight, HWND hWndParent,
                    HMENU hMenu) {
  WNDCLASSW wc = {0};
  wc.lpfnWndProc = Window::WindowProc;
  wc.hInstance = GetModuleHandle(nullptr);
  wc.lpszClassName = ClassName();
  wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
  wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);

  RegisterClassW(&wc);

  m_hwnd = CreateWindowExW(dwExStyle, ClassName(), lpWindowName, dwStyle, x, y,
                           nWidth, nHeight, hWndParent, hMenu,
                           GetModuleHandle(nullptr), this);

  return (m_hwnd != nullptr);
}

LRESULT CALLBACK Window::WindowProc(HWND hwnd, UINT uMsg, WPARAM wParam,
                                    LPARAM lParam) {
  Window *pSelf = nullptr;

  if (uMsg == WM_NCCREATE) {
    CREATESTRUCT *pCreate = (CREATESTRUCT *)lParam;
    pSelf = (Window *)pCreate->lpCreateParams;
    SetWindowLongPtr(hwnd, GWLP_USERDATA, (LONG_PTR)pSelf);
    pSelf->m_hwnd = hwnd;
  } else {
    pSelf = (Window *)GetWindowLongPtr(hwnd, GWLP_USERDATA);
  }

  if (pSelf) {
    return pSelf->HandleMessage(uMsg, wParam, lParam);
  } else {
    return DefWindowProc(hwnd, uMsg, wParam, lParam);
  }
}

LRESULT Window::HandleMessage(UINT uMsg, WPARAM wParam, LPARAM lParam) {
  switch (uMsg) {
  case WM_DESTROY:
    PostQuitMessage(0);
    return 0;
  }
  return DefWindowProc(m_hwnd, uMsg, wParam, lParam);
}

} // namespace ui
