# 屏幕画笔标注（Screen Annotation Overlay）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让子女（观看端/Guest）在自己手机上对老人屏幕画面圈点标注，标注以系统浮窗实时显示在老人（共享端/Host）真实屏幕上，触摸穿透，老人照常操作。

**Architecture:** 复用现有 WebSocket 信令通道新增 `draw_command` 消息（不动 WebRTC 媒体流）。Guest 端透明 Canvas 层捕获触摸→归一化坐标 [0,1]→发送；Go 服务端按既有 Guest→Host 路径转发；Host 端用 `WindowManager` 系统浮窗（`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE`）原生 Canvas 渲染，自动淡出 + 手动清空。

**Tech Stack:** Kotlin 2.x / Jetpack Compose（BOM 2026.05）/ Android `WindowManager` 系统浮窗 / OkHttp WebSocket / Gson / Go 1.26 信令服务。JVM 单元测试用 JUnit4。

**设计文档：** `docs/2026-05-31-screen-annotation-overlay-design.md`

**关键约定（贯穿全计划，类型/命名必须一致）：**
- 包根：`to.feng.app.easyscreen`，新代码放 `annotation/`（纯逻辑+Host 浮窗）与 `ui/annotation/`（Guest Compose 层）。
- 归一化坐标：`[0,1]×[0,1]`，相对"共享画面内容"。
- 消息类型常量：`MessageType.DRAW_COMMAND = "draw_command"`。
- 线材模型：`DrawPayload`（线缆 JSON）→ `AnnotationStore`（纯逻辑）→ `AnnotationOverlayView`（Host 渲染）/ `AnnotationLayer`（Guest 本地回显）。
- 渲染中间模型：`NPoint(x,y)` 归一化点；`RenderAnnotation`（含 alpha、ageMs）。
- 工具常量：`DrawTool` = laser / pen / circle / arrow / ripple；操作常量：`DrawOp` = begin / point / end / tap / clear。
- 构建：`cd easyscreen-android && ./gradlew :app:assembleDebug`；单测：`./gradlew :app:testDebugUnitTest`。

---

## 文件结构总览

**Android 新建：**
- `annotation/AnnotationModels.kt` — `NPoint`、`RenderAnnotation`、`DrawTool`、`DrawOp`
- `annotation/CoordinateMapping.kt` — 纯函数：触点→归一化、归一化→像素
- `annotation/AnnotationStore.kt` — 标注状态机 + 过期/淡出逻辑（纯 JVM）
- `annotation/AnnotationOverlayView.kt` — Host 原生 Canvas View
- `annotation/AnnotationOverlayManager.kt` — Host 系统浮窗单例
- `ui/annotation/AnnotationLayer.kt` — Guest Compose 标注层 + 工具条

**Android 修改：**
- `data/SignalingMessage.kt` — 加 `DRAW_COMMAND` + `DrawPayload`
- `data/SignalingClient.kt` — 加 `sendDraw()`
- `ui/screens/GuestScreen.kt` — 标注模式开关 + 集成标注层 + `sendDraw`
- `ui/screens/HostScreen.kt` — 浮窗权限入口 + 由 `draw_command` 驱动浮窗
- `AndroidManifest.xml` — `SYSTEM_ALERT_WINDOW`
- `app/build.gradle.kts` — 单元测试依赖

**测试新建（`app/src/test/java/to/feng/app/easyscreen/`）：**
- `data/DrawPayloadSerializationTest.kt`
- `annotation/CoordinateMappingTest.kt`
- `annotation/AnnotationStoreTest.kt`

**Go 修改：**
- `easyscreen-signaling/main.go` — `TypeDrawCommand` + switch 分支

---

## Task 0: 单元测试基础设施 + 浮窗权限声明

项目当前无任何测试。先搭建 JVM 单测 source set，并声明系统浮窗权限。

**Files:**
- Modify: `easyscreen-android/app/build.gradle.kts`
- Modify: `easyscreen-android/app/src/main/AndroidManifest.xml`
- Create: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/SanityTest.kt`

- [ ] **Step 1: 添加测试依赖**

在 `app/build.gradle.kts` 的 `dependencies { }` 块末尾（`debugImplementation(...)` 之后、闭合 `}` 之前）加入：

```kotlin
    // 单元测试（纯 JVM，运行在 test source set）
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: 声明系统浮窗权限**

在 `AndroidManifest.xml` 的 `<uses-permission android:name="android.permission.WAKE_LOCK" />` 之后新增一行：

```xml
    <!-- 系统浮窗：老人端在所有 App 之上显示子女的画笔标注 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- [ ] **Step 3: 写一个 sanity 测试确认 test source set 生效**

创建 `app/src/test/java/to/feng/app/easyscreen/SanityTest.kt`：

```kotlin
package to.feng.app.easyscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class SanityTest {
    @Test
    fun testInfraWorks() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 4: 运行测试确认基础设施可用**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "to.feng.app.easyscreen.SanityTest"`
Expected: BUILD SUCCESSFUL，1 test passed。

- [ ] **Step 5: 提交**

```bash
git add easyscreen-android/app/build.gradle.kts easyscreen-android/app/src/main/AndroidManifest.xml easyscreen-android/app/src/test/java/to/feng/app/easyscreen/SanityTest.kt
git commit -m "chore(android): 搭建单元测试基础设施并声明浮窗权限"
```

---

## Task 1: 消息协议 DrawPayload + 序列化测试

**Files:**
- Modify: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/data/SignalingMessage.kt`
- Create: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/data/DrawPayloadSerializationTest.kt`

- [ ] **Step 1: 写失败测试——DrawPayload 能 round-trip 序列化**

创建 `app/src/test/java/to/feng/app/easyscreen/data/DrawPayloadSerializationTest.kt`：

```kotlin
package to.feng.app.easyscreen.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawPayloadSerializationTest {
    private val gson = Gson()

    @Test
    fun drawPayload_serializesWithSnakeCaseGuestId() {
        val p = DrawPayload(
            id = "abc123", op = DrawOp.BEGIN, tool = DrawTool.CIRCLE,
            x = 0.5f, y = 0.25f, x2 = 0.6f, y2 = 0.3f,
            color = "#FF3B30", guestId = "g1", ts = 100L,
        )
        val json = gson.toJson(p)
        // 字段名必须与 Go 端一致（guest_id 蛇形）
        assert(json.contains("\"guest_id\":\"g1\"")) { "actual: $json" }
        assert(json.contains("\"tool\":\"circle\"")) { "actual: $json" }
    }

    @Test
    fun signalingMessage_withDrawPayload_roundTrips() {
        val msg = SignalingMessage(
            type = MessageType.DRAW_COMMAND,
            payload = DrawPayload(id = "x", op = DrawOp.TAP, tool = DrawTool.RIPPLE, x = 0.1f, y = 0.2f),
        )
        val json = gson.toJson(msg)
        // 模拟 Host 端收到后的二次解析（payload 是 Any?→LinkedTreeMap）
        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals(MessageType.DRAW_COMMAND, parsed.type)
        val payload = gson.fromJson(gson.toJson(parsed.payload), DrawPayload::class.java)
        assertEquals("x", payload.id)
        assertEquals(DrawOp.TAP, payload.op)
        assertEquals(DrawTool.RIPPLE, payload.tool)
        assertEquals(0.1f, payload.x, 0.0001f)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "*DrawPayloadSerializationTest"`
Expected: 编译失败（`DrawPayload` / `DrawOp` / `DrawTool` / `MessageType.DRAW_COMMAND` 未定义）。

- [ ] **Step 3: 实现协议类型**

在 `data/SignalingMessage.kt`，在 `GuestEventPayload`（第 37-39 行）之后、`object MessageType` 之前插入：

```kotlin
/**
 * 画笔标注指令载荷。坐标均为归一化 [0,1]，相对"共享画面内容"。
 * 与 Go 服务端透传字段保持蛇形命名（guest_id）。
 */
data class DrawPayload(
    @SerializedName("id") val id: String = "",
    @SerializedName("op") val op: String = "",       // 见 DrawOp
    @SerializedName("tool") val tool: String = "",   // 见 DrawTool
    @SerializedName("x") val x: Float = 0f,           // 当前点/圆心/起点
    @SerializedName("y") val y: Float = 0f,
    @SerializedName("x2") val x2: Float = 0f,         // 箭头终点/圈边缘点（可选）
    @SerializedName("y2") val y2: Float = 0f,
    @SerializedName("color") val color: String = "#FF3B30",
    @SerializedName("guest_id") val guestId: String = "",
    @SerializedName("ts") val ts: Long = 0L,
)

/** 画笔操作 */
object DrawOp {
    const val BEGIN = "begin"   // 一笔/一个图形开始
    const val POINT = "point"   // 拖动中的中间点（追加/更新）
    const val END = "end"       // 结束（开始淡出计时）
    const val TAP = "tap"       // 单击（波纹）
    const val CLEAR = "clear"   // 清空全部
}

/** 画笔工具 */
object DrawTool {
    const val LASER = "laser"    // 实时指针，不留痕
    const val PEN = "pen"        // 自由画笔
    const val CIRCLE = "circle"  // 圆圈/圈选
    const val ARROW = "arrow"    // 箭头
    const val RIPPLE = "ripple"  // 点击波纹
}
```

在 `object MessageType { ... }` 中 `KICK_GUEST` 一行之后新增：

```kotlin
    const val DRAW_COMMAND = "draw_command"
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "*DrawPayloadSerializationTest"`
Expected: PASS（2 tests）。

- [ ] **Step 5: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/data/SignalingMessage.kt easyscreen-android/app/src/test/java/to/feng/app/easyscreen/data/DrawPayloadSerializationTest.kt
git commit -m "feat(android): 新增画笔标注消息协议 DrawPayload"
```

---

## Task 2: 坐标映射纯函数 + 测试

归一化坐标的双向换算，纯函数，无 Android 依赖，完整 TDD。

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/CoordinateMapping.kt`
- Create: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/annotation/CoordinateMappingTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/to/feng/app/easyscreen/annotation/CoordinateMappingTest.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateMappingTest {

    // 舞台与源同比例（无黑边）：触点中心 → (0.5,0.5)
    @Test
    fun touchToNormalized_sameAspect_center() {
        val r = CoordinateMapping.touchToNormalized(
            touchX = 540f, touchY = 1170f,
            stageW = 1080f, stageH = 2340f,
            srcW = 1080, srcH = 2340, fillCover = false,
        )!!
        assertEquals(0.5f, r.first, 0.001f)
        assertEquals(0.5f, r.second, 0.001f)
    }

    // contain 模式：源更"宽"，上下留黑边。触到黑边区域 → null
    @Test
    fun touchToNormalized_containLetterbox_outsideContentReturnsNull() {
        // 源 1000x500（2:1），舞台 1000x1000 → 内容高 500，上下各 250 黑边
        val top = CoordinateMapping.touchToNormalized(
            touchX = 500f, touchY = 100f,   // 落在上黑边
            stageW = 1000f, stageH = 1000f,
            srcW = 1000, srcH = 500, fillCover = false,
        )
        assertNull(top)
    }

    @Test
    fun touchToNormalized_containLetterbox_insideMapsCorrectly() {
        // 内容区 y ∈ [250,750]，触 y=500 → 归一化 0.5
        val r = CoordinateMapping.touchToNormalized(
            touchX = 500f, touchY = 500f,
            stageW = 1000f, stageH = 1000f,
            srcW = 1000, srcH = 500, fillCover = false,
        )!!
        assertEquals(0.5f, r.first, 0.001f)
        assertEquals(0.5f, r.second, 0.001f)
    }

    // cover 模式：内容铺满并裁切，触点不会返回 null，越界被 clamp 到 [0,1]
    @Test
    fun touchToNormalized_cover_clampsToUnitRange() {
        val r = CoordinateMapping.touchToNormalized(
            touchX = 0f, touchY = 0f,
            stageW = 1000f, stageH = 1000f,
            srcW = 1000, srcH = 500, fillCover = true,
        )!!
        assert(r.first in 0f..1f)
        assert(r.second in 0f..1f)
    }

    @Test
    fun normalizedToPixel_mapsToFullView() {
        val p = CoordinateMapping.normalizedToPixel(0.5f, 0.25f, viewW = 1080, viewH = 2400)
        assertEquals(540f, p.first, 0.001f)
        assertEquals(600f, p.second, 0.001f)
    }

    @Test
    fun touchToNormalized_invalidSource_returnsNull() {
        assertNull(
            CoordinateMapping.touchToNormalized(10f, 10f, 100f, 100f, 0, 0, false)
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "*CoordinateMappingTest"`
Expected: 编译失败（`CoordinateMapping` 未定义）。

- [ ] **Step 3: 实现 CoordinateMapping**

创建 `app/src/main/java/to/feng/app/easyscreen/annotation/CoordinateMapping.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

/**
 * 归一化坐标换算（纯函数，无 Android 依赖）。
 *
 * 归一化坐标 [0,1] 相对"共享画面内容"本身——与 WebRTC 实际编码分辨率无关。
 */
object CoordinateMapping {

    /**
     * Guest 端：把舞台（视频容器）内的触点换算为归一化 [0,1]。
     *
     * @param fillCover false=contain（留黑边，越界返回 null）；true=cover（裁切，clamp 到 [0,1]）
     * @return (nx, ny)；contain 模式下触到黑边或源无效时返回 null。
     */
    fun touchToNormalized(
        touchX: Float, touchY: Float,
        stageW: Float, stageH: Float,
        srcW: Int, srcH: Int,
        fillCover: Boolean,
    ): Pair<Float, Float>? {
        if (srcW <= 0 || srcH <= 0 || stageW <= 0f || stageH <= 0f) return null

        val scale = if (fillCover) {
            maxOf(stageW / srcW, stageH / srcH)
        } else {
            minOf(stageW / srcW, stageH / srcH)
        }
        val contentW = srcW * scale
        val contentH = srcH * scale
        val offsetX = (stageW - contentW) / 2f
        val offsetY = (stageH - contentH) / 2f

        val nx = (touchX - offsetX) / contentW
        val ny = (touchY - offsetY) / contentH

        if (!fillCover) {
            // contain：黑边外不接受
            if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
            return nx to ny
        }
        // cover：clamp 到合法范围
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    /** Host 端：归一化 [0,1] → 浮窗像素坐标（浮窗全屏覆盖）。 */
    fun normalizedToPixel(nx: Float, ny: Float, viewW: Int, viewH: Int): Pair<Float, Float> {
        return (nx * viewW) to (ny * viewH)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "*CoordinateMappingTest"`
Expected: PASS（7 tests）。

- [ ] **Step 5: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/CoordinateMapping.kt easyscreen-android/app/src/test/java/to/feng/app/easyscreen/annotation/CoordinateMappingTest.kt
git commit -m "feat(android): 新增标注归一化坐标映射纯函数"
```

---

## Task 3: AnnotationStore 状态机 + 过期淡出 + 测试

标注状态机：应用 `DrawPayload` 操作、按时间淡出/过期、输出可渲染快照。纯 JVM（时间作为参数注入），完整 TDD。

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationModels.kt`
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationStore.kt`
- Create: `easyscreen-android/app/src/test/java/to/feng/app/easyscreen/annotation/AnnotationStoreTest.kt`

- [ ] **Step 1: 创建渲染中间模型（无测试，纯数据）**

创建 `app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationModels.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

/** 归一化点 [0,1]（不依赖 android.graphics，便于纯 JVM 测试）。 */
data class NPoint(val x: Float, val y: Float)

/**
 * 可渲染的标注快照。
 * @param points 归一化点：pen=路径；circle=[圆心,边缘]；arrow=[起,止]；ripple/laser=[当前点]
 * @param alpha 当前透明度（淡出用）
 * @param ageMs 自结束起的存活毫秒（ripple 动画/laser 拖尾用；未结束则为 0）
 */
data class RenderAnnotation(
    val id: String,
    val tool: String,
    val color: String,
    val points: List<NPoint>,
    val alpha: Float,
    val ageMs: Long,
)
```

- [ ] **Step 2: 写失败测试**

创建 `app/src/test/java/to/feng/app/easyscreen/annotation/AnnotationStoreTest.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStoreTest {

    private fun circleBegin(id: String, t: Long) = DrawPayload(
        id = id, op = DrawOp.BEGIN, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f, ts = t,
    )

    @Test
    fun activeAnnotation_isFullyVisible() {
        val store = AnnotationStore(holdMs = 5000, fadeMs = 800)
        store.apply(circleBegin("c1", 0), nowMs = 0)
        val snap = store.snapshot(nowMs = 100)
        assertEquals(1, snap.size)
        assertEquals(1f, snap[0].alpha, 0.001f)
    }

    @Test
    fun penAppendsPoints() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "p", op = DrawOp.BEGIN, tool = DrawTool.PEN, x = 0.1f, y = 0.1f), 0)
        store.apply(DrawPayload(id = "p", op = DrawOp.POINT, tool = DrawTool.PEN, x = 0.2f, y = 0.2f), 10)
        store.apply(DrawPayload(id = "p", op = DrawOp.POINT, tool = DrawTool.PEN, x = 0.3f, y = 0.3f), 20)
        val snap = store.snapshot(30)
        assertEquals(3, snap[0].points.size)
    }

    @Test
    fun finishedAnnotation_holdsThenFadesThenExpires() {
        val store = AnnotationStore(holdMs = 1000, fadeMs = 500)
        store.apply(DrawPayload(id = "c", op = DrawOp.BEGIN, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f), 0)
        store.apply(DrawPayload(id = "c", op = DrawOp.END, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f, x2 = 0.6f, y2 = 0.5f), 0)

        // hold 期内：全显
        assertEquals(1f, store.snapshot(500)[0].alpha, 0.001f)
        // 进入淡出中段（hold=1000，fade=500，t=1250 → 半透明）
        assertEquals(0.5f, store.snapshot(1250)[0].alpha, 0.05f)
        // 超过 hold+fade=1500 → 移除
        assertTrue(store.snapshot(1600).isEmpty())
    }

    @Test
    fun circleEnd_storesCenterAndEdge() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "c", op = DrawOp.BEGIN, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f), 0)
        store.apply(DrawPayload(id = "c", op = DrawOp.END, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f, x2 = 0.7f, y2 = 0.5f), 0)
        val pts = store.snapshot(10)[0].points
        assertEquals(2, pts.size)
        assertEquals(0.5f, pts[0].x, 0.001f)   // 圆心
        assertEquals(0.7f, pts[1].x, 0.001f)   // 边缘
    }

    @Test
    fun rippleTap_isFinishedImmediately() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "r", op = DrawOp.TAP, tool = DrawTool.RIPPLE, x = 0.3f, y = 0.4f), 0)
        val snap = store.snapshot(50)
        assertEquals(1, snap.size)
        assertEquals(1, snap[0].points.size)
        assertTrue(snap[0].ageMs >= 0)
    }

    @Test
    fun ripple_expiresAfterShortAnimation() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "r", op = DrawOp.TAP, tool = DrawTool.RIPPLE, x = 0.3f, y = 0.4f), 0)
        assertTrue(store.snapshot(1000).isEmpty())  // 波纹动画 ~600ms 后消失
    }

    @Test
    fun clear_removesEverything() {
        val store = AnnotationStore()
        store.apply(circleBegin("c1", 0), 0)
        store.apply(circleBegin("c2", 0), 0)
        store.apply(DrawPayload(op = DrawOp.CLEAR), 5)
        assertTrue(store.snapshot(10).isEmpty())
    }

    @Test
    fun laserEnd_fadesQuickly() {
        val store = AnnotationStore(holdMs = 5000, fadeMs = 800)
        store.apply(DrawPayload(id = "l", op = DrawOp.BEGIN, tool = DrawTool.LASER, x = 0.5f, y = 0.5f), 0)
        store.apply(DrawPayload(id = "l", op = DrawOp.END, tool = DrawTool.LASER, x = 0.5f, y = 0.5f), 0)
        // laser 不吃 holdMs，结束后约 400ms 内消失
        assertTrue(store.snapshot(500).isEmpty())
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "*AnnotationStoreTest"`
Expected: 编译失败（`AnnotationStore` 未定义）。

- [ ] **Step 4: 实现 AnnotationStore**

创建 `app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationStore.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool

/**
 * 标注状态机：应用 DrawPayload，按时间淡出/过期，输出渲染快照。
 *
 * 纯逻辑——时间由调用方传入（nowMs），便于单测，也便于渲染循环驱动。
 * 线程约束：apply / snapshot 应在同一线程（渲染线程）调用。
 *
 * @param holdMs 留痕类标注结束后的保持时长
 * @param fadeMs 保持后的淡出时长
 */
class AnnotationStore(
    private val holdMs: Long = 5000L,
    private val fadeMs: Long = 800L,
) {
    private companion object {
        const val LASER_FADE_MS = 400L   // 指针松手后快速消失
        const val RIPPLE_LIFE_MS = 600L  // 波纹动画总时长
    }

    private class Item(
        val id: String,
        val tool: String,
        val color: String,
        val points: MutableList<NPoint>,
        var finishedAt: Long?,   // null=仍在绘制
    )

    private val items = LinkedHashMap<String, Item>()

    fun apply(p: DrawPayload, nowMs: Long) {
        when (p.op) {
            DrawOp.CLEAR -> items.clear()

            DrawOp.TAP -> {
                // 波纹：单点，立即结束
                items[p.id.ifEmpty { "tap-$nowMs" }] = Item(
                    id = p.id, tool = p.tool, color = p.color,
                    points = mutableListOf(NPoint(p.x, p.y)),
                    finishedAt = nowMs,
                )
            }

            DrawOp.BEGIN -> {
                items[p.id] = Item(
                    id = p.id, tool = p.tool, color = p.color,
                    points = mutableListOf(NPoint(p.x, p.y)),
                    finishedAt = null,
                )
            }

            DrawOp.POINT -> {
                val it = items[p.id] ?: return
                when (p.tool) {
                    DrawTool.PEN -> it.points.add(NPoint(p.x, p.y))
                    DrawTool.LASER -> {
                        // 指针只保留当前点
                        it.points.clear()
                        it.points.add(NPoint(p.x, p.y))
                    }
                    DrawTool.CIRCLE, DrawTool.ARROW -> {
                        // 第二点（边缘/终点）随拖动更新
                        if (it.points.size < 2) it.points.add(NPoint(p.x, p.y))
                        else it.points[1] = NPoint(p.x, p.y)
                    }
                }
            }

            DrawOp.END -> {
                val it = items[p.id] ?: return
                if (p.tool == DrawTool.CIRCLE || p.tool == DrawTool.ARROW) {
                    val edge = NPoint(p.x2, p.y2).takeIf { p.x2 != 0f || p.y2 != 0f }
                        ?: NPoint(p.x, p.y)
                    if (it.points.size < 2) it.points.add(edge) else it.points[1] = edge
                }
                it.finishedAt = nowMs
            }
        }
    }

    fun clear() = items.clear()

    fun isEmpty(): Boolean = items.isEmpty()

    /** 返回当前可见标注，并就地剔除已过期项。 */
    fun snapshot(nowMs: Long): List<RenderAnnotation> {
        val out = ArrayList<RenderAnnotation>(items.size)
        val expired = ArrayList<String>()
        for ((key, it) in items) {
            val finishedAt = it.finishedAt
            if (finishedAt == null) {
                out.add(RenderAnnotation(it.id, it.tool, it.color, it.points.toList(), 1f, 0L))
                continue
            }
            val age = nowMs - finishedAt
            val (life, alpha) = lifeAndAlpha(it.tool, age)
            if (age >= life) { expired.add(key); continue }
            out.add(RenderAnnotation(it.id, it.tool, it.color, it.points.toList(), alpha, age))
        }
        for (k in expired) items.remove(k)
        return out
    }

    /** 返回 (总寿命, 当前 alpha)；age>=总寿命表示过期。 */
    private fun lifeAndAlpha(tool: String, age: Long): Pair<Long, Float> {
        return when (tool) {
            DrawTool.LASER -> LASER_FADE_MS to (1f - age.toFloat() / LASER_FADE_MS).coerceIn(0f, 1f)
            DrawTool.RIPPLE -> RIPPLE_LIFE_MS to (1f - age.toFloat() / RIPPLE_LIFE_MS).coerceIn(0f, 1f)
            else -> {
                val total = holdMs + fadeMs
                val alpha = if (age <= holdMs) 1f
                else (1f - (age - holdMs).toFloat() / fadeMs).coerceIn(0f, 1f)
                total to alpha
            }
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest --tests "*AnnotationStoreTest"`
Expected: PASS（8 tests）。

- [ ] **Step 6: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationModels.kt easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationStore.kt easyscreen-android/app/src/test/java/to/feng/app/easyscreen/annotation/AnnotationStoreTest.kt
git commit -m "feat(android): 新增标注状态机 AnnotationStore（淡出/过期）"
```

---

## Task 4: Go 服务端转发 draw_command

最小改动：Guest→Host 转发已支持，只加常量与 switch 分支。

**Files:**
- Modify: `easyscreen-signaling/main.go`

- [ ] **Step 1: 新增消息类型常量**

在 `main.go` 第 55 行 `TypeKickGuest = "kick_guest" ...` 之后新增一行：

```go
	TypeDrawCommand    = "draw_command"    // Guest → 服 → Host：画笔标注指令
```

- [ ] **Step 2: 在 handleMessage switch 中加分支**

在 `main.go` `handleMessage` 的 `case TypeKickGuest:`（第 257-258 行）之后、`default:` 之前插入：

```go
	case TypeDrawCommand:
		c.forwardMessage(msg, TypeDrawCommand)
```

（复用既有 `forwardMessage`：guest 角色会自动附 `guest_id` 并转发给 `room.Host`。）

- [ ] **Step 3: 编译验证**

Run: `cd easyscreen-signaling && go build ./...`
Expected: 无输出（编译成功）。

- [ ] **Step 4: go vet 验证**

Run: `cd easyscreen-signaling && go vet ./...`
Expected: 无报错。

- [ ] **Step 5: 提交**

```bash
git add easyscreen-signaling/main.go
git commit -m "feat(signaling): 转发 draw_command 标注指令（Guest→Host）"
```

---

## Task 5: Host 端浮窗渲染 View（原生 Canvas）

老人端系统浮窗里的绘制 View。无法纯 JVM 单测（依赖 Canvas），用编译 + 后续端到端手测验证。

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationOverlayView.kt`

- [ ] **Step 1: 实现 AnnotationOverlayView**

创建 `app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationOverlayView.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.View
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 老人端系统浮窗的绘制 View。维护 AnnotationStore，按动画帧重绘并淡出。
 * 触摸穿透由 WindowManager 的 FLAG_NOT_TOUCHABLE 保证，本 View 不处理触摸。
 */
@SuppressLint("ViewConstructor")
class AnnotationOverlayView(context: Context) : View(context) {

    private val store = AnnotationStore()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    /** 由浮窗管理器在收到 draw_command 时调用。 */
    fun submit(payload: DrawPayload) {
        store.apply(payload, SystemClock.uptimeMillis())
        scheduleFrame()
    }

    fun clearAll() {
        store.clear()
        invalidate()
    }

    private fun scheduleFrame() {
        // 在动画帧上重绘；onDraw 里若仍有内容会继续 re-post
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val annotations = store.snapshot(now)
        for (a in annotations) {
            val color = parseColor(a.color)
            when (a.tool) {
                DrawTool.PEN, DrawTool.LASER -> drawPenOrLaser(canvas, a, color)
                DrawTool.CIRCLE -> drawCircle(canvas, a, color)
                DrawTool.ARROW -> drawArrow(canvas, a, color)
                DrawTool.RIPPLE -> drawRipple(canvas, a, color)
            }
        }
        if (annotations.isNotEmpty()) {
            // 仍有内容 → 继续下一帧（驱动淡出/波纹动画）
            postInvalidateOnAnimation()
        }
    }

    private fun px(nx: Float, ny: Float): Pair<Float, Float> =
        CoordinateMapping.normalizedToPixel(nx, ny, width, height)

    private fun drawPenOrLaser(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        if (a.tool == DrawTool.LASER) {
            // 指针：一个实心光点 + 半透明光晕
            val (cx, cy) = px(a.points.last().x, a.points.last().y)
            fillPaint.color = withAlpha(color, a.alpha * 0.25f)
            canvas.drawCircle(cx, cy, dp(22f), fillPaint)
            fillPaint.color = withAlpha(color, a.alpha)
            canvas.drawCircle(cx, cy, dp(9f), fillPaint)
            return
        }
        // 自由画笔：折线
        path.reset()
        val first = px(a.points[0].x, a.points[0].y)
        path.moveTo(first.first, first.second)
        for (i in 1 until a.points.size) {
            val (x, y) = px(a.points[i].x, a.points[i].y)
            path.lineTo(x, y)
        }
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(5f)
        canvas.drawPath(path, strokePaint)
    }

    private fun drawCircle(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        val (cx, cy) = px(a.points[0].x, a.points[0].y)
        val r = if (a.points.size >= 2) {
            val (ex, ey) = px(a.points[1].x, a.points[1].y)
            hypot((ex - cx).toDouble(), (ey - cy).toDouble()).toFloat()
        } else dp(60f)
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(5f)
        canvas.drawCircle(cx, cy, r.coerceAtLeast(dp(8f)), strokePaint)
    }

    private fun drawArrow(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.size < 2) return
        val (sx, sy) = px(a.points[0].x, a.points[0].y)
        val (ex, ey) = px(a.points[1].x, a.points[1].y)
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(5f)
        canvas.drawLine(sx, sy, ex, ey, strokePaint)
        // 箭头头部
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val head = dp(22f)
        val spread = Math.toRadians(28.0)
        for (s in intArrayOf(-1, 1)) {
            val a2 = angle + s * spread
            canvas.drawLine(
                ex, ey,
                ex - (head * cos(a2)).toFloat(),
                ey - (head * sin(a2)).toFloat(),
                strokePaint,
            )
        }
    }

    private fun drawRipple(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        val (cx, cy) = px(a.points[0].x, a.points[0].y)
        // ageMs 0→600 驱动半径扩张、透明度衰减
        val t = (a.ageMs.toFloat() / 600f).coerceIn(0f, 1f)
        val r = dp(12f) + dp(48f) * t
        strokePaint.color = withAlpha(color, (1f - t))
        strokePaint.strokeWidth = dp(4f)
        canvas.drawCircle(cx, cy, r, strokePaint)
        // 中心实心点
        fillPaint.color = withAlpha(color, (1f - t))
        canvas.drawCircle(cx, cy, dp(8f), fillPaint)
    }

    private fun parseColor(s: String): Int = try {
        Color.parseColor(s)
    } catch (e: Exception) {
        Log.w("AnnotationOverlay", "bad color $s")
        Color.RED
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
```

- [ ] **Step 2: 编译验证**

Run: `cd easyscreen-android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationOverlayView.kt
git commit -m "feat(android): 老人端浮窗绘制 View（圆/箭头/画笔/波纹/指针）"
```

---

## Task 6: Host 端浮窗管理器（WindowManager 单例）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationOverlayManager.kt`

- [ ] **Step 1: 实现 AnnotationOverlayManager**

创建 `app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationOverlayManager.kt`：

```kotlin
package to.feng.app.easyscreen.annotation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload

/**
 * 老人端系统浮窗管理器（单例）。
 *
 * 关键：浮窗用 TYPE_APPLICATION_OVERLAY 覆盖任意 App，FLAG_NOT_TOUCHABLE 触摸穿透，
 * 老人照常操作浮窗下方的真实按钮。所有方法须在主线程调用。
 */
object AnnotationOverlayManager {
    private const val TAG = "AnnotationOverlay"

    private var windowManager: WindowManager? = null
    private var view: AnnotationOverlayView? = null
    private var added = false

    /** 是否已授予系统浮窗权限。 */
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 收到一条标注指令：懒加载浮窗并提交。无权限则忽略（由 UI 负责提示授权）。 */
    fun submit(context: Context, payload: DrawPayload) {
        val appCtx = context.applicationContext
        if (!hasPermission(appCtx)) {
            Log.w(TAG, "无浮窗权限，丢弃 draw_command")
            return
        }
        // clear 指令即使浮窗未加载也无副作用
        ensureAdded(appCtx)
        if (payload.op == DrawOp.CLEAR) {
            view?.clearAll()
        } else {
            view?.submit(payload)
        }
    }

    private fun ensureAdded(appCtx: Context) {
        if (added && view != null) return
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val v = AnnotationOverlayView(appCtx)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 不获焦 + 不可触（穿透）+ 全屏覆盖（含状态栏/挖孔区）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(v, params)
            windowManager = wm
            view = v
            added = true
            Log.d(TAG, "浮窗已添加")
        } catch (e: Exception) {
            Log.e(TAG, "addView 失败", e)
        }
    }

    /** 停止共享/退出时移除浮窗。 */
    fun hide() {
        val wm = windowManager
        val v = view
        if (added && wm != null && v != null) {
            try { wm.removeView(v) } catch (e: Exception) { Log.w(TAG, "removeView 失败", e) }
        }
        view = null
        added = false
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd easyscreen-android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/annotation/AnnotationOverlayManager.kt
git commit -m "feat(android): 老人端系统浮窗管理器（触摸穿透、懒加载）"
```

---

## Task 7: Host 集成——HostViewModel 接收指令 + HostScreen 权限入口

**Files:**
- Modify: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/HostScreen.kt`

- [ ] **Step 1: HostViewModel 处理 DRAW_COMMAND**

在 `HostScreen.kt` 的 `handleSignalingMessage` 中，`MessageType.DISCONNECT` 分支（第 292-296 行）之前插入新分支：

```kotlin
            MessageType.DRAW_COMMAND -> {
                val payload = try {
                    gson.fromJson(gson.toJson(message.payload), DrawPayload::class.java)
                } catch (e: Exception) { return }
                // 浮窗只在共享进行中有意义
                if (_isSharing.value) {
                    to.feng.app.easyscreen.annotation.AnnotationOverlayManager.submit(appContext, payload)
                }
            }
```

- [ ] **Step 2: 停止共享时移除浮窗**

在 `HostViewModel.cleanupResources()`（第 392-395 行）中，`ScreenCaptureService.stop(appContext)` 之后追加一行：

```kotlin
        to.feng.app.easyscreen.annotation.AnnotationOverlayManager.hide()
```

- [ ] **Step 3: 在 HostScreen 顶部加浮窗权限提示卡（仅未授权时显示）**

在 `HostScreen` 可组合中，找到中间可滚动 `Column`（第 545-552 行起）。在其内部、`if (!guestConnected) {`（第 555 行）之前插入权限提示卡：

```kotlin
        // 浮窗权限提示：未授权时显示一键跳转入口（子女语音指导老人点这里）
        val ctxForOverlay = LocalContext.current
        var overlayGranted by remember {
            mutableStateOf(android.provider.Settings.canDrawOverlays(ctxForOverlay))
        }
        // 从系统设置页返回时复检
        val lifecycleForOverlay = LocalLifecycleOwner.current
        DisposableEffect(lifecycleForOverlay) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayGranted = android.provider.Settings.canDrawOverlays(ctxForOverlay)
                }
            }
            lifecycleForOverlay.lifecycle.addObserver(obs)
            onDispose { lifecycleForOverlay.lifecycle.removeObserver(obs) }
        }
        if (!overlayGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = WaitingAmber.copy(alpha = 0.15f)
                ),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "开启「悬浮窗」后，对方就能在你的屏幕上画圈指导你操作",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctxForOverlay.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctxForOverlay.startActivity(intent) } catch (_: Exception) {}
                    }) { Text("去开启悬浮窗") }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
```

> `Intent`、`LocalContext`、`LocalLifecycleOwner`、`Lifecycle`、`LifecycleEventObserver`、`DisposableEffect`、`WaitingAmber` 均已在该文件 import（第 3、16、17、22-23 行及 `import androidx.compose.runtime.*`），无需新增 import。

- [ ] **Step 4: 编译验证**

Run: `cd easyscreen-android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/HostScreen.kt
git commit -m "feat(android): Host 接收 draw_command 驱动浮窗 + 权限入口"
```

---

## Task 8: Guest 端标注层（Compose Canvas + 手势 + 工具条）

**Files:**
- Create: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/annotation/AnnotationLayer.kt`

- [ ] **Step 1: 实现 AnnotationLayer**

创建 `app/src/main/java/to/feng/app/easyscreen/ui/annotation/AnnotationLayer.kt`：

```kotlin
package to.feng.app.easyscreen.ui.annotation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import to.feng.app.easyscreen.annotation.AnnotationStore
import to.feng.app.easyscreen.annotation.CoordinateMapping
import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 子女端可选颜色 */
private val PALETTE = listOf("#FF3B30", "#FFCC00", "#34C759", "#0A84FF")

/**
 * 子女端标注层：盖在视频上，捕获触摸→归一化→onSend 发送 + 本地回显。
 *
 * @param srcW/srcH 远端源画面有效尺寸（来自 WebRTCManager.SourceSize.effectiveSize()）
 * @param fillCover renderer 是否 cover（充满裁切）模式
 * @param onSend 发送一条 DrawPayload（→ signalingClient）
 * @param onExit 退出标注模式
 */
@Composable
fun AnnotationLayer(
    srcW: Int,
    srcH: Int,
    fillCover: Boolean,
    onSend: (DrawPayload) -> Unit,
    onExit: () -> Unit,
) {
    var tool by remember { mutableStateOf(DrawTool.CIRCLE) }
    var colorHex by remember { mutableStateOf(PALETTE[0]) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val store = remember { AnnotationStore() }
    // 触发重组以驱动本地回显动画
    var frameTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            frameTick = SystemClock.uptimeMillis()
            kotlinx.coroutines.delay(33)
        }
    }

    fun norm(o: Offset): Pair<Float, Float>? = CoordinateMapping.touchToNormalized(
        o.x, o.y, stageSize.width.toFloat(), stageSize.height.toFloat(), srcW, srcH, fillCover,
    )

    fun send(op: String, t: String, nx: Float, ny: Float, id: String, nx2: Float = 0f, ny2: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val p = DrawPayload(
            id = id, op = op, tool = t, x = nx, y = ny, x2 = nx2, y2 = ny2,
            color = colorHex, ts = now,
        )
        store.apply(p, now)   // 本地回显
        onSend(p)             // 发往老人端
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { stageSize = it }
            // 根据工具选择手势：ripple 用 tap，其余用 drag
            .pointerInput(tool, colorHex, srcW, srcH, fillCover) {
                if (tool == DrawTool.RIPPLE) {
                    detectTapGestures(onTap = { o ->
                        norm(o)?.let { (nx, ny) ->
                            send(DrawOp.TAP, DrawTool.RIPPLE, nx, ny, UUID.randomUUID().toString().take(8))
                        }
                    })
                } else {
                    var id = ""
                    var lastSent = 0L
                    var startN: Pair<Float, Float>? = null
                    detectDragGestures(
                        onDragStart = { o ->
                            val n = norm(o) ?: return@detectDragGestures
                            id = UUID.randomUUID().toString().take(8)
                            startN = n
                            send(DrawOp.BEGIN, tool, n.first, n.second, id)
                        },
                        onDrag = { change, _ ->
                            if (id.isEmpty()) return@detectDragGestures
                            val n = norm(change.position) ?: return@detectDragGestures
                            val now = SystemClock.uptimeMillis()
                            if (now - lastSent >= 60) {    // 节流 ~60ms
                                lastSent = now
                                send(DrawOp.POINT, tool, n.first, n.second, id)
                            }
                        },
                        onDragEnd = {
                            if (id.isEmpty()) return@detectDragGestures
                            // 对 circle/arrow，end 用 x2/y2 携带终点；这里复用 store 已有的最后点
                            send(DrawOp.END, tool, startN?.first ?: 0f, startN?.second ?: 0f, id)
                            id = ""
                        },
                    )
                }
            },
    ) {
        // 本地回显画布
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frameTick   // 订阅帧，触发重绘
            val now = SystemClock.uptimeMillis()
            for (a in store.snapshot(now)) {
                val col = parseComposeColor(a.color).copy(alpha = a.alpha)
                fun px(nx: Float, ny: Float) = Offset(nx * size.width, ny * size.height)
                when (a.tool) {
                    DrawTool.PEN, DrawTool.LASER -> {
                        if (a.tool == DrawTool.LASER) {
                            a.points.lastOrNull()?.let {
                                drawCircle(col.copy(alpha = a.alpha * 0.25f), 22.dp.toPx(), px(it.x, it.y))
                                drawCircle(col, 9.dp.toPx(), px(it.x, it.y))
                            }
                        } else if (a.points.size >= 2) {
                            for (i in 1 until a.points.size) {
                                drawLine(col, px(a.points[i-1].x, a.points[i-1].y),
                                    px(a.points[i].x, a.points[i].y),
                                    strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                            }
                        }
                    }
                    DrawTool.CIRCLE -> if (a.points.isNotEmpty()) {
                        val c = px(a.points[0].x, a.points[0].y)
                        val r = if (a.points.size >= 2) {
                            val e = px(a.points[1].x, a.points[1].y)
                            hypot((e.x - c.x).toDouble(), (e.y - c.y).toDouble()).toFloat()
                        } else 60.dp.toPx()
                        drawCircle(col, r.coerceAtLeast(8.dp.toPx()), c, style = Stroke(width = 5.dp.toPx()))
                    }
                    DrawTool.ARROW -> if (a.points.size >= 2) {
                        val s = px(a.points[0].x, a.points[0].y)
                        val e = px(a.points[1].x, a.points[1].y)
                        drawLine(col, s, e, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                        val ang = atan2((e.y - s.y).toDouble(), (e.x - s.x).toDouble())
                        val head = 22.dp.toPx(); val spread = Math.toRadians(28.0)
                        for (sgn in intArrayOf(-1, 1)) {
                            val a2 = ang + sgn * spread
                            drawLine(col, e, Offset(e.x - (head*cos(a2)).toFloat(), e.y - (head*sin(a2)).toFloat()),
                                strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    DrawTool.RIPPLE -> if (a.points.isNotEmpty()) {
                        val c = px(a.points[0].x, a.points[0].y)
                        val t = (a.ageMs.toFloat() / 600f).coerceIn(0f, 1f)
                        drawCircle(col.copy(alpha = 1f - t), 12.dp.toPx() + 48.dp.toPx() * t, c,
                            style = Stroke(width = 4.dp.toPx()))
                        drawCircle(col.copy(alpha = 1f - t), 8.dp.toPx(), c)
                    }
                }
            }
        }

        // 顶部工具条
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("○", tool == DrawTool.CIRCLE) { tool = DrawTool.CIRCLE }
            ToolButton("→", tool == DrawTool.ARROW) { tool = DrawTool.ARROW }
            ToolButton("✎", tool == DrawTool.PEN) { tool = DrawTool.PEN }
            ToolButton("·", tool == DrawTool.RIPPLE) { tool = DrawTool.RIPPLE }
            ToolButton("◉", tool == DrawTool.LASER) { tool = DrawTool.LASER }
            Spacer(Modifier.width(8.dp))
            for (hex in PALETTE) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(parseComposeColor(hex))
                        .border(
                            width = if (hex == colorHex) 3.dp else 0.dp,
                            color = Color.White, shape = CircleShape,
                        )
                        .clickable { colorHex = hex },
                )
            }
            Spacer(Modifier.width(8.dp))
            ToolButton("清空", false) {
                store.clear()
                onSend(DrawPayload(op = DrawOp.CLEAR, ts = SystemClock.uptimeMillis()))
            }
            ToolButton("✕", false) { onExit() }
        }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White.copy(alpha = 0.35f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White)
    }
}

private fun parseComposeColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color.Red
}
```

> 注：`circle/arrow` 的终点在拖动过程中通过 `POINT` 持续更新 `store` 的第二点，老人端同样靠 `POINT` 更新——`END` 时无需再带 `x2/y2`（已由最后一次 `POINT` 落位）。`AnnotationStore.apply` 的 `END` 分支对 `x2/y2` 为 0 时回退用 `x/y`，与此一致。

- [ ] **Step 2: 编译验证**

Run: `cd easyscreen-android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若报 `windowInsetsPadding`/`statusBarsIgnoringVisibility` 未解析，在文件顶部加 `@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)`。

- [ ] **Step 3: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/annotation/AnnotationLayer.kt
git commit -m "feat(android): 子女端标注层（工具条/手势/本地回显）"
```

---

## Task 9: Guest 集成——标注模式开关 + 锁定缩放 + 发送

**Files:**
- Modify: `easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/GuestScreen.kt`

- [ ] **Step 1: GuestViewModel 暴露 sendDraw**

在 `GuestViewModel` 中，`fun joinRoom()` 之前（第 183 行附近）新增方法：

```kotlin
    /** 发送一条画笔标注指令到老人端（经服务端转发）。 */
    fun sendDraw(payload: DrawPayload) {
        signalingClient.send(SignalingMessage(type = MessageType.DRAW_COMMAND, payload = payload))
    }
```

- [ ] **Step 2: FullscreenVideoView 增加标注模式参数**

修改 `FullscreenVideoView` 的签名（第 466-472 行），新增 `onSendDraw` 参数：

```kotlin
@Composable
private fun FullscreenVideoView(
    remoteViewReady: Boolean,
    statusMessage: String,
    createRenderer: () -> org.webrtc.SurfaceViewRenderer?,
    sourceSize: WebRTCManager.SourceSize,
    onLeaveToInput: () -> Unit,
    onSendDraw: (DrawPayload) -> Unit,
) {
```

并在调用处（第 455-461 行）补上实参：

```kotlin
        FullscreenVideoView(
            remoteViewReady = remoteViewReady,
            statusMessage = statusMessage,
            createRenderer = { viewModel.createRemoteView() },
            sourceSize = sourceSize,
            onLeaveToInput = { viewModel.leaveSession() },
            onSendDraw = { viewModel.sendDraw(it) },
        )
```

- [ ] **Step 3: 在 FullscreenVideoView 内加标注模式状态并锁定缩放**

在 `FullscreenVideoView` 内、`var scale by remember ...`（第 484 行）附近新增：

```kotlin
    var annotationMode by remember { mutableStateOf(false) }
    // 进入标注模式时锁定缩放/平移，保证坐标映射简单可靠
    LaunchedEffect(annotationMode) {
        if (annotationMode) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }
    }
```

- [ ] **Step 4: 在外层 Box 的手势上禁用标注模式下的缩放/平移**

把外层 Box 的两个 `.pointerInput(Unit) { detectTransformGestures... }` 与 `.pointerInput(Unit) { detectTapGestures... }`（第 664-710 行）各自改为 `key` 包含 `annotationMode` 并在标注模式下早退。将 `.pointerInput(Unit) {` 改为 `.pointerInput(annotationMode) {`，并在两个 lambda 体首行加：

```kotlin
                if (annotationMode) return@pointerInput
```

（transform 与 tap 两处都加。）

- [ ] **Step 5: 渲染标注层 + 入口按钮**

在 `FullscreenVideoView` 最外层 `Box { ... }` 内部、右下角缩放百分比块（第 800-832 行）之后、Box 闭合 `}` 之前插入：

```kotlin
        // 标注层（仅标注模式显示，盖在视频之上）
        if (annotationMode && remoteViewReady) {
            val eff = sourceSize.effectiveSize()
            to.feng.app.easyscreen.ui.annotation.AnnotationLayer(
                srcW = eff.first,
                srcH = eff.second,
                fillCover = fillMode,
                onSend = onSendDraw,
                onExit = { annotationMode = false },
            )
        }

        // 左下：进入标注模式按钮（仅系统栏可见时显示，避免误触）
        if (!annotationMode) {
            androidx.compose.material3.IconButton(
                onClick = { annotationMode = true },
                enabled = systemBarsVisible && remoteViewReady,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 12.dp)
                    .size(44.dp)
                    .alpha(controlsAlpha)
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            ) {
                Text("✎", color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.titleLarge)
            }
        }
```

> `fillMode` 在 `FullscreenVideoView` 第 477 行已定义（`true`=cover/充满）。`DrawPayload` 通过 `import to.feng.app.easyscreen.data.*`（第 39 行）已可用。

- [ ] **Step 6: 编译验证**

Run: `cd easyscreen-android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 全量单测回归**

Run: `cd easyscreen-android && ./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS。

- [ ] **Step 8: Debug APK 打包验证**

Run: `cd easyscreen-android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/*.apk`。

- [ ] **Step 9: 提交**

```bash
git add easyscreen-android/app/src/main/java/to/feng/app/easyscreen/ui/screens/GuestScreen.kt
git commit -m "feat(android): 子女端标注模式开关+锁定缩放+发送标注"
```

---

## Task 10: 端到端手动验证

无法自动化的真机联调，逐项确认。需两台 Android 真机（或一真机 + 一模拟器）连同一信令服务。

**前置：** 启动信令服务（`cd easyscreen-signaling && go run .`），两端 `local.properties` 指向该服务地址，各自安装 debug APK。

- [ ] **Step 1: 权限引导**
老人端开始共享，HostScreen 顶部出现"去开启悬浮窗"卡片 → 点击跳转系统设置 → 授权 → 返回后卡片消失。

- [ ] **Step 2: 圆圈标注**
子女端连接观看 → 点左下 ✎ 进入标注模式 → 选○ → 在某按钮上拖一个圈。预期：子女端本地立即显示圈；老人端真实屏幕浮现同位置圈；约 5 秒后淡出。

- [ ] **Step 3: 触摸穿透**
老人端在圈出现期间，点击圈内的真实按钮（如微信某入口）。预期：按钮正常响应，浮窗不拦截触摸。

- [ ] **Step 4: 跨 App 覆盖**
老人切到微信/桌面，子女继续画圈。预期：标注浮在微信/桌面之上正常显示。

- [ ] **Step 5: 箭头 / 自由画笔 / 波纹 / 指针**
依次切换工具验证：箭头起止方向正确；自由画笔连续成线；波纹点击冒脉冲后消失；指针（◉）拖动时老人端光点跟手移动、松手消失不留痕。

- [ ] **Step 6: 一键清空**
画多个标注后点"清空"。预期：两端立即清空全部。

- [ ] **Step 7: 坐标对齐（含旋转）**
子女在画面四角与中心分别标注，核对老人端落点一致。老人横竖屏切换后重复一次，确认坐标随方向正确映射。

- [ ] **Step 8: 停止共享清理**
老人停止共享。预期：浮窗移除，无残留标注悬浮。

- [ ] **Step 9: 记录结果**
将每项 PASS/FAIL 记入 PR 描述；任一 FAIL 回到对应 Task 修复后重测。

---

## Self-Review（计划编写者已核对）

**1. 规格覆盖：**
- 子女画→老人浮窗显示 → Task 7（Host 浮窗）+ Task 8/9（Guest 发送）✅
- 系统浮窗 TYPE_APPLICATION_OVERLAY + 触摸穿透 → Task 6（FLAG_NOT_TOUCHABLE）✅
- 归一化坐标双向映射 → Task 2 ✅
- 五种工具（圆/箭头/画笔/波纹/指针）→ Task 5（Host 渲染）+ Task 8（Guest 工具条）✅
- 自动淡出 + 手动清空 → Task 3（淡出逻辑）+ Task 8（清空按钮）✅
- 标注模式开关、防误触 → Task 9 ✅
- 权限仅语音 + 跳转入口 → Task 7 权限卡 ✅
- 多观众按 guestId 上色 → 服务端转发已带 guest_id（Task 4）；颜色由发送方 color 字段决定（已覆盖基础；按 guest 自动分配不同色为增强项，未列入，符合 YAGNI）✅
- Go 转发 → Task 4 ✅
- 横竖屏旋转必测 → Task 10 Step 7 ✅

**2. 占位符扫描：** 无 TBD/TODO，所有代码步骤含完整代码。

**3. 类型一致性：** `DrawPayload`/`DrawOp`/`DrawTool`/`MessageType.DRAW_COMMAND`（Task 1）→ `AnnotationStore`（Task 3）→ `AnnotationOverlayView.submit`（Task 5）/`AnnotationOverlayManager.submit`（Task 6）/`AnnotationLayer`（Task 8）全程一致；`NPoint`/`RenderAnnotation` 字段统一；`CoordinateMapping.touchToNormalized/normalizedToPixel` 签名跨 Task 2/5/8 一致；`fillCover`（纯函数）对应 Guest 的 `fillMode`（true=cover）已在 Task 9 对接。

**已知 v1 取舍（设计文档"非目标"已列）：** 标注模式下锁定缩放为 1x；本地回显与视频回传轻微重影；不按 guest 自动分配颜色；不做声音/震动提醒。
