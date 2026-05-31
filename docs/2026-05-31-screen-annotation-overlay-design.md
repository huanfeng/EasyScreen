# 屏幕画笔标注（Screen Annotation Overlay）设计文档

- 日期：2026-05-31
- 状态：已确认，待实施计划
- 适用项目：EasyScreen 远程看屏指导软件

## 1. 背景与目标

帮老人远程看屏指导时，纯语音指导效率低——老人不易理解"按哪个按钮"。本功能让**指导者（子女）在自己手机上对着老人屏幕画面圈点标注**，标注以**系统浮窗**实时显示在**老人真实屏幕上**（浮在微信/支付宝等任意 App 之上），并且**触摸穿透**，老人照常操作下方真实按钮。

### 角色映射（关键，勿混淆）
- **指导者 = 子女 = 观看端 = Guest**（看老人屏幕，画标注）
- **被指导者 = 老人 = 共享端 = Host**（屏幕被共享，浮窗显示标注）

## 2. 已确认的产品决策

| 维度 | 决策 |
|---|---|
| 标注方向 | 子女画 → 老人屏幕浮窗显示 |
| 核心交互 | 实时指针 与 留痕标注 两者都要，可切换 |
| 工具集 | 圆圈/圈选、箭头、自由画笔、点击波纹 |
| 清除方式 | 自动淡出 + 手动一键清空 |
| 老人端提醒 | 仅视觉浮窗，不加提示音/震动 |
| 标注开启 | 子女手动切换"标注模式"（默认纯观看，防误触） |
| 权限引导 | 仅靠子女语音口头指导老人开浮窗权限（不做复杂引导卡） |

## 3. 关键技术选型

### 3.1 老人端渲染方式：系统浮窗 + 原生 Canvas
- **选定**：`WindowManager` 添加 `TYPE_APPLICATION_OVERLAY` 全屏透明 View，原生 Canvas 绘制。
  - 能覆盖任意 App；`FLAG_NOT_TOUCHABLE` 实现触摸穿透；浮窗内用原生 View 比 Compose 更稳。
- **淘汰**：透明 Activity（盖不住其他 App）；把标注烧进 WebRTC 视频帧（老人自己屏幕看不到，违背目标）。
- **设计副产物**：MediaProjection 采集整屏合成画面（含系统浮窗），故老人屏上的标注会被采集回传到子女视频里，子女天然获得"标注已送达"确认，无需额外回执。

### 3.2 坐标系：归一化 [0,1]
- 坐标相对"共享画面"本身，归一化到 [0,1]。
- 子女端：触点相对"视频实际渲染矩形"（扣除 letterbox 黑边）换算为 [0,1]。
- 老人端：[0,1] 按当前真实屏幕像素还原。
- 与 WebRTC 实际编码分辨率（480p/720p/1080p）解耦，天然适配不同画质与机型。

## 4. 架构与数据流

```
子女(Guest) 画笔触摸
  → 换算归一化坐标 [0,1]
  → SignalingClient.send(draw_command)   （本地同时回显）
  → WebSocket
  → Go 信令服务 forwardMessage（Guest→Host，附 guest_id）
  → 老人(Host) SignalingClient 收到 draw_command
  → AnnotationOverlayManager 更新标注列表
  → AnnotationOverlayView.invalidate() 系统浮窗重绘
  → 老人看到圈/箭头/指针，跟着操作
```

**复用现有信令通道，完全不改动 WebRTC 媒体协商与媒体流。**

## 5. 消息协议

在 `SignalingMessage.kt` 的 `MessageType` 新增：

```kotlin
const val DRAW_COMMAND = "draw_command"
```

新增载荷数据类：

```kotlin
data class DrawPayload(
  val id: String = "",        // 每条标注唯一 id（一笔/一个图形一个 id）
  val op: String = "",        // begin | point | end | tap | clear
  val tool: String = "",      // laser | pen | circle | arrow | ripple
  val x: Float = 0f,          // 归一化 0..1：当前点/圆心/起点
  val y: Float = 0f,
  val x2: Float = 0f,         // 归一化：箭头终点/圈边缘点
  val y2: Float = 0f,
  val color: String = "#FF3B30",
  val guestId: String = "",   // 谁画的（多观众区分/上色）
  val ts: Long = 0L
)
```

### 各工具消息流

| 工具 | 交互 | 消息序列 | 老人端表现 |
|---|---|---|---|
| 实时指针 laser | 按住拖动跟手 | `point` 流式（节流）→ 松手 `end` | 一个跟手光点，松手短暂淡出，不留痕 |
| 自由画笔 pen | 拖动画线 | `begin` → 多个 `point` → `end` | 连成折线，自动淡出 |
| 圆圈 circle | 拖动定圆心+半径 | `begin` → `end(x,y,x2,y2)` | 画圆，自动淡出 |
| 箭头 arrow | 起点拖到终点 | `begin(起)` → `end(止)` | 带箭头线，自动淡出 |
| 点击波纹 ripple | 点一下 | `tap(x,y)` | 冒一圈脉冲动画后自动消失 |
| 一键清空 | 按钮 | `clear` | 清掉全部标注 |

- 节流：拖动类约 60–90ms 发一帧，兼顾流畅与流量。
- 可靠性：标注为瞬时指引，丢包不重传，靠自动淡出兜底。

## 6. 组件设计

### 6.1 老人端（Host）
- **权限**：`AndroidManifest` 增加 `SYSTEM_ALERT_WINDOW`；用 `Settings.canDrawOverlays()` 检测；提供跳转 `ACTION_MANAGE_OVERLAY_PERMISSION` 的入口按钮（子女语音指导老人点）。开始共享时复检权限，无权限则提示子女。
- **AnnotationOverlayManager**：
  - 用 `WindowManager` 添加全屏透明 `AnnotationOverlayView`。
  - LayoutParams：`TYPE_APPLICATION_OVERLAY`，flags `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN`，全屏（含状态栏/刘海区），半透明背景。
  - 懒加载：首条标注到达才 `addView`；断开/停止共享/`clear` 后移除。
  - 收到 `draw_command` → 更新标注列表 → `invalidate()`。
- **AnnotationOverlayView**（原生 Canvas）：
  - 维护标注列表，每条带时间戳。
  - 渲染循环（Choreographer / Handler）按存活时长渐隐，到期移除（默认约 5 秒，可配）。
  - ripple 播放脉冲动画后自动移除。
  - 多 guest 标注按 `guestId` 上不同颜色。
- 复用现有 `ScreenCaptureService` 前台保活，不新增 Service。

### 6.2 子女端（Guest）
- **标注模式开关**：默认纯观看（手指滑动不误画）；点"✏️ 标注"进入画笔模式。
- **工具条**：圆圈 / 箭头 / 自由画笔 / 点击波纹 + 颜色（默认红，备黄/蓝）+ 一键清空。
- **透明 Canvas 标注层**盖在 `SurfaceViewRenderer` 上：
  - 仅标注模式下拦截触摸；非标注模式不拦截，正常观看。
  - 触点按"视频实际渲染矩形"（扣除 letterbox）换算为 [0,1]。
  - `SignalingClient.send(draw_command)`，同时本地回显（即时反馈，不等网络往返）。
  - 本地回显与老人端一致的自动淡出。

### 6.3 信令服务（Go）
- `main.go` 把 `draw_command` 纳入 Guest→Host 转发白名单（与 offer/candidate 同路径，自动附 `guest_id`）。
- 多观众：各 guest 标注都转发给老人，按 `guestId` 区分颜色。

## 7. 边界与异常处理

1. **触摸穿透**为硬性要求，否则老人被标注挡住点不到真按钮 → `FLAG_NOT_TOUCHABLE`。
2. **横竖屏旋转**：老人转屏时画面方向变，归一化坐标需按当前屏幕尺寸重映射（必测项）。
3. **锁屏/息屏**：系统浮窗在锁屏上不显示（系统安全限制），亮屏后恢复，可接受。
4. **浮窗权限被系统回收**（MIUI/鸿蒙常见）：每次开始共享复检，没权限提示子女让老人重开。
5. **多观众同时画**：按 `guestId` 上色叠加显示。
6. **本地回显 vs 视频回传重叠**：坐标一致仅轻微重影，可接受；"送达后隐藏本地回显"的优化暂不做（YAGNI）。
7. **隐私**：标注无敏感信息，不持久化存储。
8. **性能**：节流 + 轻量 Canvas + 自动过期清理，长时间使用不堆积。
9. **未共享/老人端离线**：标注仅在共享进行中可用，否则无渲染目标。

## 8. 涉及改动文件（预估）

### Android（`easyscreen-android/app/src/main/java/to/feng/app/easyscreen/`）
- `data/SignalingMessage.kt`：新增 `DRAW_COMMAND` 类型与 `DrawPayload`。
- `data/SignalingClient.kt`：发送/接收 `draw_command`（如需）。
- 新增 `overlay/AnnotationOverlayManager.kt`、`overlay/AnnotationOverlayView.kt`（老人端浮窗）。
- 新增 `ui/annotation/` 下子女端标注层与工具条 Composable。
- `ui/screens/HostScreen.kt` / HostViewModel：接收 `draw_command`、权限检测、驱动浮窗。
- `ui/screens/GuestScreen.kt` / GuestViewModel：标注模式、工具条、发送 `draw_command`、本地回显。
- `AndroidManifest.xml`：新增 `SYSTEM_ALERT_WINDOW`。

### 信令服务（`easyscreen-signaling/`）
- `main.go`：`draw_command` 纳入 Guest→Host 转发白名单。

## 9. 非目标（Out of Scope）

- 不做远程触控注入（老人自己操作）。
- 不做标注历史/录制回放。
- 不做复杂权限引导卡（仅语音口头指导 + 跳转入口）。
- 不做声音/震动提醒。
- 不做"送达后隐藏本地回显"的去重优化。
