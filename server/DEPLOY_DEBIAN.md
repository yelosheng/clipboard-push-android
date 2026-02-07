# Clipboard Man Server - Debian Deployment Guide

This guide details how to deploy the Clipboard Man server to a Debian machine (e.g., 192.168.2.62) and configure Nginx as a reverse proxy for HTTPS access on a custom port.

**Target:** `https://kxkl.tk:12577`

## Prerequisites

On your Debian server:
- Python 3.8+ (`sudo apt install python3 python3-venv python3-pip`)
- Nginx (`sudo apt install nginx`)
- Git (optional, or copy files via SCP/SFTP)

---

## Step 1: Install Application

1.  **Create Directory**:
    ```bash
    sudo mkdir -p /opt/clipboard-man
    sudo chown $USER:$USER /opt/clipboard-man
    ```

2.  **Upload Files**:
    Copy the contents of your local `server/` directory to `/opt/clipboard-man` on the server.
    *Ensure `relay_server.py` and `requirements.txt` are present.*

3.  **Setup Python Environment**:
    ```bash
    cd /opt/clipboard-man
    python3 -m venv venv
    source venv/bin/activate
    pip install -r requirements.txt
    pip install gunicorn gevent  # Production server dependencies
    ```

---

## Step 2: Configure Systemd Service

Create a service to keep the app running in the background.

1.  **Create Service File**:
    ```bash
    sudo nano /etc/systemd/system/clipboard-man.service
    ```

2.  **Paste Configuration**:
    ```ini
    [Unit]
    Description=Clipboard Man Relay Server
    After=network.target

    [Service]
    User=www-data
    Group=www-data
    WorkingDirectory=/opt/clipboard-man
    Environment="PATH=/opt/clipboard-man/venv/bin"
    # Port 5000 is internal, Nginx will proxy to it
    ExecStart=/opt/clipboard-man/venv/bin/gunicorn --worker-class gevent --workers 1 --bind 127.0.0.1:5000 relay_server:app
    Restart=always

    [Install]
    WantedBy=multi-user.target
    ```

3.  **Start Service**:
    ```bash
    # Fix permissions so www-data can read files
    sudo chown -R www-data:www-data /opt/clipboard-man

    sudo systemctl daemon-reload
    sudo systemctl enable clipboard-man
    sudo systemctl start clipboard-man
    sudo systemctl status clipboard-man
    ```

---

## Step 3: Configure SSL (HTTPS)

You need SSL certificates for `kxkl.tk`.

### Option A: Use Existing Certificates (Recommended)
If you already have `fullchain.pem` and `privkey.pem`, upload them to `/etc/nginx/ssl/`.

### Option B: Self-Signed (If testing)
```bash
sudo mkdir -p /etc/nginx/ssl
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/nginx/ssl/nginx.key \
    -out /etc/nginx/ssl/nginx.crt \
    -subj "/CN=kxkl.tk"
```

---

## Step 4: Configure Nginx Reverse Proxy

1.  **Create Config File**:
    ```bash
    sudo nano /etc/nginx/sites-available/clipboard-man
    ```

2.  **Paste Configuration**:
    *Replace certificate paths if yours are different.*
    ```nginx
    server {
        listen 12577 ssl;
        server_name kxkl.tk;

        # SSL Configuration
        ssl_certificate /etc/nginx/ssl/nginx.crt;  # Or your fullchain.pem
        ssl_certificate_key /etc/nginx/ssl/nginx.key; # Or your privkey.pem
        
        # Security Optimizations (Optional but recommended)
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers HIGH:!aNULL:!MD5;

        # Upload Size Limit (Important for files)
        client_max_body_size 100M;

        location / {
            proxy_pass http://127.0.0.1:5000;
            proxy_http_version 1.1;
            
            # WebSocket Support (Critical)
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
            
            # Forward Headers
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
    ```

3.  **Enable Site**:
    ```bash
    sudo ln -s /etc/nginx/sites-available/clipboard-man /etc/nginx/sites-enabled/
    sudo nginx -t  # Test config
    sudo systemctl restart nginx
    ```

4.  **Firewall (If using ufw)**:
    ```bash
    sudo ufw allow 12577/tcp
    ```

---

## Step 5: Update Clients

### PC Client (`config.json`)
Edit `client/config.json`:
```json
{
    "relay_server_url": "https://kxkl.tk:12577",
    ...
}
```

### Android Client
1.  Open App -> Settings.
2.  Server Address: `kxkl.tk:12577` (Assuming default protocol logic handles https if port is 443, but for custom port check if you need `https://` prefix in input or toggle 'Use HTTPS').
    *   *Note: If using self-signed cert, Android might reject it. You may need to install the CA or trust user certs.*
