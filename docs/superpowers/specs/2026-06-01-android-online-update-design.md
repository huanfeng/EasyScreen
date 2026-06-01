# 安卓 App 在线更新设计

> 日期：2026-06-01
> 状态：已批准，待实现
> 分支：`feat/app-online-update`

## 背景与目标

老年用户不擅长手动安装/更新 APK，首次通过微信安装已遇到诸多麻烦，后续手动更新更不现实。

目标：App 自动检查更新、自动下载、一键触发安装（首次需用户手动授权「允许安装未知应用」）。利用现有信令服务器作为分发节点，避免老人直连 GitHub（国内访问慢/不稳定）。

## 关键决策（已确认）

| 决策点 | 结论 |
|--------|------|
| APK / 版本元数据托管 | 信令服务器全托管，作为 GitHub 的国内加速镜像 |
| 服务器同步策略 | 懒加载 + 缓存（App 请求时若缓存过期才拉 GitHub） |
| 版本元数据来源 | CI 在 tag 构建时生成 `version.json` 作为 GitHub Release asset |
| 检查时机与交互 | App 启动静默自动检查 + 一键安装；设置页保留手动「检查更新」 |
| 强制更新 | 支持 `forceUpdate` / `minSupportedVersionCode`，tag message 含 `[force]` 时置真 |
| 发版流程 | 开发者只打 tag，CI 已有流程产出 APK + 新增 version.json；服务器自动同步 |
| APK 下载位置（端） | 安卓 `externalCacheDir/update/`（系统托管、不占内部存储配额） |
| APK 缓存位置（服务器） | Docker 挂载卷，不烧进镜像 |

## 总体架构与数据流

```
开发者: git tag -a v1.2.2 -m "修复xxx [force]"
            │
            ▼
GitHub Actions (android.yml)
   构建签名 APK ──► 生成 version.json ──► 一起附到 GitHub Release
            │
   ┌────────┴─────────────────────────────────┐
   │ 信令服务器 (Go, 懒加载+缓存)                │
   │  · App 请求时若缓存过期(>15min)才拉 GitHub  │
   │  · 缓存 version.json + APK 到挂载卷         │
   │  · GitHub 不可达时降级返回旧缓存            │
   └────────┬─────────────────────────────────┘
            │
老人 App 启动 ──GET https://域名/app/version.json──► 比较 versionCode
          └──有新版──► GET /app/download (国内快) ──► 校验sha256 ──► 一键安装
```

核心原则：**App 只与信令服务器通信，永不直连 GitHub**；服务器是 GitHub 的缓存代理。

## 组件一：信令服务端（Go）

新增文件 `easyscreen-signaling/app_update.go`，在 `main.go` 的 mux 中注册端点。

### 端点

| 端点 | 行为 |
|------|------|
| `GET /app/version.json` | 懒加载：缓存过期才拉 GitHub `releases/latest`，解析其中 `version.json` asset 返回。`downloadUrl` 改写为相对路径 `/app/download` |
| `GET /app/download` | `http.ServeFile` 返回缓存 APK，支持 Range 断点续传 |

`downloadUrl` 用相对路径的原因：服务器在反向代理后仅绑 `127.0.0.1:8081`，无法可靠得知自身对外域名；由 App 用与请求 version.json 相同的 base 拼接最稳妥。

### 配置（环境变量，docker-compose 注入）

| 变量 | 默认 | 说明 |
|------|------|------|
| `EASYSCREEN_GITHUB_REPO` | （必填启用） | 如 `huanfeng/EasyScreen`，留空则关闭更新端点 |
| `EASYSCREEN_GITHUB_TOKEN` | 空 | 可选，提高 GitHub API 限流（未认证 60 次/h） |
| `EASYSCREEN_APP_CACHE_DIR` | `/app/cache` | APK + 元数据缓存目录，挂载卷 |
| `EASYSCREEN_APP_SYNC_TTL` | `15m` | 缓存有效期 |

### 同步逻辑（懒加载）

1. App 请求 `/app/version.json`，若 `now - lastSync < TTL` 直接返回内存缓存。
2. 否则调 GitHub API `GET /repos/{repo}/releases/latest`。
3. 在 assets 中定位 `version.json` → 下载解析。
4. 若元数据中的 `versionCode` 比当前缓存新，定位 `*.apk` asset → 下载到 cache 目录（按 versionCode 命名）→ 用元数据中的 `sha256` 校验。
5. 校验通过后原子替换内存缓存与磁盘文件、更新 `lastSync`。

### 降级与并发

- GitHub 拉取/校验失败：保留并返回上次成功缓存；首次无任何缓存才返回 `503`。
- 用 mutex + singleflight 思路避免并发请求重复拉取 GitHub。
- 启动时尝试从 `EASYSCREEN_APP_CACHE_DIR` 恢复上次缓存的 `version.json` 与 APK，避免重启后冷启动。

### 部署改动

- `docker-compose.yml`：新增 volume 挂载 `/app/cache`；新增上述环境变量。
- `deploy/`（systemd 方案）README 同步说明缓存目录与环境变量。

## 组件二：CI（android.yml）

在「Rename APK」之后、Release 之前新增「Generate version.json」步骤，仅 tag 构建执行。

`version.json` 结构：

```json
{
  "versionCode": 42,
  "versionName": "1.2.2",
  "sha256": "<APK 的 sha256>",
  "fileSize": 12345678,
  "forceUpdate": false,
  "minSupportedVersionCode": 1,
  "releaseNotes": "<取自 annotated tag 的 message>",
  "downloadUrl": "/app/download"
}
```

字段来源：
- `versionCode` = `github.run_number`（与 APK 内注入值一致）
- `versionName` = tag 去 `v` 前缀
- `sha256` / `fileSize` = 对重命名后的 APK 计算
- `releaseNotes` = annotated tag message（去掉 `[force]` 标记行）
- `forceUpdate` = tag message 含 `[force]` 标记时为 `true`，否则 `false`
- `minSupportedVersionCode` = 默认 `1`（暂不随 tag 变化；如需提高由后续手动维护）
- `downloadUrl` = 固定 `/app/download`

将 `version.json` 与 APK 一同 `softprops/action-gh-release` 上传。main 分支推送不产出 release，不受影响。

## 组件三：安卓端

新增 `to.feng.app.easyscreen.update` 包：

| 文件 | 职责 |
|------|------|
| `UpdateModels.kt` | `AppVersionInfo` data class（对应 version.json） |
| `UpdatePrefs.kt` | 记录「已跳过版本」「上次检查时间」，避免每次启动重复弹窗 |
| `ServerUrlUtil.kt` | 从 `wss://host[:port]/ws` 推导 `https://host[:port]`（`ws`→`http`），作为更新接口 base |
| `UpdateRepository.kt` | OkHttp 拉 `/app/version.json`，Gson 解析，比较 `versionCode`，产出更新判定结果 |
| `ApkDownloader.kt` | 下载 APK 到 `externalCacheDir/update/`，进度回调，下载后 sha256 校验 |
| `ApkInstaller.kt` | FileProvider 生成 `content://` URI + `ACTION_VIEW`(application/vnd.android.package-archive) 触发系统安装器；检查 `packageManager.canRequestPackageInstalls()`，无权限则引导到 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` |
| `UpdateDialog.kt` | 老人友好大字 Compose 对话框：版本/说明/进度条/大按钮 |

### 版本比较与更新判定

- 有更新：`remote.versionCode > BuildConfig.VERSION_CODE`
- 强制：`remote.forceUpdate == true` 或 `BuildConfig.VERSION_CODE < remote.minSupportedVersionCode`
- 普通更新且用户已「跳过此版本」→ 启动检查时不弹（手动检查仍弹）

### 触发点

- `MainScreen` 启动 `LaunchedEffect` 静默检查（失败不打扰）。
- `SettingsScreen` 关于区新增「检查更新」按钮（失败给提示，已是最新给「已是最新版本」）。

### UI 流程（老人友好）

发现新版 → 大字弹窗「发现新版本 1.2.2」+ 更新说明 →「立即更新」大按钮 → 自动下载（进度条）→ 下载完按钮变「立即安装」→ 点击触发系统安装器。首次安装需引导开启「允许安装未知应用」。

- 普通更新：有「稍后」按钮（记入已跳过版本）。
- 强制更新（`forceUpdate` 或低于 `minSupportedVersionCode`）：弹窗不可取消，无「稍后」。

### 清单 / 权限 / 资源

- `AndroidManifest.xml` 新增 `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- 声明 `FileProvider`（authority `${applicationId}.fileprovider`）
- 新增 `res/xml/file_paths.xml`（暴露 `external-cache-path` 下 `update/`）
- 依赖无需新增：`androidx.core`（含 FileProvider）、OkHttp、Gson 均已存在

## 错误处理与边界

| 场景 | 处理 |
|------|------|
| 启动静默检查网络失败 | 不打扰用户 |
| 设置页手动检查失败 | 给出错误提示 |
| sha256 校验失败 | 删除已下载文件，提示重试 |
| 服务器/GitHub 不可用 | 服务器返回旧缓存或 503，App 端跳过 |
| 已是最新版本 | 手动检查提示「已是最新版本」；启动检查静默 |
| 无安装权限 | 引导至系统「允许安装未知应用」授权页 |

## 测试策略

### 信令服务端（Go）

- 用 `httptest` mock GitHub API。
- 用例：首次同步、缓存命中（TTL 内不再拉取）、缓存过期触发拉取、sha256 校验失败拒绝、`downloadUrl` 改写、GitHub 失败时降级返回旧缓存、启动时从磁盘恢复缓存。

### 安卓端（纯 JVM 单元测试，已有 junit）

- 版本比较与强制更新判定逻辑。
- `wss://host/ws` → `https://host` 推导（含带端口、`ws`/`wss` 两种）。
- sha256 校验工具。
- 「已跳过版本」对启动检查 vs 手动检查的不同行为。

## 实现顺序建议

1. 信令服务端 `/app/version.json` + `/app/download` + 同步/缓存/降级 + 测试。
2. CI 生成并上传 `version.json`。
3. 安卓端更新模块（Repository → Downloader → Installer → Dialog）+ 触发点 + 权限/清单/FileProvider + 测试。
4. 文档：README / DEVELOPMENT.md / deploy README 补充更新机制与部署配置。

## 非目标（YAGNI）

- 增量/差分更新（diff patch）。
- 多渠道/灰度发布。
- 应用内静默自动安装（无用户点击）——Android 普通应用无此能力，且对老人需保留一次确认。
- 服务器端解包 APK 读取 versionCode（改用 CI 产出的 version.json）。
