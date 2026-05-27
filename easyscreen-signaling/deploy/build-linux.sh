#!/usr/bin/env bash
# 在本地交叉编译出 Linux amd64 二进制，方便 scp 到 VPS
# 使用：bash deploy/build-linux.sh
set -euo pipefail

cd "$(dirname "$0")/.."

OUT="deploy/dist"
mkdir -p "$OUT"

echo "==> 交叉编译 Linux amd64..."
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
    go build -trimpath -ldflags="-s -w" \
    -o "$OUT/easyscreen-signaling" .

echo "==> 同步 web 静态资源"
rm -rf "$OUT/web"
cp -r web "$OUT/web"

echo "==> 打包"
tar -czf "$OUT/easyscreen-signaling-linux-amd64.tar.gz" \
    -C "$OUT" \
    easyscreen-signaling web

ls -lh "$OUT/"
echo ""
echo "完成。下一步："
echo "  scp $OUT/easyscreen-signaling-linux-amd64.tar.gz user@your.vps:/tmp/"
echo "  然后在 VPS 按 deploy/README.md 步骤继续"
