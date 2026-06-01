# 开发说明

本仓库为 monorepo，包含 Android 客户端与 Go 信令服务两个子项目。

## 项目结构

```
.
├── easyscreen-android/        # Android 客户端（Kotlin + Compose + WebRTC）
│   ├── app/                   # 应用模块
│   └── local.properties       # 本地配置（已 gitignore，含默认信令地址）
├── easyscreen-signaling/      # Go 信令服务
│   ├── main.go                # 信令核心（房间状态机、SDP/ICE 转发、多 Guest）
│   ├── stats_page.go          # /stats 可视化页面
│   ├── web/                   # Web 观看端静态资源
│   ├── Dockerfile             # 多阶段构建
│   ├── docker-compose.yml
│   └── deploy/                # systemd + Caddy 免 Docker 方案
├── docs/design.md             # 初始设计文档
└── .github/workflows/         # CI：android.yml / signaling.yml
```

## 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 17 |
| Android SDK | compileSdk 34（minSdk 26 / targetSdk 34） |
| Go | 1.26（Docker 构建镜像固定该版本） |
| Docker | 用于服务端本地运行 / 构建镜像 |

## Android 客户端

### 配置默认信令地址

默认信令地址不进仓库，由环境变量或 `local.properties` 注入到 `BuildConfig.DEFAULT_SERVER_URL`：

```properties
# easyscreen-android/local.properties
easyscreen.defaultServerUrl=wss://your.domain/ws
```

或设置环境变量 `EASYSCREEN_DEFAULT_SERVER_URL`（优先级高于 `local.properties`）。两者都缺失时回退占位符 `ws://example.com:8081/ws`，需用户在「设置」页手动填写。

> 地址格式：经反代时用 `wss://your.domain/ws`（不带端口，走标准 443）；直连裸端口才用 `wss://host:8081/ws`。

### 构建

```bash
cd easyscreen-android
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK
```

产物在 `app/build/outputs/apk/`。

### release 签名

release 签名信息从环境变量读取（见 `app/build.gradle.kts` 的 `signingConfigs`）；**本地缺失时 release 自动回退 debug 签名**，因此本地 `assembleRelease` 无需 keystore 也能跑。

CI 或本地正式签名时设置：

| 环境变量 | 含义 |
|----------|------|
| `KEYSTORE_FILE` | keystore 文件路径（`.jks` / `.keystore` 均可，Gradle 自动识别） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

### 版本与 git 哈希

`app/build.gradle.kts` 在编译时执行 `git rev-parse --short HEAD`，注入 `BuildConfig.GIT_COMMIT`。版本号在「设置 → 关于」展示为 `versionName (versionCode)` + 构建哈希。

## 信令服务

### 本地运行

```bash
cd easyscreen-signaling
go run .                          # 默认监听 :8081
EASYSCREEN_ADDR=:9000 go run .    # 自定义端口
```

打开 `http://127.0.0.1:8081/` 即 Web 观看端，`http://127.0.0.1:8081/stats` 为状态页。

### 版本注入

版本信息通过 `-ldflags -X` 注入到 `main` 包变量：

```bash
go build -ldflags="-s -w \
  -X main.appVersion=1.1 \
  -X main.gitCommit=$(git rev-parse --short HEAD) \
  -X main.buildTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)" -o easyscreen-signaling .
```

Docker 构建则通过 `--build-arg APP_VERSION / GIT_COMMIT / BUILD_TIME` 传入（`Dockerfile` 内 `ARG` → `ldflags`）。因部署目录通常非 git 仓库，哈希需由构建方显式传入。

### Docker

```bash
cd easyscreen-signaling
# docker-compose 从 shell 环境变量取 build-args
APP_VERSION=1.1 GIT_COMMIT=$(git rev-parse --short HEAD) \
BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ) \
docker compose up -d --build
```

容器绑定 `127.0.0.1:8081`，需反代对外暴露并放行 `/ws` 的 WebSocket 升级。

## CI/CD

`.github/workflows/` 下两个 workflow，均在 **push `main`** 或 **push tag `v*`** 时触发。

| Workflow | 产物 | 触发后行为 |
|----------|------|-----------|
| `android.yml` | release APK | 上传为 artifact；tag 时附加到 GitHub Release |
| `signaling.yml` | Docker 镜像 | 推送到 GHCR `ghcr.io/<owner>/easyscreen-signaling` |

镜像 tag 规则：`v1.2` → `:1.2` + `:latest`；push main → `:main` + `:sha-<短哈希>`。

### 所需 Secrets

仓库 Settings → Secrets and variables → Actions：

| Secret | 用途 |
|--------|------|
| `EASYSCREEN_DEFAULT_SERVER_URL` | 默认信令地址（注入 APK 的 BuildConfig） |
| `ANDROID_KEYSTORE_BASE64` | release keystore 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |

Docker 推送使用内置 `GITHUB_TOKEN`，无需额外 Secret（workflow 已声明 `packages: write`）。

将 keystore 转 base64（PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\your.jks")) | Out-File -NoNewline keystore.b64.txt
```

## App 在线更新机制

- **CI**：`android.yml` 在 tag 构建时除 APK 外用 `jq` 生成 `version.json`（versionCode/versionName/sha256/fileSize/forceUpdate/minSupportedVersionCode/releaseNotes/downloadUrl）一并附到 Release。`versionCode` 取 `github.run_number`（不连续但单调递增）。`releaseNotes` 与 `[force]` 标记经 **GitHub API**（`gh api repos/<repo>/git/tags/<sha>`，需 `GH_TOKEN`）读取 annotated tag 注释——CI 浅 checkout 下本地 tag 是 peeled commit，`git tag -l --format='%(contents)'` 会误取成 commit message。
- **信令服务**：`app_update.go` 懒加载（默认 TTL 15min）从 GitHub `releases/latest` 同步 `version.json` 与 APK 到 `EASYSCREEN_APP_CACHE_DIR`，sha256 校验后缓存；并发请求有防重入；版本未变跳过重复下载；GitHub 不可达时降级返回旧缓存。环境变量：`EASYSCREEN_GITHUB_REPO`、`EASYSCREEN_GITHUB_TOKEN`、`EASYSCREEN_APP_CACHE_DIR`、`EASYSCREEN_APP_SYNC_TTL`。
- **缓存与权限**：缓存落容器内 `/app/cache`，`Dockerfile` 已 `mkdir -p /app/cache && chown app`，使命名卷挂载也能被非 root 的 `app` 用户写入——否则 sync 因 Permission denied 失败、端点返回 503。仓库须为 public（匿名 `browser_download_url` 下载）。
- **安卓端**：`update/` 包。启动静默检查、设置页手动检查；比较 `BuildConfig.VERSION_CODE`；下载到 `externalCacheDir/update/`、sha256 校验；经 FileProvider（`${applicationId}.fileprovider`）触发系统安装器，需 `REQUEST_INSTALL_PACKAGES` 权限 + 用户授权「允许安装未知应用」。
- **强制更新**：`forceUpdate=true` 或当前 `versionCode < minSupportedVersionCode` 时对话框不可取消。

## 信令协议要点

WebSocket 消息结构：`{ type, room_id?, data?, payload? }`。

- **消息类型**：`register`（被控端注册）、`join`（观看端加入）、`offer`、`answer`、`candidate`、`room_ready`、`guest_join`、`guest_leave`、`kick_guest`、`disconnect`、`ping`/`pong`。
- **SDP 载荷**：`payload: { sdp, type: 'offer'|'answer' }`。
- **ICE 载荷**：`payload: { candidate, sdpMid, sdpMLineIndex }`（camelCase，Android 与 Web 端一致）。
- **多 Guest 路由**：转发消息携带 `guest_id`；服务端转发 Host↔Guest 时用 `map[string]interface{}` 透传，避免反序列化丢失未知字段。
- **房间持久化**：被控端 `register` 可带 `token`，离线后在 `hostReconnectTTL`（60s）内重连可复用原 6 位连接码。

### 协商顺序（易错点）

1. Host 收到 Guest 的 Offer 时，必须**先 createPeerConnection 再 setRemoteDescription**。
2. Host 是应答方，授权挂载轨道后应 `createAnswer()` 回发，而非再创建 Offer。
3. Guest 的 `createOffer` 需 `OfferToReceiveAudio/Video = true`，否则 SDP 不含 `m=video/audio`。

## 许可

[MIT License](LICENSE) © 2026 huanfeng
