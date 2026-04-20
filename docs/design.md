# 角色设定与项目目标
你是一个资深的 Android 系统开发工程师和 Go 后端架构师。
我们的目标是开发一款名为 "EasyScreen" 的极简 Android 远程看屏应用，专为老年人设计。
项目采用无特权的普通应用方案，核心诉求是：被控端零门槛（输入 6 位数即可连接）、高稳定、极低延迟。

# 技术栈选型
* **后端信令服务:** Go + `gorilla/websocket` (轻量级，负责状态机和 SDP/ICE 交换)
* **打洞服务:** Coturn (使用公共的或后续自行搭建的 STUN/TURN，代码中预留配置项)
* **Android 客户端:** Kotlin + Jetpack Compose (UI) + `org.webrtc:google-webrtc` (核心流媒体)
* **屏幕采集:** Android `MediaProjection` API + Foreground Service

# 系统交互核心流程 (6位验证码直连)
1. 被控端(父母)打开App，连接WebSocket，服务端下发一个6位随机数字(RoomID/SessionID)，界面大字显示。
2. 控制端(子女)打开App，输入这6位数字，点击连接。
3. WebSocket 绑定双方，控制端创建 WebRTC Offer 并发送。
4. 被控端收到 Offer，自动触发 `MediaProjection` 权限申请。
5. 父母点击“允许截图”后，App 启动前台服务保活，将获取到的 Intent 喂给 WebRTC 的 `ScreenCapturerAndroid`。
6. 被控端生成 Answer 返回，双方交换 ICE，建立 P2P 视频流。

---

# 实施计划 (请务必严格按阶段执行，不要跨阶段跳跃)

## Phase 1: 构建 Go 信令服务器 (Signaling Server)
**任务：** 创建一个基于 WebSocket 的信令服务器。
**需求细节：**
1. 初始化 Go module：`go mod init easyscreen-signaling`
2. 引入 `github.com/gorilla/websocket`。
3. 定义 WebSocket 消息 JSON 结构：`type` (例如: `register`, `join`, `offer`, `answer`, `candidate`, `error`), `room_id`, `data` (Payload)。
4. 内存状态管理：维护一个 Map，记录 `RoomID` (6位随机数) 对应的 Host (被控端) 和 Guest (控制端) 的 WebSocket Conn。
5. 核心逻辑：
   - 被控端连入，生成 6 位不重复的纯数字 ID 返回。
   - 控制端拿着 6 位数字发 `join` 消息，服务器校验有效性，绑定两者。
   - 收到 `offer`/`answer`/`candidate` 消息时，只做盲转发（Host 发的转给 Guest，Guest 发的转给 Host）。
6. 输出要求：提供完整的 `main.go`，并确保有良好的并发安全（Mutex 锁）和心跳机制（Ping/Pong 剔除死连接）。

## Phase 2: Android 客户端基础架构与 UI (Kotlin + Compose)
**任务：** 搭建 Android 工程骨架和基础交互界面。
**需求细节：**
1. 创建 Android 项目，包名 `com.example.easyscreen`。
2. 权限声明 (AndroidManifest.xml)：网络、前台服务 (`FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，需适配 Android 14)。
3. 使用 Jetpack Compose 编写两个极简页面：
   - **主页:** 提供两个入口大按钮：“我要共享屏幕(父母用)” 和 “我要看别人屏幕(子女用)”。
   - **共享端页面:** 屏幕中央显示超大号字体的 6 位数字（从后端获取的占位符），以及状态提示（等待连接...）。
   - **控制端页面:** 一个 TextField 供输入 6 位数字，加一个“开始连接”按钮，下方预留一个 `AndroidView` (用于稍后挂载 WebRTC 的 `SurfaceViewRenderer`)。
4. 输出要求：暂不涉及 WebRTC，仅把 UI 导航、WebSocket 客户端基础通信（收发 JSON）和权限申请的骨架写好。

## Phase 3: WebRTC 初始化与屏幕采集 (核心难点)
**任务：** 引入 WebRTC 并实现 `MediaProjection` 采集。
**需求细节：**
1. 引入 WebRTC 依赖：`implementation 'io.github.webrtc-sdk:android:114.5735.02'` (或最新可用版本)。
2. 在控制端：初始化 `PeerConnectionFactory`，创建 `PeerConnection`，生成 Offer。
3. 在被控端：
   - 收到控制端的 Offer 后，调用 `MediaProjectionManager.createScreenCaptureIntent()`，使用 `rememberLauncherForActivityResult` 弹出系统授权框。
   - 拿到授权结果后，**立即**启动一个 Foreground Service，挂载常驻通知，声明 `foregroundServiceType="mediaProjection"`。
   - 在 Service 内或全局单例中，使用 `ScreenCapturerAndroid(intent, ...)` 创建 `VideoCapturer`。
   - 创建 `VideoTrack`，加入到 `PeerConnection` 中，生成 Answer 发回。
4. 打洞配置：在创建 `PeerConnection` 时，硬编码填入一组测试用的公共 STUN 服务器（如 `stun.l.google.com:19302`），以保证局域网外也能尽量打通。
5. 渲染：将控制端收到的 `VideoTrack` 绑定到 Compose 页面中的 `SurfaceViewRenderer` 上。

## Phase 4: 联调与保活优化
**任务：** 完善连接稳定性与错误处理。
**需求细节：**
1. 处理 ICE Candidate 的互相传递和添加。
2. 处理断网重连和对端主动挂断的清理逻辑（销毁 PeerConnection，释放 MediaProjection，停止前台服务）。
3. 调整 WebRTC 的编码参数（限制最高分辨率和码率），以保证在弱网下优先保流畅不卡顿。

