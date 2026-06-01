# 安卓 App 在线更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让老人端 App 启动时自动检查更新、从信令服务器下载 APK、一键触发安装；信令服务器作为 GitHub Release 的国内缓存代理。

**Architecture:** 三段式。CI 在 tag 构建时除 APK 外额外产出 `version.json` 上传到 GitHub Release。信令服务器（Go）懒加载拉取 GitHub `releases/latest`，缓存 `version.json` + APK 到挂载卷，对 App 暴露 `/app/version.json` 与 `/app/download`，GitHub 不可达时降级返回旧缓存。安卓端启动静默检查、比较 `versionCode`、下载并校验 sha256、经 FileProvider 触发系统安装器。

**Tech Stack:** Go 标准库 `net/http`（信令端，无需新依赖）；GitHub Actions（CI）；Kotlin + Jetpack Compose + OkHttp 4.12.0 + Gson 2.10.1 + androidx.core FileProvider（安卓端，依赖均已存在）。

设计来源：`docs/superpowers/specs/2026-06-01-android-online-update-design.md`

---

## 文件结构

### 信令服务端（`easyscreen-signaling/`）
- 创建 `app_update.go` — `AppVersionInfo` 结构、`AppUpdater`（同步/缓存/降级）、两个 HTTP handler
- 创建 `app_update_test.go` — 用 `httptest` mock GitHub 的单元测试
- 修改 `main.go` — 注册 `/app/version.json` 与 `/app/download`，从环境变量构造 `AppUpdater`
- 修改 `docker-compose.yml` — 新增 cache 卷与环境变量
- 修改 `.dockerignore` / 运行目录 — 确保 cache 目录可写

### CI（`.github/workflows/`）
- 修改 `android.yml` — tag 构建时生成并上传 `version.json`

### 安卓端（`easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/`）
- 创建 `UpdateModels.kt` — `AppVersionInfo`、`UpdateKind`、`decideUpdate()`
- 创建 `ServerUrlUtil.kt` — `wss://host/ws` → `https://host`
- 创建 `Sha256.kt` — 文件/字节 sha256
- 创建 `UpdatePrefs.kt` — 跳过版本、上次检查时间
- 创建 `UpdateRepository.kt` — OkHttp 拉 `version.json`
- 创建 `ApkDownloader.kt` — 下载 + 进度 + 校验
- 创建 `ApkInstaller.kt` — FileProvider + 安装 Intent + 权限检查
- 创建 `UpdateController.kt` — 协调检查/下载/安装的状态机（供 UI 观察）
- 创建 `ui/update/UpdateDialog.kt` — 老人友好对话框
- 修改 `MainActivity.kt` / `ui/screens/MainScreen.kt` — 启动检查接入
- 修改 `ui/screens/SettingsScreen.kt` — 手动「检查更新」按钮
- 修改 `AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES` + FileProvider
- 创建 `res/xml/file_paths.xml` — FileProvider 路径
- 创建测试 `app/src/test/java/to/feng/app/easyscreen/update/`：`UpdateModelsTest.kt`、`ServerUrlUtilTest.kt`、`Sha256Test.kt`

### 文档
- 修改 `README.md`、`DEVELOPMENT.md`、`easyscreen-signaling/deploy/README.md`

---

## version.json 契约（三端共享，务必字段一致）

```json
{
  "versionCode": 42,
  "versionName": "1.2.2",
  "sha256": "<APK 文件的小写十六进制 sha256>",
  "fileSize": 12345678,
  "forceUpdate": false,
  "minSupportedVersionCode": 1,
  "releaseNotes": "修复了若干问题",
  "downloadUrl": "/app/download"
}
```

- CI 生成此文件并上传为 Release asset（名为 `version.json`）。
- 信令服务器解析后，把 `downloadUrl` 强制改写为 `/app/download` 再返回给 App。
- App 用拉取 version.json 的同一 base 拼接 `downloadUrl` 得到 APK 地址。

---

# Part A：信令服务端（Go）

### Task A1：定义数据结构与 AppUpdater 骨架

**Files:**
- Create: `easyscreen-signaling/app_update.go`

- [ ] **Step 1：写入数据结构与构造函数**

```go
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// AppVersionInfo 对应 CI 生成、随 GitHub Release 一起发布的 version.json
type AppVersionInfo struct {
	VersionCode             int    `json:"versionCode"`
	VersionName             string `json:"versionName"`
	SHA256                  string `json:"sha256"`
	FileSize                int64  `json:"fileSize"`
	ForceUpdate             bool   `json:"forceUpdate"`
	MinSupportedVersionCode int    `json:"minSupportedVersionCode"`
	ReleaseNotes            string `json:"releaseNotes"`
	DownloadURL             string `json:"downloadUrl"`
}

// ghRelease / ghAsset 是 GitHub releases/latest 响应的最小子集
type ghAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
}
type ghRelease struct {
	TagName string    `json:"tag_name"`
	Assets  []ghAsset `json:"assets"`
}

// AppUpdater 把 GitHub Release 缓存为本地 APK + 元数据，懒加载刷新。
type AppUpdater struct {
	repo      string // 形如 "huanfeng/EasyScreen"，空则禁用
	token     string // 可选 GitHub token
	cacheDir  string
	ttl       time.Duration
	githubAPI string // 默认 https://api.github.com，测试可覆盖
	client    *http.Client
	now       func() time.Time // 默认 time.Now，测试可覆盖

	mu       sync.Mutex
	info     *AppVersionInfo
	apkPath  string
	lastSync time.Time
}

func NewAppUpdater(repo, token, cacheDir string, ttl time.Duration) *AppUpdater {
	return &AppUpdater{
		repo:      repo,
		token:     token,
		cacheDir:  cacheDir,
		ttl:       ttl,
		githubAPI: "https://api.github.com",
		client:    &http.Client{Timeout: 60 * time.Second},
		now:       time.Now,
	}
}

// Enabled 报告是否配置了仓库（未配置则不注册端点）
func (u *AppUpdater) Enabled() bool { return u.repo != "" }
```

- [ ] **Step 2：不要在此单独编译**

`app_update.go` 顶部 import 块已为 Task A2 预留（`crypto/sha256`、`io`、`fmt`、`path/filepath` 等在 A1 代码里尚未使用）。Go 把「未使用 import」视为编译错误，所以**此刻不要运行 `go build`**——它必然失败。A1 与 A2 的代码都就位后，在 Task A2 Step 4 统一编译验证。

---

### Task A2：实现 GitHub 同步逻辑（含 sha256 校验）

**Files:**
- Modify: `easyscreen-signaling/app_update.go`
- Test: `easyscreen-signaling/app_update_test.go`

- [ ] **Step 1：先写失败测试**

创建 `easyscreen-signaling/app_update_test.go`：

```go
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 启动一个假的 GitHub：releases/latest + version.json asset + apk asset
func fakeGitHub(t *testing.T, info AppVersionInfo, apk []byte) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	srv := httptest.NewServer(mux)

	mux.HandleFunc("/repos/owner/repo/releases/latest", func(w http.ResponseWriter, r *http.Request) {
		rel := ghRelease{
			TagName: "v" + info.VersionName,
			Assets: []ghAsset{
				{Name: "version.json", BrowserDownloadURL: srv.URL + "/dl/version.json"},
				{Name: "EasyScreen-v" + info.VersionName + "-release.apk", BrowserDownloadURL: srv.URL + "/dl/app.apk"},
			},
		}
		json.NewEncoder(w).Encode(rel)
	})
	mux.HandleFunc("/dl/version.json", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(info)
	})
	mux.HandleFunc("/dl/app.apk", func(w http.ResponseWriter, r *http.Request) {
		w.Write(apk)
	})
	t.Cleanup(srv.Close)
	return srv
}

func sha256hex(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

func TestSyncDownloadsAndVerifies(t *testing.T) {
	apk := []byte("fake-apk-bytes")
	info := AppVersionInfo{
		VersionCode: 42, VersionName: "1.2.2",
		SHA256: sha256hex(apk), FileSize: int64(len(apk)),
		ForceUpdate: false, MinSupportedVersionCode: 1,
		ReleaseNotes: "修复", DownloadURL: "/app/download",
	}
	gh := fakeGitHub(t, info, apk)

	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 15*time.Minute)
	u.githubAPI = gh.URL

	if err := u.sync(); err != nil {
		t.Fatalf("sync failed: %v", err)
	}
	got := u.snapshot()
	if got == nil || got.VersionCode != 42 {
		t.Fatalf("expected versionCode 42, got %+v", got)
	}
	// APK 已落盘
	if _, err := os.Stat(u.apkPath); err != nil {
		t.Fatalf("apk not cached: %v", err)
	}
}

func TestSyncRejectsBadSha(t *testing.T) {
	apk := []byte("fake-apk-bytes")
	info := AppVersionInfo{
		VersionCode: 42, VersionName: "1.2.2",
		SHA256: "deadbeef", FileSize: int64(len(apk)),
		MinSupportedVersionCode: 1, DownloadURL: "/app/download",
	}
	gh := fakeGitHub(t, info, apk)
	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 15*time.Minute)
	u.githubAPI = gh.URL

	if err := u.sync(); err == nil {
		t.Fatal("expected sha mismatch error, got nil")
	}
	if u.snapshot() != nil {
		t.Fatal("bad sync must not populate cache")
	}
}

func TestDownloadURLRewritten(t *testing.T) {
	apk := []byte("x")
	info := AppVersionInfo{
		VersionCode: 5, VersionName: "1.0", SHA256: sha256hex(apk),
		FileSize: 1, MinSupportedVersionCode: 1, DownloadURL: "https://github.com/whatever",
	}
	gh := fakeGitHub(t, info, apk)
	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 15*time.Minute)
	u.githubAPI = gh.URL
	if err := u.sync(); err != nil {
		t.Fatal(err)
	}
	if u.snapshot().DownloadURL != "/app/download" {
		t.Fatalf("downloadUrl not rewritten: %s", u.snapshot().DownloadURL)
	}
}

func TestDiskRestore(t *testing.T) {
	apk := []byte("persist-me")
	info := AppVersionInfo{
		VersionCode: 7, VersionName: "1.1", SHA256: sha256hex(apk),
		FileSize: int64(len(apk)), MinSupportedVersionCode: 1, DownloadURL: "/app/download",
	}
	dir := t.TempDir()
	// 预置磁盘缓存
	apkPath := filepath.Join(dir, "app-7.apk")
	os.WriteFile(apkPath, apk, 0o644)
	meta, _ := json.Marshal(info)
	os.WriteFile(filepath.Join(dir, "version.json"), meta, 0o644)

	u := NewAppUpdater("owner/repo", "", dir, 15*time.Minute)
	u.loadFromDisk()
	if u.snapshot() == nil || u.snapshot().VersionCode != 7 {
		t.Fatal("disk restore failed")
	}
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `cd easyscreen-signaling && go test ./... -run 'Sync|DownloadURL|DiskRestore' -v`
Expected: 编译失败 / FAIL（`sync`、`snapshot`、`loadFromDisk` 未定义）

- [ ] **Step 3：实现同步逻辑**

向 `app_update.go` 追加：

```go
// snapshot 返回当前缓存元数据的拷贝（线程安全）
func (u *AppUpdater) snapshot() *AppVersionInfo {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.info == nil {
		return nil
	}
	cp := *u.info
	return &cp
}

func (u *AppUpdater) httpGet(url string) (*http.Response, error) {
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	if u.token != "" {
		req.Header.Set("Authorization", "Bearer "+u.token)
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	return u.client.Do(req)
}

// sync 从 GitHub 拉取 latest release，下载并校验 APK，成功后原子更新缓存。
func (u *AppUpdater) sync() error {
	relURL := fmt.Sprintf("%s/repos/%s/releases/latest", u.githubAPI, u.repo)
	resp, err := u.httpGet(relURL)
	if err != nil {
		return fmt.Errorf("fetch release: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("github release status %d", resp.StatusCode)
	}
	var rel ghRelease
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return fmt.Errorf("decode release: %w", err)
	}

	var verURL, apkURL string
	for _, a := range rel.Assets {
		switch {
		case a.Name == "version.json":
			verURL = a.BrowserDownloadURL
		case filepath.Ext(a.Name) == ".apk":
			apkURL = a.BrowserDownloadURL
		}
	}
	if verURL == "" || apkURL == "" {
		return fmt.Errorf("release missing version.json or apk asset")
	}

	// 下载并解析 version.json
	info, err := u.fetchVersionInfo(verURL)
	if err != nil {
		return err
	}

	// 下载 APK 到临时文件并校验 sha256
	apkPath := filepath.Join(u.cacheDir, fmt.Sprintf("app-%d.apk", info.VersionCode))
	if err := os.MkdirAll(u.cacheDir, 0o755); err != nil {
		return fmt.Errorf("mkdir cache: %w", err)
	}
	tmp := apkPath + ".tmp"
	sum, size, err := u.downloadTo(apkURL, tmp)
	if err != nil {
		return err
	}
	if info.SHA256 != "" && sum != info.SHA256 {
		os.Remove(tmp)
		return fmt.Errorf("sha256 mismatch: want %s got %s", info.SHA256, sum)
	}
	if err := os.Rename(tmp, apkPath); err != nil {
		return fmt.Errorf("rename apk: %w", err)
	}

	info.DownloadURL = "/app/download"
	if info.FileSize == 0 {
		info.FileSize = size
	}

	// 落盘元数据
	meta, _ := json.Marshal(info)
	os.WriteFile(filepath.Join(u.cacheDir, "version.json"), meta, 0o644)

	u.mu.Lock()
	u.info = info
	u.apkPath = apkPath
	u.lastSync = u.now()
	u.mu.Unlock()
	return nil
}

func (u *AppUpdater) fetchVersionInfo(url string) (*AppVersionInfo, error) {
	resp, err := u.httpGet(url)
	if err != nil {
		return nil, fmt.Errorf("fetch version.json: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("version.json status %d", resp.StatusCode)
	}
	var info AppVersionInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return nil, fmt.Errorf("decode version.json: %w", err)
	}
	return &info, nil
}

// downloadTo 把 url 内容写入 path，返回小写十六进制 sha256 与字节数。
func (u *AppUpdater) downloadTo(url, path string) (string, int64, error) {
	resp, err := u.httpGet(url)
	if err != nil {
		return "", 0, fmt.Errorf("download apk: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", 0, fmt.Errorf("apk status %d", resp.StatusCode)
	}
	f, err := os.Create(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), resp.Body)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// loadFromDisk 在启动时尝试恢复上次缓存，避免重启冷启动。
func (u *AppUpdater) loadFromDisk() {
	metaPath := filepath.Join(u.cacheDir, "version.json")
	b, err := os.ReadFile(metaPath)
	if err != nil {
		return
	}
	var info AppVersionInfo
	if json.Unmarshal(b, &info) != nil {
		return
	}
	apkPath := filepath.Join(u.cacheDir, fmt.Sprintf("app-%d.apk", info.VersionCode))
	if _, err := os.Stat(apkPath); err != nil {
		return
	}
	info.DownloadURL = "/app/download"
	u.mu.Lock()
	u.info = &info
	u.apkPath = apkPath
	// 不设 lastSync：重启后仍允许下一次请求触发刷新
	u.mu.Unlock()
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `cd easyscreen-signaling && go test ./... -run 'Sync|DownloadURL|DiskRestore' -v`
Expected: PASS（4 个用例）

- [ ] **Step 5：提交**

```bash
git add easyscreen-signaling/app_update.go easyscreen-signaling/app_update_test.go
git commit -m "feat(signaling): 增加 GitHub Release 同步与 APK 缓存"
```

---

### Task A3：实现两个 HTTP handler + 懒加载/降级

**Files:**
- Modify: `easyscreen-signaling/app_update.go`
- Test: `easyscreen-signaling/app_update_test.go`

- [ ] **Step 1：写失败测试**

向 `app_update_test.go` 追加：

```go
func TestVersionEndpointLazyAndCache(t *testing.T) {
	apk := []byte("apkapk")
	info := AppVersionInfo{
		VersionCode: 9, VersionName: "1.3", SHA256: sha256hex(apk),
		FileSize: int64(len(apk)), MinSupportedVersionCode: 1, DownloadURL: "/app/download",
	}
	gh := fakeGitHub(t, info, apk)
	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 15*time.Minute)
	u.githubAPI = gh.URL

	// 第一次请求：触发同步，返回 200 + JSON
	r1 := httptest.NewRequest(http.MethodGet, "/app/version.json", nil)
	w1 := httptest.NewRecorder()
	u.handleVersion(w1, r1)
	if w1.Code != http.StatusOK {
		t.Fatalf("want 200 got %d", w1.Code)
	}
	var out AppVersionInfo
	json.Unmarshal(w1.Body.Bytes(), &out)
	if out.VersionCode != 9 || out.DownloadURL != "/app/download" {
		t.Fatalf("bad payload %+v", out)
	}
	first := u.lastSync

	// 第二次请求（TTL 内）：命中缓存，不更新 lastSync
	r2 := httptest.NewRequest(http.MethodGet, "/app/version.json", nil)
	u.handleVersion(httptest.NewRecorder(), r2)
	if !u.lastSync.Equal(first) {
		t.Fatal("expected cache hit (lastSync unchanged)")
	}
}

func TestVersionEndpointDegradesToCache(t *testing.T) {
	apk := []byte("apkapk")
	info := AppVersionInfo{
		VersionCode: 9, VersionName: "1.3", SHA256: sha256hex(apk),
		FileSize: int64(len(apk)), MinSupportedVersionCode: 1, DownloadURL: "/app/download",
	}
	gh := fakeGitHub(t, info, apk)
	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 0) // ttl=0 → 每次都尝试刷新
	u.githubAPI = gh.URL

	// 先成功同步一次
	u.handleVersion(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/app/version.json", nil))
	// 让 GitHub 挂掉
	gh.Close()
	// 再请求：刷新失败但应降级返回旧缓存
	w := httptest.NewRecorder()
	u.handleVersion(w, httptest.NewRequest(http.MethodGet, "/app/version.json", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("expected degrade to 200 cache, got %d", w.Code)
	}
}

func TestVersionEndpointNoCacheReturns503(t *testing.T) {
	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 0)
	u.githubAPI = "http://127.0.0.1:0" // 必失败
	w := httptest.NewRecorder()
	u.handleVersion(w, httptest.NewRequest(http.MethodGet, "/app/version.json", nil))
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("want 503 got %d", w.Code)
	}
}

func TestDownloadEndpointServesApk(t *testing.T) {
	apk := []byte("real-apk-content")
	info := AppVersionInfo{
		VersionCode: 9, VersionName: "1.3", SHA256: sha256hex(apk),
		FileSize: int64(len(apk)), MinSupportedVersionCode: 1, DownloadURL: "/app/download",
	}
	gh := fakeGitHub(t, info, apk)
	dir := t.TempDir()
	u := NewAppUpdater("owner/repo", "", dir, 15*time.Minute)
	u.githubAPI = gh.URL
	u.handleVersion(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/app/version.json", nil))

	w := httptest.NewRecorder()
	u.handleDownload(w, httptest.NewRequest(http.MethodGet, "/app/download", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("want 200 got %d", w.Code)
	}
	if w.Body.String() != string(apk) {
		t.Fatal("apk content mismatch")
	}
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd easyscreen-signaling && go test ./... -run 'Endpoint' -v`
Expected: 编译失败（`handleVersion`、`handleDownload` 未定义）

- [ ] **Step 3：实现 handler**

向 `app_update.go` 追加：

```go
// ensureFresh 在缓存过期时尝试刷新；返回是否有可用数据。
func (u *AppUpdater) ensureFresh() {
	u.mu.Lock()
	stale := u.info == nil || u.now().Sub(u.lastSync) >= u.ttl
	u.mu.Unlock()
	if !stale {
		return
	}
	// 刷新失败时静默：保留旧缓存（降级）
	_ = u.sync()
}

func (u *AppUpdater) handleVersion(w http.ResponseWriter, r *http.Request) {
	u.ensureFresh()
	info := u.snapshot()
	if info == nil {
		http.Error(w, `{"error":"update info unavailable"}`, http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(info)
}

func (u *AppUpdater) handleDownload(w http.ResponseWriter, r *http.Request) {
	u.ensureFresh()
	u.mu.Lock()
	apkPath := u.apkPath
	versionName := ""
	if u.info != nil {
		versionName = u.info.VersionName
	}
	u.mu.Unlock()
	if apkPath == "" {
		http.Error(w, "apk unavailable", http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf(`attachment; filename="EasyScreen-v%s-release.apk"`, versionName))
	http.ServeFile(w, r, apkPath) // ServeFile 自动支持 Range 断点续传
}
```

- [ ] **Step 4：运行确认通过**

Run: `cd easyscreen-signaling && go test ./... -v`
Expected: PASS（全部用例）

- [ ] **Step 5：提交**

```bash
git add easyscreen-signaling/app_update.go easyscreen-signaling/app_update_test.go
git commit -m "feat(signaling): /app/version.json 与 /app/download 端点(懒加载+降级)"
```

---

### Task A4：在 main.go 注册端点并读取环境变量

**Files:**
- Modify: `easyscreen-signaling/main.go`（在 `/version` handler 注册附近，约第 738-763 行之间）

- [ ] **Step 1：在 mux 注册区前构造 updater 并注册端点**

在 `main.go` 中，定位到 `// Web 预览客户端：静态资源托管在 ./web 目录` 之前（约第 760 行），插入：

```go
	// App 在线更新：信令服务器作为 GitHub Release 的国内缓存代理
	{
		repo := os.Getenv("EASYSCREEN_GITHUB_REPO")
		cacheDir := os.Getenv("EASYSCREEN_APP_CACHE_DIR")
		if cacheDir == "" {
			cacheDir = "/app/cache"
		}
		ttl := 15 * time.Minute
		if v := os.Getenv("EASYSCREEN_APP_SYNC_TTL"); v != "" {
			if d, err := time.ParseDuration(v); err == nil {
				ttl = d
			}
		}
		updater := NewAppUpdater(repo, os.Getenv("EASYSCREEN_GITHUB_TOKEN"), cacheDir, ttl)
		if updater.Enabled() {
			updater.loadFromDisk()
			mux.HandleFunc("/app/version.json", updater.handleVersion)
			mux.HandleFunc("/app/download", updater.handleDownload)
			log.Printf("  - App 更新:   /app/version.json (repo=%s, cache=%s)", repo, cacheDir)
		} else {
			log.Printf("  - App 更新:   未启用（设置 EASYSCREEN_GITHUB_REPO 以开启）")
		}
	}

```

- [ ] **Step 2：编译并跑全部测试**

Run: `cd easyscreen-signaling && go build ./... && go test ./...`
Expected: build 通过，test PASS

- [ ] **Step 3：手动冒烟（可选，需联网）**

Run:
```bash
cd easyscreen-signaling
EASYSCREEN_GITHUB_REPO=huanfeng/EasyScreen EASYSCREEN_APP_CACHE_DIR=./.cache go run .
```
另开终端：`curl -s http://127.0.0.1:8081/app/version.json`
Expected: 返回 version.json（若该 repo 尚未发布带 version.json 的 Release，则返回 503——属正常，待 CI 上线后生效）

- [ ] **Step 4：提交**

```bash
git add easyscreen-signaling/main.go
git commit -m "feat(signaling): 注册 App 更新端点并读取环境变量配置"
```

---

### Task A5：docker-compose 缓存卷与环境变量

**Files:**
- Modify: `easyscreen-signaling/docker-compose.yml`

- [ ] **Step 1：增加 volume 与环境变量**

将 `docker-compose.yml` 的 `environment` 与新增 `volumes` 改为：

```yaml
    environment:
      EASYSCREEN_ADDR: ":8081"
      TZ: Asia/Shanghai
      # App 在线更新：留空则关闭更新端点
      EASYSCREEN_GITHUB_REPO: ${EASYSCREEN_GITHUB_REPO:-}
      EASYSCREEN_GITHUB_TOKEN: ${EASYSCREEN_GITHUB_TOKEN:-}
      EASYSCREEN_APP_CACHE_DIR: /app/cache
      EASYSCREEN_APP_SYNC_TTL: ${EASYSCREEN_APP_SYNC_TTL:-15m}
    volumes:
      - easyscreen-apk-cache:/app/cache
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"

volumes:
  easyscreen-apk-cache:
```

> 注意：`volumes:` 顶层键与 `services:` 同级，缩进为 0。`easyscreen-apk-cache` 命名卷由 Docker 托管，重建容器不丢缓存。容器内运行用户为 `app`（见 Dockerfile），命名卷首次挂载会继承挂载点属主，`os.MkdirAll`/写入在 `/app/cache` 下可正常工作。

- [ ] **Step 2：校验 compose 语法**

Run: `cd easyscreen-signaling && docker compose config >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3：提交**

```bash
git add easyscreen-signaling/docker-compose.yml
git commit -m "chore(signaling): 为 App 更新增加 APK 缓存卷与环境变量"
```

---

# Part B：CI 生成 version.json

### Task B1：android.yml 生成并上传 version.json

**Files:**
- Modify: `.github/workflows/android.yml`

- [ ] **Step 1：在「Rename APK」步骤之后插入生成步骤**

在 `android.yml` 的 `- name: Rename APK with version` 步骤之后、`- name: Upload APK artifact` 之前插入：

```yaml
      - name: Generate version.json
        if: startsWith(github.ref, 'refs/tags/')
        run: |
          APK_DIR=easyscreen-android/app/build/outputs/apk/release
          APK_PATH=$(ls "$APK_DIR"/EasyScreen-*.apk | head -n1)
          VERSION_NAME="${{ steps.ver.outputs.name }}"
          VERSION_CODE="${{ github.run_number }}"
          SHA256=$(sha256sum "$APK_PATH" | awk '{print $1}')
          FILE_SIZE=$(stat -c%s "$APK_PATH")
          # tag 注释信息作为更新说明；含 [force] 则标记强制更新
          TAG_MSG=$(git tag -l --format='%(contents)' "v${VERSION_NAME}")
          if echo "$TAG_MSG" | grep -q '\[force\]'; then
            FORCE=true
          else
            FORCE=false
          fi
          RELEASE_NOTES=$(echo "$TAG_MSG" | sed 's/\[force\]//g' | sed ':a;N;$!ba;s/\n/ /g' | sed 's/  */ /g' | sed 's/^ *//;s/ *$//')
          cat > "$APK_DIR/version.json" <<EOF
          {
            "versionCode": ${VERSION_CODE},
            "versionName": "${VERSION_NAME}",
            "sha256": "${SHA256}",
            "fileSize": ${FILE_SIZE},
            "forceUpdate": ${FORCE},
            "minSupportedVersionCode": 1,
            "releaseNotes": "${RELEASE_NOTES}",
            "downloadUrl": "/app/download"
          }
          EOF
          echo "--- generated version.json ---"
          cat "$APK_DIR/version.json"
```

- [ ] **Step 2：把 version.json 一起附加到 Release**

将末尾的 `- name: Attach APK to GitHub Release` 步骤的 `files:` 改为同时包含 version.json：

```yaml
      - name: Attach APK to GitHub Release
        if: startsWith(github.ref, 'refs/tags/')
        uses: softprops/action-gh-release@v2
        with:
          files: |
            easyscreen-android/app/build/outputs/apk/release/EasyScreen-*.apk
            easyscreen-android/app/build/outputs/apk/release/version.json
```

- [ ] **Step 3：本地用 act 或纯 shell 验证生成片段（可选）**

Run（在仓库根，模拟字段）：
```bash
VERSION_NAME=1.2.2 VERSION_CODE=42 FORCE=false RELEASE_NOTES="测试" SHA256=abc FILE_SIZE=100 \
bash -c 'cat <<EOF
{ "versionCode": ${VERSION_CODE}, "versionName": "${VERSION_NAME}", "sha256": "${SHA256}", "fileSize": ${FILE_SIZE}, "forceUpdate": ${FORCE}, "minSupportedVersionCode": 1, "releaseNotes": "${RELEASE_NOTES}", "downloadUrl": "/app/download" }
EOF' | python -m json.tool
```
Expected: 合法 JSON 被格式化输出

- [ ] **Step 4：提交**

```bash
git add .github/workflows/android.yml
git commit -m "ci(android): tag 构建生成并发布 version.json"
```

> **YAML 校验提醒**：`<<EOF` heredoc 在 YAML `run: |` 块内缩进敏感。修改后用 `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/android.yml'))"` 确认 YAML 可解析。

---

# Part C：安卓端

> 约定：新增 Kotlin 文件包名 `to.feng.app.easyscreen.update`（UI 对话框为 `to.feng.app.easyscreen.ui.update`）。单元测试放 `app/src/test/java/...` 对应包下，纯 JVM，用已存在的 `junit:junit:4.13.2`。

### Task C1：版本模型与更新判定（TDD）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdateModels.kt`
- Test: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/update/UpdateModelsTest.kt`

- [ ] **Step 1：写失败测试**

```kotlin
package to.feng.app.easyscreen.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateModelsTest {
    private fun info(code: Int, force: Boolean = false, minSupported: Int = 1) =
        AppVersionInfo(
            versionCode = code, versionName = "x", sha256 = "", fileSize = 0,
            forceUpdate = force, minSupportedVersionCode = minSupported,
            releaseNotes = "", downloadUrl = "/app/download",
        )

    @Test fun sameOrOlderIsNone() {
        assertEquals(UpdateKind.NONE, decideUpdate(current = 10, remote = info(10)))
        assertEquals(UpdateKind.NONE, decideUpdate(current = 10, remote = info(9)))
    }

    @Test fun newerIsOptional() {
        assertEquals(UpdateKind.OPTIONAL, decideUpdate(current = 10, remote = info(11)))
    }

    @Test fun forceFlagMakesForced() {
        assertEquals(UpdateKind.FORCED, decideUpdate(current = 10, remote = info(11, force = true)))
    }

    @Test fun belowMinSupportedIsForced() {
        assertEquals(
            UpdateKind.FORCED,
            decideUpdate(current = 10, remote = info(11, minSupported = 11)),
        )
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest --tests "*.UpdateModelsTest"`
Expected: 编译失败（`AppVersionInfo`/`UpdateKind`/`decideUpdate` 未定义）

- [ ] **Step 3：实现模型**

```kotlin
package to.feng.app.easyscreen.update

/** 对应信令服务器 /app/version.json 返回体 */
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val fileSize: Long,
    val forceUpdate: Boolean,
    val minSupportedVersionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
)

enum class UpdateKind { NONE, OPTIONAL, FORCED }

/** 比较当前 versionCode 与远端元数据，决定更新类型。 */
fun decideUpdate(current: Int, remote: AppVersionInfo): UpdateKind {
    if (remote.versionCode <= current) return UpdateKind.NONE
    if (remote.forceUpdate || current < remote.minSupportedVersionCode) return UpdateKind.FORCED
    return UpdateKind.OPTIONAL
}
```

- [ ] **Step 4：运行确认通过**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest --tests "*.UpdateModelsTest"`
Expected: PASS

- [ ] **Step 5：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdateModels.kt easyscreen-android/app/src/test/java/to/feng/app/easyscreen/update/UpdateModelsTest.kt
git commit -m "feat(android): 更新元数据模型与版本判定逻辑"
```

---

### Task C2：服务器 URL 推导（TDD）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/ServerUrlUtil.kt`
- Test: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/update/ServerUrlUtilTest.kt`

- [ ] **Step 1：写失败测试**

```kotlin
package to.feng.app.easyscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlUtilTest {
    @Test fun wssNoPort() {
        assertEquals("https://a.com", deriveHttpBase("wss://a.com/ws"))
    }
    @Test fun wsWithPort() {
        assertEquals("http://a.com:8081", deriveHttpBase("ws://a.com:8081/ws"))
    }
    @Test fun wssWithPort() {
        assertEquals("https://a.com:443", deriveHttpBase("wss://a.com:443/ws"))
    }
    @Test fun trimsWhitespace() {
        assertEquals("https://a.com", deriveHttpBase("  wss://a.com/ws  "))
    }
    @Test fun invalidReturnsNull() {
        assertNull(deriveHttpBase("not a url"))
        assertNull(deriveHttpBase(""))
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest --tests "*.ServerUrlUtilTest"`
Expected: 编译失败（`deriveHttpBase` 未定义）

- [ ] **Step 3：实现**

```kotlin
package to.feng.app.easyscreen.update

import java.net.URI

/**
 * 把信令 WebSocket 地址（wss://host[:port]/ws）推导为 HTTP base（https://host[:port]）。
 * 更新接口在此 base 下：<base>/app/version.json、<base>/app/download。
 * 解析失败返回 null。
 */
fun deriveHttpBase(wsUrl: String): String? {
    val trimmed = wsUrl.trim()
    if (trimmed.isEmpty()) return null
    val uri = try { URI(trimmed) } catch (e: Exception) { return null }
    val scheme = when (uri.scheme?.lowercase()) {
        "wss", "https" -> "https"
        "ws", "http" -> "http"
        else -> return null
    }
    val host = uri.host ?: return null
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$portPart"
}
```

- [ ] **Step 4：运行确认通过**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest --tests "*.ServerUrlUtilTest"`
Expected: PASS

- [ ] **Step 5：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/ServerUrlUtil.kt easyscreen-android/app/src/test/java/to/feng/app/easyscreen/update/ServerUrlUtilTest.kt
git commit -m "feat(android): WebSocket 地址推导 HTTP base 工具"
```

---

### Task C3：sha256 工具（TDD）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/Sha256.kt`
- Test: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/update/Sha256Test.kt`

- [ ] **Step 1：写失败测试**

```kotlin
package to.feng.app.easyscreen.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class Sha256Test {
    @Test fun knownVectorBytes() {
        // sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.ofBytes("abc".toByteArray()),
        )
    }

    @Test fun fileMatchesBytes() {
        val tmp = File.createTempFile("sha", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes("hello-world".toByteArray())
        assertEquals(Sha256.ofBytes("hello-world".toByteArray()), Sha256.ofFile(tmp))
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest --tests "*.Sha256Test"`
Expected: 编译失败（`Sha256` 未定义）

- [ ] **Step 3：实现**

```kotlin
package to.feng.app.easyscreen.update

import java.io.File
import java.security.MessageDigest

object Sha256 {
    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 4：运行确认通过**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest --tests "*.Sha256Test"`
Expected: PASS

- [ ] **Step 5：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/Sha256.kt easyscreen-android/app/src/test/java/to/feng/app/easyscreen/update/Sha256Test.kt
git commit -m "feat(android): sha256 校验工具"
```

---

### Task C4：UpdatePrefs（跳过版本 / 上次检查时间）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdatePrefs.kt`

- [ ] **Step 1：实现（沿用项目 SharedPreferences 模式，复用同一 PREFS_NAME）**

```kotlin
package to.feng.app.easyscreen.update

import android.content.Context

/**
 * 更新相关偏好：记录用户「跳过的版本」，避免普通更新每次启动都弹窗。
 * 复用项目统一的 easyscreen_prefs 文件。
 */
object UpdatePrefs {
    private const val PREFS_NAME = "easyscreen_prefs"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version_code"

    fun getSkippedVersion(context: Context): Int {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_SKIPPED_VERSION, 0)
    }

    fun setSkippedVersion(context: Context, versionCode: Int) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_SKIPPED_VERSION, versionCode).apply()
    }
}
```

- [ ] **Step 2：编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 3：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdatePrefs.kt
git commit -m "feat(android): 更新偏好（跳过版本记录）"
```

---

### Task C5：UpdateRepository（拉取 version.json）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdateRepository.kt`

- [ ] **Step 1：实现（OkHttp + Gson，挂在 IO 协程）**

```kotlin
package to.feng.app.easyscreen.update

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 从信令服务器拉取最新版本元数据。
 * @param wsServerUrl 设置里保存的 wss://host/ws 地址
 */
class UpdateRepository(
    private val client: OkHttpClient = defaultClient,
    private val gson: Gson = Gson(),
) {
    suspend fun fetchLatest(wsServerUrl: String): Result<AppVersionInfo> = withContext(Dispatchers.IO) {
        val base = deriveHttpBase(wsServerUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("无法解析服务器地址"))
        val url = "$base/app/version.json"
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("HTTP ${resp.code}"))
                }
                val body = resp.body?.string()
                    ?: return@withContext Result.failure(RuntimeException("空响应"))
                val info = gson.fromJson(body, AppVersionInfo::class.java)
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 把相对 downloadUrl 拼成绝对地址；若已是绝对地址则原样返回。 */
    fun resolveDownloadUrl(wsServerUrl: String, info: AppVersionInfo): String? {
        if (info.downloadUrl.startsWith("http://") || info.downloadUrl.startsWith("https://")) {
            return info.downloadUrl
        }
        val base = deriveHttpBase(wsServerUrl) ?: return null
        return base + info.downloadUrl
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 2：编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 3：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdateRepository.kt
git commit -m "feat(android): 拉取版本元数据仓库"
```

---

### Task C6：ApkDownloader（下载 + 进度 + 校验）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/ApkDownloader.kt`

- [ ] **Step 1：实现**

```kotlin
package to.feng.app.easyscreen.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载 APK 到 externalCacheDir/update/，带进度回调，下载后做 sha256 校验。
 */
class ApkDownloader(
    private val client: OkHttpClient = defaultClient,
) {
    /**
     * @param onProgress 0..100；总长未知时回调 -1
     * @return 成功返回已校验的 APK File；失败返回 Result.failure
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSha256: String,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.externalCacheDir, "update").apply { mkdirs() }
        val target = File(dir, "latest.apk")
        val tmp = File(dir, "latest.apk.tmp")
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("HTTP ${resp.code}"))
                }
                val body = resp.body ?: return@withContext Result.failure(RuntimeException("空响应"))
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(((read * 100) / total).toInt()) else onProgress(-1)
                        }
                    }
                }
            }
            // 校验
            val actual = Sha256.ofFile(tmp)
            if (expectedSha256.isNotEmpty() && !actual.equals(expectedSha256, ignoreCase = true)) {
                tmp.delete()
                return@withContext Result.failure(RuntimeException("文件校验失败，请重试"))
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext Result.failure(RuntimeException("保存失败"))
            }
            onProgress(100)
            Result.success(target)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 2：编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 3：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/ApkDownloader.kt
git commit -m "feat(android): APK 下载器（进度+sha256校验）"
```

---

### Task C7：Manifest 权限 + FileProvider + file_paths.xml

**Files:**
- Modify: `easyscreen-android/app/src/main/AndroidManifest.xml`
- Create: `easyscreen-android/app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1：新增 file_paths.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- 暴露 externalCacheDir/update/ 下的 APK 给系统安装器 -->
    <external-cache-path name="update" path="update/" />
</paths>
```

- [ ] **Step 2：Manifest 加权限（在现有 SYSTEM_ALERT_WINDOW 之后）**

在 `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />` 之后插入：

```xml
    <!-- 安装 APK（在线更新触发系统安装器，Android 8+ 需用户授权「允许安装未知应用」） -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

- [ ] **Step 3：Manifest 在 `<application>` 内、`</application>` 之前注册 FileProvider**

在 `ScreenCaptureService` 的 `<service .../>` 之后插入：

```xml
        <!-- FileProvider：把下载的 APK 以 content:// URI 提供给系统安装器 -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 4：编译确认资源/清单合法**

Run: `cd easyscreen-android && ./gradlew :app:processReleaseManifest`
Expected: 通过

- [ ] **Step 5：提交**

```bash
git add easyscreen-android/app/src/main/AndroidManifest.xml easyscreen-android/app/src/main/res/xml/file_paths.xml
git commit -m "feat(android): 安装权限与 FileProvider 配置"
```

---

### Task C8：ApkInstaller（FileProvider + 安装 Intent + 权限检查）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/ApkInstaller.kt`

- [ ] **Step 1：实现**

```kotlin
package to.feng.app.easyscreen.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    /** Android 8+ 需用户授权本应用「安装未知应用」。 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「允许安装未知应用」授权页。 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 用 FileProvider 生成 content:// URI 并触发系统安装器。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
```

> `canRequestPackageInstalls()` 自 API 26 起可用，项目 minSdk=26，无需版本判断。

- [ ] **Step 2：编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 3：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/ApkInstaller.kt
git commit -m "feat(android): APK 安装器（FileProvider+权限引导）"
```

---

### Task C9：UpdateController（状态机，供 UI 观察）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdateController.kt`

- [ ] **Step 1：实现**

```kotlin
package to.feng.app.easyscreen.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** UI 可观察的更新状态。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: AppVersionInfo, val kind: UpdateKind) : UpdateState
    data class Downloading(val info: AppVersionInfo, val kind: UpdateKind, val progress: Int) : UpdateState
    data class ReadyToInstall(val info: AppVersionInfo, val kind: UpdateKind, val apk: File) : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 协调检查→下载→安装的状态机。UI 观察 [state]。
 */
class UpdateController(
    private val repository: UpdateRepository = UpdateRepository(),
    private val downloader: ApkDownloader = ApkDownloader(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /**
     * 检查更新。
     * @param silent 启动静默检查：已是最新或已跳过该版本时不改变为打扰态。
     */
    fun check(scope: CoroutineScope, context: Context, wsServerUrl: String, currentCode: Int, silent: Boolean) {
        if (!silent) _state.value = UpdateState.Checking
        scope.launch {
            val result = repository.fetchLatest(wsServerUrl)
            val info = result.getOrElse {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed(it.message ?: "检查失败")
                return@launch
            }
            when (decideUpdate(currentCode, info)) {
                UpdateKind.NONE -> _state.value = if (silent) UpdateState.Idle else UpdateState.UpToDate
                UpdateKind.OPTIONAL -> {
                    if (silent && UpdatePrefs.getSkippedVersion(context) == info.versionCode) {
                        _state.value = UpdateState.Idle
                    } else {
                        _state.value = UpdateState.Available(info, UpdateKind.OPTIONAL)
                    }
                }
                UpdateKind.FORCED -> _state.value = UpdateState.Available(info, UpdateKind.FORCED)
            }
        }
    }

    fun startDownload(scope: CoroutineScope, context: Context, wsServerUrl: String) {
        val current = _state.value
        val (info, kind) = when (current) {
            is UpdateState.Available -> current.info to current.kind
            else -> return
        }
        val url = repository.resolveDownloadUrl(wsServerUrl, info) ?: run {
            _state.value = UpdateState.Failed("无法解析下载地址")
            return
        }
        _state.value = UpdateState.Downloading(info, kind, 0)
        scope.launch {
            val result = downloader.download(context, url, info.sha256) { p ->
                val s = _state.value
                if (s is UpdateState.Downloading) {
                    _state.value = s.copy(progress = p.coerceAtLeast(0))
                }
            }
            result.fold(
                onSuccess = { _state.value = UpdateState.ReadyToInstall(info, kind, it) },
                onFailure = { _state.value = UpdateState.Failed(it.message ?: "下载失败") },
            )
        }
    }

    fun skip(context: Context) {
        val s = _state.value
        if (s is UpdateState.Available && s.kind == UpdateKind.OPTIONAL) {
            UpdatePrefs.setSkippedVersion(context, s.info.versionCode)
        }
        _state.value = UpdateState.Idle
    }

    fun dismiss() { _state.value = UpdateState.Idle }
}
```

- [ ] **Step 2：编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 3：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/update/UpdateController.kt
git commit -m "feat(android): 更新流程状态机控制器"
```

---

### Task C10：UpdateDialog（老人友好对话框）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/update/UpdateDialog.kt`

- [ ] **Step 1：实现**

```kotlin
package to.feng.app.easyscreen.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.feng.app.easyscreen.update.UpdateKind
import to.feng.app.easyscreen.update.UpdateState

/**
 * 老人友好更新对话框。根据 [state] 渲染：发现新版 / 下载中 / 待安装。
 * 强制更新（FORCED）时不可取消、无「稍后」。
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (info, kind) = when (state) {
        is UpdateState.Available -> state.info to state.kind
        is UpdateState.Downloading -> state.info to state.kind
        is UpdateState.ReadyToInstall -> state.info to state.kind
        else -> return
    }
    val forced = kind == UpdateKind.FORCED
    // 非强制时：发现新版与下载完成都允许「稍后」；下载中不可中断
    val dismissable = !forced && (state is UpdateState.Available || state is UpdateState.ReadyToInstall)

    AlertDialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        title = {
            Text(
                text = "发现新版本 ${info.versionName}",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info.releaseNotes.isNotBlank()) {
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodyLarge)
                }
                when (state) {
                    is UpdateState.Downloading -> {
                        Text("正在下载… ${state.progress}%", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                    is UpdateState.ReadyToInstall ->
                        Text("下载完成，点击安装。", style = MaterialTheme.typography.bodyMedium)
                    else ->
                        if (forced) Text("此版本需要更新后才能继续使用。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available ->
                    Button(onClick = onUpdate) { Text("立即更新", style = MaterialTheme.typography.titleMedium) }
                is UpdateState.Downloading ->
                    Button(onClick = {}, enabled = false) { Text("下载中…") }
                is UpdateState.ReadyToInstall ->
                    Button(onClick = onInstall) { Text("立即安装", style = MaterialTheme.typography.titleMedium) }
                else -> {}
            }
        },
        dismissButton = {
            if (dismissable) {
                TextButton(onClick = onSkip) { Text("稍后") }
            }
        },
    )
}
```

> `LinearProgressIndicator(progress = { ... })` 的 lambda 形式来自 Material3（项目 compose-bom 2026.05.01 已支持）。

- [ ] **Step 2：编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 3：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/update/UpdateDialog.kt
git commit -m "feat(android): 老人友好更新对话框"
```

---

### Task C11：接入启动检查与设置页手动检查

**Files:**
- Modify: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/MainScreen.kt`
- Modify: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/SettingsScreen.kt`

- [ ] **Step 1：MainScreen 启动静默检查 + 挂载对话框**

在 `MainScreen` 的 `import` 区补充：

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import to.feng.app.easyscreen.BuildConfig
import to.feng.app.easyscreen.update.ApkInstaller
import to.feng.app.easyscreen.update.UpdateController
import to.feng.app.easyscreen.update.UpdateState
import to.feng.app.easyscreen.ui.update.UpdateDialog
import androidx.compose.runtime.LaunchedEffect
```

在 `MainScreen` 函数体内、`val serverUrl by ...` 之后插入：

```kotlin
    val scope = rememberCoroutineScope()
    val updateController = remember { UpdateController() }
    val updateState by updateController.state.collectAsState()

    // 启动静默检查（每次进入主页触发一次）
    LaunchedEffect(Unit) {
        updateController.check(
            scope = scope,
            context = context,
            wsServerUrl = serverUrl,
            currentCode = BuildConfig.VERSION_CODE,
            silent = true,
        )
    }

    // 更新对话框
    UpdateDialog(
        state = updateState,
        onUpdate = {
            if (ApkInstaller.canInstall(context)) {
                updateController.startDownload(scope, context, serverUrl)
            } else {
                ApkInstaller.openInstallPermissionSettings(context)
            }
        },
        onInstall = {
            val s = updateState
            if (s is UpdateState.ReadyToInstall) {
                if (ApkInstaller.canInstall(context)) ApkInstaller.install(context, s.apk)
                else ApkInstaller.openInstallPermissionSettings(context)
            }
        },
        onSkip = { updateController.skip(context) },
        onDismiss = { updateController.dismiss() },
    )
```

> 注意：`onUpdate` 先检查安装权限，未授权先引导到系统设置；用户授权返回后再次点「立即更新」即可下载。

- [ ] **Step 2：SettingsScreen 关于区加「检查更新」按钮**

在 `SettingsScreen` 的 import 区补充：

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import to.feng.app.easyscreen.update.ApkInstaller
import to.feng.app.easyscreen.update.UpdateController
import to.feng.app.easyscreen.update.UpdateState
import to.feng.app.easyscreen.ui.update.UpdateDialog
```

在 `SettingsScreen` 函数体顶部（`val context = LocalContext.current` 之后）插入：

```kotlin
    val scope = rememberCoroutineScope()
    val updateController = remember { UpdateController() }
    val updateState by updateController.state.collectAsState()
```

在「关于」`SectionCard` 内、版本 Row 之后、`HorizontalDivider()` 之前插入「检查更新」行：

```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("检查更新", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when (val s = updateState) {
                                is UpdateState.Checking -> "检查中…"
                                is UpdateState.UpToDate -> "已是最新版本"
                                is UpdateState.Failed -> s.message
                                else -> "从服务器获取最新版本"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = {
                        updateController.check(
                            scope = scope,
                            context = context,
                            wsServerUrl = serverUrl,
                            currentCode = BuildConfig.VERSION_CODE,
                            silent = false,
                        )
                    }) { Text("检查") }
                }
```

并在 `SettingsScreen` 的 `Scaffold { ... }` 末尾（`Column` 之外，函数返回前）挂载对话框——把现有 `Scaffold(...) { inner -> Column { ... } }` 包进一个 `Box`，或在 `Column` 之后同级添加。最简方式：在 `Column` 闭合后、`}` (Scaffold content lambda) 之前添加：

```kotlin
            UpdateDialog(
                state = updateState,
                onUpdate = {
                    if (ApkInstaller.canInstall(context)) updateController.startDownload(scope, context, serverUrl)
                    else ApkInstaller.openInstallPermissionSettings(context)
                },
                onInstall = {
                    val s = updateState
                    if (s is UpdateState.ReadyToInstall) {
                        if (ApkInstaller.canInstall(context)) ApkInstaller.install(context, s.apk)
                        else ApkInstaller.openInstallPermissionSettings(context)
                    }
                },
                onSkip = { updateController.dismiss() },
                onDismiss = { updateController.dismiss() },
            )
```

> `BuildConfig` 已在 SettingsScreen 中导入（现有「关于」区已用）。`Arrangement`/`Alignment`/`Row`/`Column`/`OutlinedButton` 均已 import。

- [ ] **Step 3：整体编译**

Run: `cd easyscreen-android && ./gradlew :app:compileReleaseKotlin`
Expected: 通过

- [ ] **Step 4：提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/MainScreen.kt easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/SettingsScreen.kt
git commit -m "feat(android): 接入启动自动检查与设置页手动检查更新"
```

---

### Task C12：构建 + 安卓单元测试全跑 + Lint

**Files:** 无（验证任务）

- [ ] **Step 1：跑全部单元测试**

Run: `cd easyscreen-android && ./gradlew :app:testReleaseUnitTest`
Expected: PASS（UpdateModelsTest / ServerUrlUtilTest / Sha256Test）

- [ ] **Step 2：组装 debug APK 确认整体可构建**

Run: `cd easyscreen-android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3：真机/模拟器手动验证（记录结果，不通过则回到对应 Task）**

手动检查清单：
1. 设置页点「检查」→ 若服务器无更新显示「已是最新版本」。
2. 临时把 `appVersionCode` 调低（build.gradle.kts 默认值改小）重新装，启动应弹「发现新版本」。
3. 首次点「立即更新」→ 跳转「允许安装未知应用」；授权返回后再点 → 下载进度 → 「立即安装」→ 系统安装器。
4. 强制更新（version.json 的 forceUpdate=true）时对话框无「稍后」、点外部不消失。

- [ ] **Step 4：提交（若步骤 3 临时改了版本号，记得还原）**

```bash
git add -A
git commit -m "test(android): 在线更新单元测试与构建验证"
```

---

# Part D：文档

### Task D1：更新 README / DEVELOPMENT / deploy 文档

**Files:**
- Modify: `README.md`
- Modify: `DEVELOPMENT.md`
- Modify: `easyscreen-signaling/deploy/README.md`

- [ ] **Step 1：README.md「服务端部署」后增加「App 在线更新」小节**

在 `## 服务状态端点` 之前插入：

```markdown
## App 在线更新

信令服务器可作为 GitHub Release 的国内缓存代理，让老人端 App 自动检查/下载更新：

| 端点 | 用途 |
|------|------|
| `/app/version.json` | 最新版本元数据（懒加载从 GitHub 同步并缓存） |
| `/app/download` | 缓存的最新 APK（支持断点续传） |

启用方式：在 docker-compose 环境变量设置 `EASYSCREEN_GITHUB_REPO=<owner>/<repo>`（留空则关闭）。可选 `EASYSCREEN_GITHUB_TOKEN` 提高 GitHub API 限流。APK 缓存在命名卷 `easyscreen-apk-cache`（`/app/cache`），不烧进镜像。

发版流程不变：打 `v*` tag → CI 构建 APK 并生成 `version.json` 一起发布到 GitHub Release → 服务器下次请求时自动同步。tag 注释信息（`git tag -a v1.2.2 -m "..."`）作为更新说明；注释含 `[force]` 时该版本标记为强制更新。
```

- [ ] **Step 2：DEVELOPMENT.md 增加更新机制说明**

在 `## 信令协议要点` 之前插入：

```markdown
## App 在线更新机制

- **CI**：`android.yml` 在 tag 构建时除 APK 外生成 `version.json`（versionCode/versionName/sha256/fileSize/forceUpdate/minSupportedVersionCode/releaseNotes/downloadUrl），一并附到 GitHub Release。
- **信令服务**：`app_update.go` 懒加载（默认 TTL 15min）从 GitHub `releases/latest` 同步 `version.json` 与 APK 到 `EASYSCREEN_APP_CACHE_DIR`，sha256 校验后缓存；GitHub 不可达时降级返回旧缓存。环境变量：`EASYSCREEN_GITHUB_REPO`、`EASYSCREEN_GITHUB_TOKEN`、`EASYSCREEN_APP_CACHE_DIR`、`EASYSCREEN_APP_SYNC_TTL`。
- **安卓端**：`update/` 包。启动静默检查、设置页手动检查；比较 `BuildConfig.VERSION_CODE`；下载到 `externalCacheDir/update/`、sha256 校验；经 FileProvider（`${applicationId}.fileprovider`）触发系统安装器，需 `REQUEST_INSTALL_PACKAGES` 权限 + 用户授权「允许安装未知应用」。
- **强制更新**：`forceUpdate=true` 或当前 `versionCode < minSupportedVersionCode` 时对话框不可取消。
```

- [ ] **Step 3：deploy/README.md 补充缓存目录与环境变量**

在 `easyscreen-signaling/deploy/README.md` 中 systemd 环境变量相关位置追加说明（若无对应小节，在文件末尾追加）：

```markdown
## App 在线更新（可选）

免 Docker 的 systemd 方案启用 App 更新：在 service 的环境变量中加入
`EASYSCREEN_GITHUB_REPO=<owner>/<repo>`、可选 `EASYSCREEN_GITHUB_TOKEN`，
并设置可写的 `EASYSCREEN_APP_CACHE_DIR`（如 `/var/lib/easyscreen/cache`）。
反代需放行 `/app/version.json` 与 `/app/download`（APK 可能较大，注意上传/下载体大小限制）。
```

- [ ] **Step 4：提交**

```bash
git add README.md DEVELOPMENT.md easyscreen-signaling/deploy/README.md
git commit -m "docs: 补充 App 在线更新机制与部署说明"
```

---

## 最终验收清单

- [ ] 信令端 `go test ./...` 全绿
- [ ] 安卓端 `./gradlew :app:testReleaseUnitTest` 全绿
- [ ] 安卓端 `./gradlew :app:assembleDebug` 成功
- [ ] `docker compose config` 通过
- [ ] `android.yml` YAML 可解析
- [ ] 手动验证（Task C12 Step 3）四项通过
- [ ] 文档三处更新

## 实现注意事项（写给执行者）

- **DRY**：SharedPreferences 复用 `easyscreen_prefs` 文件名，勿新建。
- **YAGNI**：不做差分更新、灰度、静默安装——见 spec「非目标」。
- **降级优先**：服务器端任何 GitHub 失败都不能让已有缓存失效；App 端启动静默检查失败必须无声。
- **类型一致**：`AppVersionInfo` 字段名三端（Go json tag / CI json / Kotlin data class）必须逐字对应。
- **下载地址**：始终由 App 用 `deriveHttpBase(serverUrl) + downloadUrl` 拼接，不信任服务器返回的绝对 URL（除非已是 http(s) 开头）。
