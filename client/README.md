# Clipboard Man Web Test Client

简单的 Web 界面，用于测试 Clipboard Man 服务器和 Android 应用。

## 功能

- ✅ 发送文本消息
- ✅ 上传并发送图片
- ✅ 上传并发送各种文件
- ✅ 快速测试按钮（预设消息）
- ✅ 实时服务器状态检测
- ✅ 消息历史记录
- ✅ 现代化响应式界面

## 使用方法

### 启动方式 1: 直接打开（推荐）

双击 `index.html` 文件，会在默认浏览器中打开。

### 启动方式 2: 使用 Python HTTP 服务器

```powershell
# 进入 client 目录
cd d:\android-dev\clipboard-man\client

# Python 3
python -m http.server 8080

# 然后在浏览器访问
# http://localhost:8080
```

### 启动方式 3: 使用 Node.js

```powershell
# 安装 http-server
npm install -g http-server

# 启动服务器
cd d:\android-dev\clipboard-man\client
http-server -p 8080

# 浏览器访问
# http://localhost:8080
```

## 测试流程

1. **确保服务器运行**
   ```powershell
   cd d:\android-dev\clipboard-man\server
   python app.py
   ```

2. **确保 Android 模拟器运行并已连接 app**
   ```powershell
   # 启动模拟器
   .\start_emulator.bat
   
   # 运行 app
   .\run_on_emulator.bat
   ```

3. **打开 Web 客户端**
   - 双击 `index.html`
   - 检查页面顶部显示 "Server Online"（绿色）

4. **开始测试**
   - 发送文本：输入文本后点击 "Send Text"
   - 快速测试：点击预设按钮发送测试数据
   - 发送图片：选择图片文件并发送
   - 发送文件：选择任意文件并发送

5. **验证结果**
   - 检查 Android 模拟器是否收到通知
   - 检查消息是否显示在 app 列表中
   - 检查文本是否复制到剪贴板

## 快捷键

- `Ctrl + Enter` 或 `Cmd + Enter`：在文本框中发送消息

## 功能说明

### 文本消息
- 支持多行文本
- 支持 URL
- 支持代码片段
- 快速测试按钮提供常用场景

### 图片
- 支持所有常见图片格式（JPG, PNG, GIF 等）
- 实时预览功能
- 自动验证文件类型

### 文件
- 支持任意文件类型
- 显示文件名和大小
- 根据扩展名显示对应图标

### 消息历史
- 自动保存到浏览器 localStorage
- 显示时间戳和消息类型
- 成功/失败状态标识
- 支持清除历史

## 注意事项

- 确保服务器在 `localhost:9661` 运行
- 如果修改了服务器端口，需要编辑 `app.js` 中的 `SERVER_URL`
- 文件大小限制取决于服务器配置
- 历史记录最多保存 50 条

## 故障排除

**服务器显示离线**
- 检查服务器是否运行：`http://localhost:9661`
- 确认防火墙没有阻止连接

**发送失败**
- 查看浏览器控制台（F12）的错误信息
- 检查服务器日志
- 验证文件格式和大小

**消息未到达 app**
- 确认 app 已连接到服务器
- 检查 app 的连接状态指示器
- 查看 app 日志
