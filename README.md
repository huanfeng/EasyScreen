# 远程看屏 (EasyScreen)

极简远程看屏应用，专为老年人设计：父母端一键共享屏幕，子女端在手机或浏览器输入 6 位连接码即可远程查看，帮助远程协助、教学与排障。

- **零门槛**：被控端只需点一下「共享」，界面大字显示 6 位连接码
- **多端观看**：Android App 或任意浏览器都能作为观看端，支持 1 对多
- **低延迟**：基于 WebRTC P2P 直连，媒体流端到端加密（DTLS-SRTP），服务器看不到画面内容
- **稳定**：断网自动重连、连接码持久化（被控端重启仍可复用号码）

## 架构

```
┌────────────┐   6 位连接码    ┌────────────┐
│  被控端     │ ─────────────► │  观看端     │
│ (Android)   │   WebRTC P2P    │ (Android/Web)│
│ 共享屏幕     │ ◄═════════════► │ 观看画面     │
└─────┬──────┘   DTLS-SRTP     └──────┬─────┘
      │                                │
      │         信令交换 (WSS)          │
      └──────────►┌──────────┐◄────────┘
                  │ 信令服务  │  Go + gorilla/websocket
                  │ (Signaling)│ 仅转发 SDP/ICE，不经手媒体
                  └──────────┘
```

| 组件 | 技术 | 说明 |
|------|------|------|
| 被控端 / 观看端 (Android) | Kotlin + Jetpack Compose + WebRTC | `easyscreen-android/` |
| 信令服务 | Go + gorilla/websocket | `easyscreen-signaling/` |
| Web 观看端 | 原生 HTML/JS | `easyscreen-signaling/web/`，由信令服务托管在 `/` |

## 快速开始

### 终端用户

1. 安装 APK（见 [Releases](https://github.com/huanfeng/EasyScreen/releases) 或自行构建）。
2. 首次打开 → 主页右上角「设置」→ 填入信令服务器地址：
   ```
   wss://your.domain/ws
   ```
3. **被控端（父母）**：主页点「我要共享屏幕」→ 记下大字显示的 6 位连接码 → 授权录屏。
4. **观看端（子女）**：主页点「我要看别人屏幕」→ 输入 6 位连接码 → 开始观看。

### 浏览器观看（无需第二台设备）

直接访问 `https://your.domain/`，输入连接码即可作为观看端接入，方便测试与临时查看。

## 服务端部署

信令服务以 Docker 运行，绑定 `127.0.0.1:8081`，由反向代理（Caddy / Nginx / OpenResty 等）对外提供 HTTPS + WSS。

```bash
cd easyscreen-signaling
docker compose up -d --build
```

反代需将 `https://your.domain/` 转发到 `127.0.0.1:8081`，并放行 WebSocket 升级（`/ws` 路径）。

> 注意：若使用 Cloudflare 代理，需将该域名设为「仅 DNS」（灰云），否则其 HTTP/2 会阻断 WebSocket 的 Upgrade 头。

也可使用 systemd + Caddy 的免 Docker 方案，见 [`easyscreen-signaling/deploy/README.md`](easyscreen-signaling/deploy/README.md)。

镜像同时由 CI 自动推送到 GHCR：

```
ghcr.io/huanfeng/easyscreen-signaling:latest
```

## App 在线更新

信令服务器可作为 GitHub Release 的国内缓存代理，让老人端 App 自动检查/下载更新：

| 端点 | 用途 |
|------|------|
| `/app/version.json` | 最新版本元数据（懒加载从 GitHub 同步并缓存） |
| `/app/download` | 缓存的最新 APK（支持断点续传） |

启用方式：在 docker-compose 环境变量设置 `EASYSCREEN_GITHUB_REPO=<owner>/<repo>`（留空则关闭）。可选 `EASYSCREEN_GITHUB_TOKEN` 提高 GitHub API 限流。APK 缓存在命名卷 `easyscreen-apk-cache`（容器内 `/app/cache`），不烧进镜像。

发版流程不变：打 `v*` tag → CI 构建 APK 并生成 `version.json` 一起发布到 GitHub Release → 服务器下次请求时自动同步。tag 注释信息（`git tag -a v1.2.2 -m "..."`）作为更新说明；注释含 `[force]` 时该版本标记为强制更新。

## 服务状态端点

信令服务提供以下匿名端点（不含房间号 / IP / token）：

| 端点 | 用途 |
|------|------|
| `/health` | 健康检查（房间数、连接数） |
| `/stats` | 可视化状态页（自动刷新） |
| `/stats.json` | 状态数据接口 |
| `/version` | 版本号与 git 哈希 |

## 开发

参见 [DEVELOPMENT.md](DEVELOPMENT.md)：环境要求、本地构建、签名配置、版本注入、CI/CD 与信令协议。

## 许可

[MIT License](LICENSE) © 2026 huanfeng
