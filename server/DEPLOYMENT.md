# Clipboard Man Server 部署指南

## 📋 部署到服务器（Nginx 反向代理 + HTTPS）

### 1️⃣ 服务器环境准备

```bash
# 安装 Python 3.8+
sudo apt update
sudo apt install python3 python3-pip python3-venv

# 安装 Nginx
sudo apt install nginx

# 安装 Certbot（用于 Let's Encrypt SSL 证书）
sudo apt install certbot python3-certbot-nginx
```

### 2️⃣ 上传代码并安装依赖

```bash
# 在服务器上创建项目目录
mkdir -p /opt/clipboard-man
cd /opt/clipboard-man

# 上传代码（使用 scp、git 或其他方式）
# 例如：scp -r ./server/* user@your-server:/opt/clipboard-man/

# 创建虚拟环境
python3 -m venv venv
source venv/bin/activate

# 安装依赖
pip install -r requirements.txt
```

### 3️⃣ 配置 systemd 服务（后台运行）

创建服务文件：`/etc/systemd/system/clipboard-man.service`

```ini
[Unit]
Description=Clipboard Man Server
After=network.target

[Service]
Type=simple
User=www-data
Group=www-data
WorkingDirectory=/opt/clipboard-man
Environment="PATH=/opt/clipboard-man/venv/bin"
ExecStart=/opt/clipboard-man/venv/bin/python app.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable clipboard-man
sudo systemctl start clipboard-man
sudo systemctl status clipboard-man
```

### 4️⃣ 配置 Nginx 反向代理

复制 `nginx_example.conf` 的内容到 Nginx 配置：

```bash
sudo nano /etc/nginx/sites-available/clipboard-man
```

**修改配置中的以下内容：**
- `server_name` 改为你的域名
- SSL 证书路径（先不要配置，等获取证书后再配置）

启用站点：

```bash
sudo ln -s /etc/nginx/sites-available/clipboard-man /etc/nginx/sites-enabled/
sudo nginx -t  # 测试配置
```

### 5️⃣ 获取 SSL 证书

```bash
# 使用 Certbot 自动获取并配置 Let's Encrypt 证书
sudo certbot --nginx -d your-domain.com

# Certbot 会自动修改 Nginx 配置并添加 SSL 证书
```

### 6️⃣ 重启 Nginx

```bash
sudo systemctl restart nginx
```

### 7️⃣ 防火墙配置

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw reload
```

---

## 🧪 测试部署

### 测试 HTTPS 连接

```bash
curl https://your-domain.com/
```

应该返回服务器状态 JSON。

### 测试 WebSocket 连接

使用浏览器开发者工具或 WebSocket 客户端测试：

```javascript
// 在浏览器控制台测试 Socket.IO
const socket = io('https://your-domain.com');
socket.on('connect', () => console.log('连接成功'));
```

---

## 📱 更新客户端配置

### PC 客户端（client/config.json）

```json
{
    "server_url": "https://your-domain.com",
    "download_path": "D:/temp/ClipboardMan",
    "auto_copy_image": true,
    "auto_copy_file": true
}
```

### Android 客户端

修改 Android 代码中的服务器地址：

```kotlin
// 在适当的配置文件或代码中修改
const val SERVER_URL = "https://your-domain.com"
const val WS_URL = "wss://your-domain.com/ws"  // 注意是 wss://
```

---

## 🔍 故障排查

### 查看服务日志

```bash
# Flask 服务日志
sudo journalctl -u clipboard-man -f

# Nginx 访问日志
sudo tail -f /var/log/nginx/clipboard-man-access.log

# Nginx 错误日志
sudo tail -f /var/log/nginx/clipboard-man-error.log
```

### 常见问题

1. **WebSocket 连接失败**
   - 检查 Nginx 配置中的 `proxy_set_header Upgrade` 和 `Connection` 头
   - 确认防火墙允许 80 和 443 端口

2. **文件上传失败**
   - 检查 `client_max_body_size` 设置
   - 确认 Flask 的 `MAX_CONTENT_LENGTH` 配置

3. **CORS 错误**
   - 当前配置允许所有来源 (`*`)，如需限制可修改 `app.py` 中的 CORS 设置

---

## 🔐 安全建议

1. **限制 CORS 来源**：
   ```python
   CORS(app, origins=['https://your-domain.com'])
   ```

2. **添加认证**：考虑添加 API 密钥或 JWT 认证

3. **定期更新证书**：Certbot 会自动续期，确保 cron 任务正常

4. **限流**：在 Nginx 中添加 rate limiting

---

## 📊 监控

可以使用以下工具监控服务：
- `htop` - 查看进程和资源使用
- `netstat -tlnp` - 查看端口占用
- Nginx 日志分析工具

---

## 🔄 更新部署

```bash
cd /opt/clipboard-man
git pull  # 如果使用 git
source venv/bin/activate
pip install -r requirements.txt
sudo systemctl restart clipboard-man
```
