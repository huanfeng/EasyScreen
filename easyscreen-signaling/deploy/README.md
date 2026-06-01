# EasyScreen 信令服务 VPS 部署

部署后获得：
- 一个公网可访问的 WebSocket 信令端点：`wss://your.domain/ws`
- 一个可在任意浏览器打开的 Web 控制端：`https://your.domain/`
- 自动 TLS（Let's Encrypt 通过 Caddy 自动签发与续期）
- systemd 守护 + 崩溃自动重启

> TURN 不在此处部署 —— 客户端已经默认使用公共 `openrelay.metered.ca`。
> 若以后想自建 TURN（更稳定 / 流量更可控），再装 coturn 即可。

## 准备

- 一台 Linux VPS（Debian 12 / Ubuntu 22.04 推荐；其他发行版微调即可）
- 一个域名，A 记录已指向 VPS 公网 IP
- 本地装好 Go 1.21+

## 三步上手

### 1. 本地交叉编译

```bash
cd easyscreen-signaling
bash deploy/build-linux.sh
# 产物：deploy/dist/easyscreen-signaling-linux-amd64.tar.gz
```

### 2. 上传到 VPS

```bash
scp deploy/dist/easyscreen-signaling-linux-amd64.tar.gz user@your.vps:/tmp/
scp deploy/install-vps.sh                              user@your.vps:/tmp/
```

### 3. VPS 上一键安装

```bash
ssh user@your.vps
cd /tmp
mkdir -p easyscreen-signaling
tar -xzf easyscreen-signaling-linux-amd64.tar.gz -C easyscreen-signaling
sudo bash install-vps.sh your.domain
```

脚本完成后会输出测试命令。打开浏览器访问 `https://your.domain/` 应看到 EasyScreen 输入界面。

## 客户端配置

### Android

打开 App → 主页 → 设置图标 → 服务器地址输入：

```
wss://your.domain/ws
```

回车保存，再次回到主页即可使用。

### Web

直接访问 `https://your.domain/`，默认就连到同源信令。

## 运维常用

```bash
# 看日志
sudo journalctl -u easyscreen-signaling -f

# 重启服务
sudo systemctl restart easyscreen-signaling

# 看 Caddy 状态
sudo systemctl status caddy

# 看活跃房间数
curl https://your.domain/health
```

## 升级

```bash
# 本地重编
bash deploy/build-linux.sh

# 上传并替换
scp deploy/dist/easyscreen-signaling-linux-amd64.tar.gz user@your.vps:/tmp/
ssh user@your.vps
cd /tmp && tar -xzf easyscreen-signaling-linux-amd64.tar.gz -C /tmp/upd
sudo cp /tmp/upd/easyscreen-signaling /opt/easyscreen/
sudo rm -rf /opt/easyscreen/web && sudo cp -r /tmp/upd/web /opt/easyscreen/web
sudo chown -R easyscreen:easyscreen /opt/easyscreen
sudo systemctl restart easyscreen-signaling
```

## App 在线更新（可选）

让信令服务作为 GitHub Release 的国内缓存代理，老人端 App 即可自动检查/下载更新。在 systemd service 的环境变量中加入：

- `EASYSCREEN_GITHUB_REPO=<owner>/<repo>`（留空则关闭更新端点）
- 可选 `EASYSCREEN_GITHUB_TOKEN`（提高 GitHub API 限流）
- `EASYSCREEN_APP_CACHE_DIR`（可写目录，如 `/var/lib/easyscreen/cache`）

编辑 `easyscreen-signaling.service` 的 `Environment=` 行后执行 `sudo systemctl daemon-reload && sudo systemctl restart easyscreen-signaling`。反代（Caddy）需放行 `/app/version.json` 与 `/app/download`（APK 可能较大，注意上传/下载体大小限制）。

## 资源占用参考

- 信令服务本身：闲时 < 10 MB 内存、< 1% CPU
- Caddy：~ 30 MB 内存
- 1 核 1 G 的小机绰绰有余

## 安全说明

- TLS 自动加密：Caddy 处理所有 HTTPS / WSS
- 信令服务只监听 `127.0.0.1:8081`，不会被外网直接访问
- 6 位房号生命周期短（host 离线 60s 自动清），不存在长期占用
- 媒体流是 P2P 加密（DTLS-SRTP），服务器看不到内容
- TURN 走公共节点（`openrelayproject:openrelayproject`）—— 这些凭证公开，第三方也能用，**不存在敏感凭证泄漏问题**

## 可选：自建 TURN（高级）

若公共 TURN 不稳定 / 流量受限，装 coturn：

```bash
sudo apt install -y coturn
sudo sed -i 's/#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn

cat | sudo tee /etc/turnserver.conf <<'EOF'
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
realm=your.domain
user=easyscreen:换成强随机字符串
external-ip=你的VPS公网IP
no-multicast-peers
no-cli
EOF

sudo systemctl enable --now coturn
# 防火墙：3478/udp, 3478/tcp, 5349/tcp, 49152-65535/udp
```

然后让客户端使用：`turn:easyscreen:你的密码@your.domain:3478`（具体如何注入到 App 见 `WebRTCManager.kt`，目前预留了 `turnServers` 参数）
