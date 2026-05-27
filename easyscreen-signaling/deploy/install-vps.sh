#!/usr/bin/env bash
# 在 VPS 上跑：完成目录创建、用户、systemd 注册、Caddy 安装与启用
# 使用：sudo bash install-vps.sh <你的域名>
set -euo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
    echo "用法：sudo bash $0 <你的域名>"
    exit 1
fi

# 必须 root
if [ "$EUID" -ne 0 ]; then
    echo "请用 sudo 运行"
    exit 1
fi

# 解包位置：假设你已经把 tar.gz 放到 /tmp 并解压到 /tmp/easyscreen-signaling
SRC_DIR="${SRC_DIR:-/tmp/easyscreen-signaling}"
if [ ! -d "$SRC_DIR" ] || [ ! -f "$SRC_DIR/easyscreen-signaling" ]; then
    echo "找不到 $SRC_DIR/easyscreen-signaling"
    echo "请先：cd /tmp && tar -xzf easyscreen-signaling-linux-amd64.tar.gz -C easyscreen-signaling --strip-components=0"
    echo "（或自己解到 $SRC_DIR）"
    exit 1
fi

echo "==> 1/6 创建系统用户与目录"
id -u easyscreen >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin easyscreen
mkdir -p /opt/easyscreen
mkdir -p /var/log/caddy

echo "==> 2/6 复制二进制和静态资源"
cp -f "$SRC_DIR/easyscreen-signaling" /opt/easyscreen/
chmod +x /opt/easyscreen/easyscreen-signaling
rm -rf /opt/easyscreen/web
cp -r "$SRC_DIR/web" /opt/easyscreen/web
chown -R easyscreen:easyscreen /opt/easyscreen

echo "==> 3/6 安装 systemd unit"
# 假设你也把 deploy/easyscreen-signaling.service 一起放到 /tmp/easyscreen-signaling
if [ -f "$SRC_DIR/deploy/easyscreen-signaling.service" ]; then
    cp -f "$SRC_DIR/deploy/easyscreen-signaling.service" /etc/systemd/system/
else
    # 否则生成最小版本
    cat > /etc/systemd/system/easyscreen-signaling.service <<EOF
[Unit]
Description=EasyScreen Signaling Server
After=network-online.target
[Service]
Type=simple
User=easyscreen
WorkingDirectory=/opt/easyscreen
ExecStart=/opt/easyscreen/easyscreen-signaling
Restart=on-failure
RestartSec=5s
Environment=EASYSCREEN_ADDR=127.0.0.1:8081
[Install]
WantedBy=multi-user.target
EOF
fi
systemctl daemon-reload
systemctl enable --now easyscreen-signaling

echo "==> 4/6 安装 Caddy（用于自动 TLS）"
if ! command -v caddy >/dev/null 2>&1; then
    apt update
    apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
    apt update
    apt install -y caddy
fi

echo "==> 5/6 生成 Caddyfile"
cat > /etc/caddy/Caddyfile <<EOF
${DOMAIN} {
    reverse_proxy localhost:8081 {
        transport http {
            read_timeout 24h
            response_header_timeout 5m
        }
    }
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "strict-origin-when-cross-origin"
        -Server
    }
}
EOF
systemctl reload caddy || systemctl restart caddy

echo "==> 6/6 防火墙：开放 80 / 443"
if command -v ufw >/dev/null 2>&1; then
    ufw allow 80/tcp || true
    ufw allow 443/tcp || true
fi

echo ""
echo "✅ 部署完成"
echo ""
echo "测试："
echo "  curl https://${DOMAIN}/health"
echo "  浏览器访问：https://${DOMAIN}/"
echo "  Android / Web 端服务器 URL：wss://${DOMAIN}/ws"
echo ""
echo "查看日志：journalctl -u easyscreen-signaling -f"
