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
