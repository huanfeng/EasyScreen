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
