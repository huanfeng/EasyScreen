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

### 国产手机安装说明

由于本应用未上架国内应用商店，在国产 Android 系统（小米 / 华为 / OPPO / vivo 等）上安装 APK 时可能遇到拦截提示，需手动允许。

**通用步骤：开启「允许安装未知来源应用」**

> 下载前若浏览器弹出"此文件可能有害"，点「仍然下载」继续。

| 品牌 / 系统 | 路径 |
|-------------|------|
| 小米 / Redmi（MIUI / HyperOS） | 设置 → 特殊应用权限 → 安装未知应用 → 选择浏览器或文件管理器 → 开启「允许」 |
| 华为 / 荣耀（EMUI / HarmonyOS） | 设置 → 安全 → 更多设置 → 安装外部来源应用 → 选择对应应用开启 |
| OPPO / 一加（ColorOS） | 设置 → 其他设置 → 安全设置 → 安装外部来源应用 → 选择对应应用开启 |
| vivo（OriginOS / Funtouch） | 设置 → 指纹与安全 → 安装外部来源应用 → 选择对应应用开启 |
| 三星（One UI） | 设置 → 生物识别与安全 → 安装未知应用 → 选择对应应用开启 |

**华为「纯净模式」说明**

部分华为 / 荣耀设备出厂启用了「纯净模式」，该模式下系统会阻止所有第三方 APK 安装。可在以下路径关闭：

```
设置 → 安全 → 更多设置 → 纯净模式 → 关闭
```

> 关闭后重新安装 APK 即可。安装完成后可视需要重新开启纯净模式。

**小米安全审核弹窗**

MIUI / HyperOS 安装时可能弹出「向小米发送应用信息以检测风险」，选择「暂不发送」即可继续安装，无需上传云端审核。

**安装后首次启动被拦截**

部分系统（如 OPPO / vivo）在首次启动未知来源应用时会弹出额外确认框，点「继续安装」或「允许运行」即可。若被手机管家直接标记为「病毒」，可进入手机管家 → 信任该应用，或临时关闭实时防护后重新启动。

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

启用方式：在 docker-compose 的 `environment` 设置 `EASYSCREEN_GITHUB_REPO=<owner>/<repo>`（留空则关闭更新端点）。可选项见下表。

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `EASYSCREEN_GITHUB_REPO` | 空（关闭） | 形如 `huanfeng/EasyScreen`，仓库须为 **public** |
| `EASYSCREEN_GITHUB_TOKEN` | 空 | 可选，提高 GitHub API 限流；public 仓库可不配 |
| `EASYSCREEN_APP_CACHE_DIR` | `/app/cache` | APK 缓存目录 |
| `EASYSCREEN_APP_SYNC_TTL` | `15m` | 缓存有效期，超时才回源 GitHub |

> **仓库必须为 public**：服务器以匿名方式拉取 Release 的 `version.json` 与 APK（走 `browser_download_url`）。私有仓库当前不支持（其 asset 需经 GitHub asset API + token 下载）。
>
> **缓存与权限**：APK 缓存在容器内 `/app/cache`，不烧进镜像。如需重启保留缓存，可挂命名卷 `easyscreen-apk-cache`——镜像已预建该目录并归属运行用户 `app`，避免非 root 用户写入被拒（否则同步失败返回 503）。VPS 回源 GitHub 通常很快，不挂卷也可（重启后首个请求重新同步）。

### 发版

打一个带注释的 tag 即可，其余全自动：

```bash
git tag -a v1.3.3 -m "更新说明文字"      # 注释含 [force] 则该版本标记为强制更新
git push origin v1.3.3
```

CI 自动构建签名 APK + 生成 `version.json`，一起发布到 GitHub Release；服务器在缓存超过 TTL 后的下一个请求自动同步。`versionName` 取自 tag（去 `v`），`versionCode` 用 CI 运行号（不连续但单调递增）。tag 注释作为更新说明（`releaseNotes`），含 `[force]` 时置 `forceUpdate=true`。

### 故障排查

| 现象 | 可能原因 |
|------|----------|
| App 检查更新 **404** | 服务器跑的是旧版本（无 `/app/version.json` 端点），或未设 `EASYSCREEN_GITHUB_REPO`。`curl https://域名/version` 看 `git_commit` 是否为含本功能的提交 |
| App 检查更新 **503** | 服务器同步失败：仓库非 public、缓存目录不可写（命名卷权限）、或 VPS 访问 GitHub 失败。`curl https://域名/app/version.json` 复现 |
| 反代未放行 | 确保反代把 `/app/version.json`、`/app/download` 转发到信令服务（APK 较大，注意 body 大小限制） |

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
