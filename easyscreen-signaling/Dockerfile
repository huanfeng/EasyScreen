# syntax=docker/dockerfile:1.7

# ---------- 构建阶段 ----------
FROM golang:1.26-alpine AS builder

WORKDIR /build

# 利用 Docker 缓存：先拷 go.mod/sum
COPY go.mod go.sum ./
RUN go mod download

COPY *.go ./

# 版本信息由构建方通过 --build-arg 注入（VPS 部署目录非 git 仓库，无法在容器内读取）
ARG APP_VERSION=dev
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown

# 静态、精简、去符号；用 -X 注入版本变量
RUN CGO_ENABLED=0 GOOS=linux \
    go build -trimpath \
    -ldflags="-s -w -X main.appVersion=${APP_VERSION} -X main.gitCommit=${GIT_COMMIT} -X main.buildTime=${BUILD_TIME}" \
    -o easyscreen-signaling .

# ---------- 运行阶段 ----------
FROM alpine:3.20

RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=builder /build/easyscreen-signaling .
COPY web ./web
RUN chown -R app:app /app

USER app

EXPOSE 8081
ENV EASYSCREEN_ADDR=":8081" \
    TZ=Asia/Shanghai

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:8081/health || exit 1

CMD ["./easyscreen-signaling"]
