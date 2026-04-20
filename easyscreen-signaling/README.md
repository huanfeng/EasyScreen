# EasyScreen 信令服务器

## 快速启动

### 编译
```bash
cd easyscreen-signaling
go build -o easyscreen-signaling.exe .
```

### 运行
```bash
./easyscreen-signaling.exe
```

### 配置端口
在 `main.go` 中修改 `addr := ":8081"` 为所需端口。

## API 端点

### WebSocket 连接
```
ws://localhost:8081/ws
```

### 健康检查
```
http://localhost:8081/health
```

## WebSocket 消息协议

### 消息结构
```json
{
  "type": "register|join|offer|answer|candidate|error|room_ready|disconnect|ping|pong",
  "room_id": "123456",
  "payload": {},
  "data": {}
}
```

### 流程说明

#### 被控端（父母）
1. 连接 WebSocket
2. 发送 `{"type": "register"}`
3. 收到 `{"type": "room_ready", "room_id": "123456"}`
4. 等待控制端连接

#### 控制端（子女）
1. 连接 WebSocket
2. 发送 `{"type": "join", "room_id": "123456"}`
3. 收到 `{"type": "room_ready"}`
4. 发送 `{"type": "offer", "payload": {...}}`
5. 收到 `{"type": "answer", "payload": {...}}`
6. 交换 `{"type": "candidate", "payload": {...}}`

## STUN/TURN 配置

如需打洞服务，在 Android 客户端配置 STUN/TURN 服务器：
```go
// Android 客户端示例
iceServers := listOf(
    RTCIceServer(listOf("stun:stun.l.google.com:19302")),
    RTCIceServer(listOf("turn:your-turn-server.com:3478"), "username", "credential")
)
```

## 生产环境部署

建议使用以下方式部署：
- 使用 systemd 或 supervisor 管理进程
- 配置 Nginx 反向代理（支持 WSS）
- 使用 Docker 容器化部署
- 配置 TLS 证书（使用 WSS）

### Nginx WSS 配置示例
```nginx
location /ws {
    proxy_pass http://127.0.0.1:8081;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 86400;
}
```
