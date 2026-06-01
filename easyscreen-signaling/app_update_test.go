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
