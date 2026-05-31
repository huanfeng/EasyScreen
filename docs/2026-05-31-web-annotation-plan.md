# Web 端画笔标注 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 让 Web 观看端（子女，浏览器）也能在老人屏幕画面上画圈点标注，标注经现有 WebSocket 以 `draw_command` 发送，由老人端 Android 系统浮窗渲染——与 Android Guest 功能对等。

**Architecture:** 镜像 Android 端。老人端浮窗渲染 + Go 服务端转发**完全复用、零改动**。只动 `web/` 四个文件：新增 `annotation.js`（JS 版坐标映射 + 标注状态机 + 叠加 canvas 渲染 + 手势 + 工具条），并在 `index.html`/`style.css`/`app.js` 接入。进入标注模式时锁定缩放，规避双层坐标（object-fit + CSS transform）的复杂度。

**Tech Stack:** 原生 JS（无框架）、Canvas 2D、Pointer Events、现有 WebSocket 信令。逻辑用 Node v22 断言脚本测试，`node --check` 验证语法。

**父功能设计/计划：** `docs/2026-05-31-screen-annotation-overlay-design.md` / `-plan.md`

**关键约定（与 Android 逐字对齐）：**
- 消息类型 `draw_command`；载荷字段 `{id, op, tool, x, y, x2, y2, color, guest_id, ts}`（蛇形）。
- 归一化坐标 `[0,1]`，相对"共享画面内容"。
- 工具：`laser/pen/circle/arrow/ripple`；操作：`begin/point/end/tap/clear`。
- 颜色：`#FF3B30`(默认)/`#FFCC00`/`#34C759`/`#0A84FF`。
- 淡出：laser 松手 ~400ms 消失；ripple ~600ms 动画；其余 hold 5000ms + fade 800ms。
- **已修复的圆/箭头塌缩 bug 在 JS 版一开始就规避**：`onDragEnd` 带最后位置作 `x2/y2`，store 的 END 不回退覆盖。

---

## 文件结构总览

- **新建** `web/annotation.js` — `CoordinateMapping` + `AnnotationStore` + `DrawOp/DrawTool` + `init(opts)` 控制器，导出为 `window.AnnotationOverlay`（浏览器）/ `module.exports`（node 测试）。
- **新建** `web/annotation.test.js` — node 断言测试（坐标 + 状态机 + 塌缩回归）。
- **改** `web/index.html` — 在 `#video-view` 内加叠加 canvas、工具条、入口按钮，并引入 `annotation.js`。
- **改** `web/style.css` — 上述元素样式（沿用 `.floating-action`/CSS 变量）。
- **改** `web/app.js` — `MSG.DRAW_COMMAND`；`AnnotationOverlay.init({...})`；标注模式禁用 wheel/双击缩放 + 锁定缩放。

### `AnnotationOverlay.init(opts)` 契约（贯穿全计划）
```
AnnotationOverlay.init({
  stageEl,       // #video-view（getBoundingClientRect → 舞台坐标/尺寸）
  videoEl,       // #remote-video（videoWidth/videoHeight → 源尺寸）
  canvasEl,      // #annotation-canvas（叠加层：本地回显 + 标注模式下捕获指针）
  toolbarEl,     // #annotation-toolbar
  entryBtnEl,    // #annotate-btn（✎ 入口）
  getFitCover,   // () => boolean，true=cover（充满裁剪）
  getGuestId,    // () => string
  send,          // (payloadObj) => void，发 draw_command 的 payload
  onModeChange,  // (active:boolean) => void，app.js 用来锁缩放/禁手势
})
```

---

## Task W1: JS 坐标映射 + 状态机核心 + Node 测试

**Files:**
- Create: `easyscreen-signaling/web/annotation.js`（先只放逻辑核心 + 导出）
- Create: `easyscreen-signaling/web/annotation.test.js`

- [ ] **Step 1: 写失败的 node 测试**

创建 `easyscreen-signaling/web/annotation.test.js`：

```js
'use strict';
const assert = require('assert');
const { CoordinateMapping, AnnotationStore, DrawOp, DrawTool } = require('./annotation.js');

// ---- CoordinateMapping ----
{
  const r = CoordinateMapping.touchToNormalized(540, 1170, 1080, 2340, 1080, 2340, false);
  assert(r && Math.abs(r[0] - 0.5) < 1e-3 && Math.abs(r[1] - 0.5) < 1e-3, 'sameAspect center');
}
{
  // 源 1000x500（2:1），舞台 1000x1000 → 上下黑边，触上黑边返回 null
  const top = CoordinateMapping.touchToNormalized(500, 100, 1000, 1000, 1000, 500, false);
  assert(top === null, 'contain letterbox outside → null');
}
{
  const r = CoordinateMapping.touchToNormalized(500, 500, 1000, 1000, 1000, 500, false);
  assert(r && Math.abs(r[1] - 0.5) < 1e-3, 'contain inside maps');
}
{
  const r = CoordinateMapping.touchToNormalized(0, 0, 1000, 1000, 1000, 500, true);
  assert(r && r[0] >= 0 && r[0] <= 1 && r[1] >= 0 && r[1] <= 1, 'cover clamps');
}
{
  const p = CoordinateMapping.normalizedToPixel(0.5, 0.25, 1080, 2400);
  assert(Math.abs(p[0] - 540) < 1e-3 && Math.abs(p[1] - 600) < 1e-3, 'normalizedToPixel');
}
assert(CoordinateMapping.touchToNormalized(10, 10, 100, 100, 0, 0, false) === null, 'invalid src → null');

// ---- AnnotationStore ----
{
  const s = new AnnotationStore(5000, 800);
  s.apply({ id: 'c1', op: DrawOp.BEGIN, tool: DrawTool.CIRCLE, x: 0.5, y: 0.5 }, 0);
  const snap = s.snapshot(100);
  assert(snap.length === 1 && Math.abs(snap[0].alpha - 1) < 1e-3, 'active fully visible');
}
{
  const s = new AnnotationStore();
  s.apply({ id: 'p', op: DrawOp.BEGIN, tool: DrawTool.PEN, x: 0.1, y: 0.1 }, 0);
  s.apply({ id: 'p', op: DrawOp.POINT, tool: DrawTool.PEN, x: 0.2, y: 0.2 }, 10);
  s.apply({ id: 'p', op: DrawOp.POINT, tool: DrawTool.PEN, x: 0.3, y: 0.3 }, 20);
  assert(s.snapshot(30)[0].points.length === 3, 'pen appends');
}
{
  const s = new AnnotationStore(1000, 500);
  s.apply({ id: 'c', op: DrawOp.BEGIN, tool: DrawTool.CIRCLE, x: 0.5, y: 0.5 }, 0);
  s.apply({ id: 'c', op: DrawOp.END, tool: DrawTool.CIRCLE, x: 0.5, y: 0.5, x2: 0.6, y2: 0.5 }, 0);
  assert(Math.abs(s.snapshot(500)[0].alpha - 1) < 1e-3, 'hold full');
  assert(Math.abs(s.snapshot(1250)[0].alpha - 0.5) < 0.05, 'fade midpoint');
  assert(s.snapshot(1600).length === 0, 'expired');
}
{
  // 塌缩回归：END 不带 x2/y2 时，必须保留最后一次 POINT 落点
  const s = new AnnotationStore();
  s.apply({ id: 'c', op: DrawOp.BEGIN, tool: DrawTool.CIRCLE, x: 0.5, y: 0.5 }, 0);
  s.apply({ id: 'c', op: DrawOp.POINT, tool: DrawTool.CIRCLE, x: 0.7, y: 0.5 }, 5);
  s.apply({ id: 'c', op: DrawOp.END, tool: DrawTool.CIRCLE, x: 0.5, y: 0.5 }, 10);
  const pts = s.snapshot(20)[0].points;
  assert(pts.length === 2 && Math.abs(pts[1][0] - 0.7) < 1e-3, 'circle END without x2 preserves edge');
}
{
  const s = new AnnotationStore();
  s.apply({ id: 'r', op: DrawOp.TAP, tool: DrawTool.RIPPLE, x: 0.3, y: 0.4 }, 0);
  assert(s.snapshot(50).length === 1, 'ripple tap visible');
  assert(s.snapshot(1000).length === 0, 'ripple expires');
}
{
  const s = new AnnotationStore();
  s.apply({ id: 'a', op: DrawOp.BEGIN, tool: DrawTool.CIRCLE, x: 0.1, y: 0.1 }, 0);
  s.apply({ op: DrawOp.CLEAR }, 5);
  assert(s.snapshot(10).length === 0, 'clear removes all');
}
{
  const s = new AnnotationStore(5000, 800);
  s.apply({ id: 'l', op: DrawOp.BEGIN, tool: DrawTool.LASER, x: 0.5, y: 0.5 }, 0);
  s.apply({ id: 'l', op: DrawOp.END, tool: DrawTool.LASER, x: 0.5, y: 0.5 }, 0);
  assert(s.snapshot(500).length === 0, 'laser fades quickly');
}

console.log('✓ all web annotation logic tests passed');
```

- [ ] **Step 2: 运行确认失败**

Run: `cd easyscreen-signaling/web && node annotation.test.js`
Expected: 失败（`Cannot find module './annotation.js'` 或类似）。

- [ ] **Step 3: 实现逻辑核心**

创建 `easyscreen-signaling/web/annotation.js`：

```js
// EasyScreen Web 画笔标注模块
// 逻辑核心（CoordinateMapping / AnnotationStore）与 Android 端 annotation/* 逐字对齐。
// 同时支持浏览器（window.AnnotationOverlay）与 Node（module.exports，仅逻辑测试）。
(function (root) {
  'use strict';

  function clamp01(v) { return Math.min(1, Math.max(0, v)); }

  // 画笔操作 / 工具常量
  const DrawOp = { BEGIN: 'begin', POINT: 'point', END: 'end', TAP: 'tap', CLEAR: 'clear' };
  const DrawTool = { LASER: 'laser', PEN: 'pen', CIRCLE: 'circle', ARROW: 'arrow', RIPPLE: 'ripple' };

  // 归一化坐标换算（纯函数）
  const CoordinateMapping = {
    // 返回 [nx, ny]；contain 模式触到黑边或源无效返回 null
    touchToNormalized: function (touchX, touchY, stageW, stageH, srcW, srcH, fillCover) {
      if (srcW <= 0 || srcH <= 0 || stageW <= 0 || stageH <= 0) return null;
      const scale = fillCover
        ? Math.max(stageW / srcW, stageH / srcH)
        : Math.min(stageW / srcW, stageH / srcH);
      const contentW = srcW * scale, contentH = srcH * scale;
      const offsetX = (stageW - contentW) / 2, offsetY = (stageH - contentH) / 2;
      const nx = (touchX - offsetX) / contentW, ny = (touchY - offsetY) / contentH;
      if (!fillCover) {
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return [nx, ny];
      }
      return [clamp01(nx), clamp01(ny)];
    },
    normalizedToPixel: function (nx, ny, viewW, viewH) { return [nx * viewW, ny * viewH]; },
  };

  // 标注状态机：apply 操作 + 按时间淡出/过期 + snapshot 输出
  // points 为归一化点数组：pen=路径；circle=[圆心,边缘]；arrow=[起,止]；ripple/laser=[当前点]
  class AnnotationStore {
    constructor(holdMs = 5000, fadeMs = 800) {
      this.holdMs = holdMs; this.fadeMs = fadeMs; this.items = new Map();
      this._LASER_FADE = 400; this._RIPPLE_LIFE = 600;
    }
    apply(p, nowMs) {
      switch (p.op) {
        case DrawOp.CLEAR:
          this.items.clear();
          break;
        case DrawOp.TAP:
          this.items.set(p.id || ('tap-' + nowMs), {
            id: p.id || '', tool: p.tool, color: p.color,
            points: [[p.x, p.y]], finishedAt: nowMs,
          });
          break;
        case DrawOp.BEGIN:
          this.items.set(p.id, {
            id: p.id, tool: p.tool, color: p.color,
            points: [[p.x, p.y]], finishedAt: null,
          });
          break;
        case DrawOp.POINT: {
          const it = this.items.get(p.id); if (!it) break;
          if (p.tool === DrawTool.PEN) it.points.push([p.x, p.y]);
          else if (p.tool === DrawTool.LASER) it.points = [[p.x, p.y]];
          else if (p.tool === DrawTool.CIRCLE || p.tool === DrawTool.ARROW) {
            if (it.points.length < 2) it.points.push([p.x, p.y]);
            else it.points[1] = [p.x, p.y];
          }
          break;
        }
        case DrawOp.END: {
          const it = this.items.get(p.id); if (!it) break;
          if (p.tool === DrawTool.CIRCLE || p.tool === DrawTool.ARROW) {
            if ((p.x2 || 0) !== 0 || (p.y2 || 0) !== 0) {
              const edge = [p.x2, p.y2];
              if (it.points.length < 2) it.points.push(edge); else it.points[1] = edge;
            } else if (it.points.length < 2) {
              it.points.push([p.x, p.y]);
            }
            // 否则保留拖动最后一次 POINT 落点，不塌回起点
          }
          it.finishedAt = nowMs;
          break;
        }
      }
    }
    clear() { this.items.clear(); }
    isEmpty() { return this.items.size === 0; }
    snapshot(nowMs) {
      const out = []; const expired = [];
      for (const [key, it] of this.items) {
        if (it.finishedAt == null) {
          out.push({ id: it.id, tool: it.tool, color: it.color, points: it.points.slice(), alpha: 1, ageMs: 0 });
          continue;
        }
        const age = nowMs - it.finishedAt;
        const la = this._lifeAndAlpha(it.tool, age);
        if (age >= la[0]) { expired.push(key); continue; }
        out.push({ id: it.id, tool: it.tool, color: it.color, points: it.points.slice(), alpha: la[1], ageMs: age });
      }
      for (const k of expired) this.items.delete(k);
      return out;
    }
    _lifeAndAlpha(tool, age) {
      if (tool === DrawTool.LASER) return [this._LASER_FADE, clamp01(1 - age / this._LASER_FADE)];
      if (tool === DrawTool.RIPPLE) return [this._RIPPLE_LIFE, clamp01(1 - age / this._RIPPLE_LIFE)];
      const total = this.holdMs + this.fadeMs;
      const alpha = age <= this.holdMs ? 1 : clamp01(1 - (age - this.holdMs) / this.fadeMs);
      return [total, alpha];
    }
  }

  // init 控制器在 Task W3 实现；此处先占位以保证导出形状稳定
  function init(opts) {
    if (root && root.console) root.console.warn('[annotation] init not yet implemented');
  }

  const api = { CoordinateMapping, AnnotationStore, DrawOp, DrawTool, init };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.AnnotationOverlay = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
```

- [ ] **Step 4: 运行确认通过**

Run: `cd easyscreen-signaling/web && node annotation.test.js`
Expected: `✓ all web annotation logic tests passed`，退出码 0。

- [ ] **Step 5: 语法检查**

Run: `cd easyscreen-signaling/web && node --check annotation.js`
Expected: 无输出（语法 OK）。

- [ ] **Step 6: 提交**

```bash
git add easyscreen-signaling/web/annotation.js easyscreen-signaling/web/annotation.test.js
git commit -m "feat(web): 新增标注逻辑核心（坐标映射+状态机）与 node 测试"
```

---

## Task W2: DOM + 样式（叠加 canvas / 工具条 / 入口按钮）

**Files:**
- Modify: `easyscreen-signaling/web/index.html`
- Modify: `easyscreen-signaling/web/style.css`

- [ ] **Step 1: index.html 加元素**

在 `index.html` 的 `#video-view` 内，`<div id="overlay" ...>` 之前插入叠加 canvas：

```html
      <!-- 标注叠加层：平时 pointer-events:none，标注模式下捕获指针 -->
      <canvas id="annotation-canvas" class="annotation-canvas"></canvas>
```

在 `#video-view` 内、`<!-- 右上角操作按钮组 -->` 之前，插入入口按钮和工具条：

```html
      <!-- 进入标注模式入口（左下，zoom-bar 上方） -->
      <button id="annotate-btn" class="floating-action floating-annotate" aria-label="标注">✎</button>

      <!-- 标注工具条（顶部居中，默认隐藏） -->
      <div id="annotation-toolbar" class="annotation-toolbar hidden">
        <button class="anno-tool" data-tool="circle" title="圆圈">○</button>
        <button class="anno-tool" data-tool="arrow" title="箭头">→</button>
        <button class="anno-tool" data-tool="pen" title="自由画笔">✎</button>
        <button class="anno-tool" data-tool="ripple" title="点击波纹">·</button>
        <button class="anno-tool" data-tool="laser" title="实时指针">◉</button>
        <span class="anno-sep"></span>
        <button class="anno-color" data-color="#FF3B30" style="background:#FF3B30"></button>
        <button class="anno-color" data-color="#FFCC00" style="background:#FFCC00"></button>
        <button class="anno-color" data-color="#34C759" style="background:#34C759"></button>
        <button class="anno-color" data-color="#0A84FF" style="background:#0A84FF"></button>
        <span class="anno-sep"></span>
        <button id="anno-clear" class="anno-text-btn" title="清空">清空</button>
        <button id="anno-exit" class="anno-text-btn" title="退出标注">✕</button>
      </div>
```

并在文件底部把 `annotation.js` 放在 `app.js` **之前**引入（app.js 需要 `window.AnnotationOverlay`）。把：
```html
  <script src="/app.js"></script>
```
改为：
```html
  <script src="/annotation.js"></script>
  <script src="/app.js"></script>
```

- [ ] **Step 2: style.css 加样式**

在 `style.css` 末尾追加：

```css
/* ===== 画笔标注 ===== */
.annotation-canvas {
  position: absolute;
  inset: 0;
  z-index: 15;            /* 视频之上、浮动按钮之下 */
  pointer-events: none;   /* 默认穿透；标注模式由 JS 置为 auto */
  touch-action: none;
}
.floating-annotate {
  left: calc(env(safe-area-inset-left, 0px) + 12px);
  bottom: calc(env(safe-area-inset-bottom, 0px) + 60px);
  top: auto;
  font-size: 20px;
}
.floating-annotate:hover { background: rgba(96, 165, 250, 0.75); }

.annotation-toolbar {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 12px);
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border-radius: 24px;
  background: rgba(15, 23, 42, 0.7);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  z-index: 22;            /* 工具条须在叠加 canvas 之上，可点 */
}
.anno-tool, .anno-text-btn {
  min-width: 34px; height: 34px;
  border: none; border-radius: 16px;
  background: transparent; color: #fff;
  font-size: 18px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  padding: 0 8px;
}
.anno-text-btn { font-size: 14px; }
.anno-tool.selected { background: rgba(255, 255, 255, 0.35); }
.anno-color {
  width: 24px; height: 24px; margin: 0 2px;
  border-radius: 50%; border: 2px solid transparent; cursor: pointer; padding: 0;
}
.anno-color.selected { border-color: #fff; }
.anno-sep { width: 1px; height: 22px; background: rgba(255,255,255,0.25); margin: 0 4px; }
```

- [ ] **Step 3: 校验 app.js 仍可加载（引入顺序未破坏）**

Run: `cd easyscreen-signaling/web && node --check app.js`
Expected: 无输出（语法 OK；本步只确认没误改 app.js）。

> HTML/CSS 无法 node 校验，结构正确性在 Task W5 浏览器冒烟测试确认。

- [ ] **Step 4: 提交**

```bash
git add easyscreen-signaling/web/index.html easyscreen-signaling/web/style.css
git commit -m "feat(web): 标注叠加 canvas、工具条与入口按钮的 DOM 与样式"
```

---

## Task W3: annotation.js 控制器 `init()`（canvas 渲染 + 手势 + 工具条）

**Files:**
- Modify: `easyscreen-signaling/web/annotation.js`（替换 Task W1 的 `init` 占位实现）

- [ ] **Step 1: 实现 init 控制器**

把 `annotation.js` 中的占位 `function init(opts) { ... }` 整段替换为以下实现（其余逻辑核心保持不变）：

```js
  // 标注模式 UI 控制器：叠加 canvas 渲染 + 指针手势 + 工具条
  function init(opts) {
    const stageEl = opts.stageEl, videoEl = opts.videoEl, canvasEl = opts.canvasEl;
    const toolbarEl = opts.toolbarEl, entryBtnEl = opts.entryBtnEl;
    const getFitCover = opts.getFitCover, getGuestId = opts.getGuestId;
    const send = opts.send, onModeChange = opts.onModeChange;
    if (!stageEl || !videoEl || !canvasEl || !toolbarEl || !entryBtnEl) return;

    const ctx = canvasEl.getContext('2d');
    const store = new AnnotationStore();
    let active = false;
    let tool = DrawTool.CIRCLE;
    let colorHex = '#FF3B30';
    let dpr = window.devicePixelRatio || 1;
    let rafId = 0;

    // ---- canvas 尺寸跟随舞台 ----
    function resizeCanvas() {
      const rect = stageEl.getBoundingClientRect();
      dpr = window.devicePixelRatio || 1;
      canvasEl.width = Math.round(rect.width * dpr);
      canvasEl.height = Math.round(rect.height * dpr);
      canvasEl.style.width = rect.width + 'px';
      canvasEl.style.height = rect.height + 'px';
    }

    // ---- 指针 → 归一化 [0,1] ----
    function norm(clientX, clientY) {
      const rect = stageEl.getBoundingClientRect();
      const x = clientX - rect.left, y = clientY - rect.top;
      return CoordinateMapping.touchToNormalized(
        x, y, rect.width, rect.height,
        videoEl.videoWidth || 0, videoEl.videoHeight || 0, !!getFitCover(),
      );
    }

    function uid() { return Math.random().toString(36).slice(2, 10); }

    function emit(op, t, nx, ny, id, nx2, ny2) {
      const p = {
        id: id, op: op, tool: t, x: nx, y: ny,
        x2: nx2 || 0, y2: ny2 || 0, color: colorHex,
        guest_id: getGuestId ? getGuestId() : '', ts: Date.now(),
      };
      store.apply(p, performance.now());  // 本地回显
      send(p);                            // 发往老人端
    }

    // ---- 手势 ----
    const drag = { id: '', startN: null, lastN: null, lastSent: 0 };

    function onDown(e) {
      if (!active) return;
      canvasEl.setPointerCapture(e.pointerId);
      const n = norm(e.clientX, e.clientY);
      if (tool === DrawTool.RIPPLE) {
        if (n) emit(DrawOp.TAP, DrawTool.RIPPLE, n[0], n[1], uid());
        return;
      }
      if (!n) return;
      drag.id = uid(); drag.startN = n; drag.lastN = n; drag.lastSent = 0;
      emit(DrawOp.BEGIN, tool, n[0], n[1], drag.id);
    }
    function onMove(e) {
      if (!active || !drag.id || tool === DrawTool.RIPPLE) return;
      const n = norm(e.clientX, e.clientY);
      if (!n) return;
      drag.lastN = n;
      const now = performance.now();
      if (now - drag.lastSent >= 60) {
        drag.lastSent = now;
        emit(DrawOp.POINT, tool, n[0], n[1], drag.id);
      }
    }
    function onUp() {
      if (!active || !drag.id || tool === DrawTool.RIPPLE) return;
      const s = drag.startN || [0, 0], l = drag.lastN || s;
      emit(DrawOp.END, tool, s[0], s[1], drag.id, l[0], l[1]);
      drag.id = '';
    }

    canvasEl.addEventListener('pointerdown', onDown);
    canvasEl.addEventListener('pointermove', onMove);
    canvasEl.addEventListener('pointerup', onUp);
    canvasEl.addEventListener('pointercancel', onUp);

    // ---- 渲染循环 ----
    function px(n, rect) { return [n[0] * rect.width, n[1] * rect.height]; }
    function draw() {
      const rect = stageEl.getBoundingClientRect();
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, rect.width, rect.height);
      const snap = store.snapshot(performance.now());
      for (let i = 0; i < snap.length; i++) drawOne(snap[i], rect);
      rafId = requestAnimationFrame(draw);
    }
    function rgba(hex, a) {
      const h = hex.replace('#', '');
      const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
      return `rgba(${r},${g},${b},${Math.max(0, Math.min(1, a))})`;
    }
    function drawOne(a, rect) {
      const pts = a.points;
      if (!pts.length) return;
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      if (a.tool === DrawTool.LASER) {
        const c = px(pts[pts.length - 1], rect);
        ctx.fillStyle = rgba(a.color, a.alpha * 0.25);
        ctx.beginPath(); ctx.arc(c[0], c[1], 22, 0, 7); ctx.fill();
        ctx.fillStyle = rgba(a.color, a.alpha);
        ctx.beginPath(); ctx.arc(c[0], c[1], 9, 0, 7); ctx.fill();
      } else if (a.tool === DrawTool.PEN) {
        ctx.strokeStyle = rgba(a.color, a.alpha); ctx.lineWidth = 5;
        ctx.beginPath();
        const p0 = px(pts[0], rect); ctx.moveTo(p0[0], p0[1]);
        for (let i = 1; i < pts.length; i++) { const q = px(pts[i], rect); ctx.lineTo(q[0], q[1]); }
        ctx.stroke();
      } else if (a.tool === DrawTool.CIRCLE) {
        const c = px(pts[0], rect);
        let r = 60;
        if (pts.length >= 2) { const e = px(pts[1], rect); r = Math.hypot(e[0] - c[0], e[1] - c[1]); }
        ctx.strokeStyle = rgba(a.color, a.alpha); ctx.lineWidth = 5;
        ctx.beginPath(); ctx.arc(c[0], c[1], Math.max(8, r), 0, 7); ctx.stroke();
      } else if (a.tool === DrawTool.ARROW) {
        if (pts.length < 2) return;
        const s = px(pts[0], rect), e = px(pts[1], rect);
        ctx.strokeStyle = rgba(a.color, a.alpha); ctx.lineWidth = 5;
        ctx.beginPath(); ctx.moveTo(s[0], s[1]); ctx.lineTo(e[0], e[1]); ctx.stroke();
        const ang = Math.atan2(e[1] - s[1], e[0] - s[0]);
        const head = 22, spread = 28 * Math.PI / 180;
        for (const sgn of [-1, 1]) {
          const a2 = ang + sgn * spread;
          ctx.beginPath(); ctx.moveTo(e[0], e[1]);
          ctx.lineTo(e[0] - head * Math.cos(a2), e[1] - head * Math.sin(a2)); ctx.stroke();
        }
      } else if (a.tool === DrawTool.RIPPLE) {
        const c = px(pts[0], rect);
        const t = Math.max(0, Math.min(1, a.ageMs / 600));
        ctx.strokeStyle = rgba(a.color, 1 - t); ctx.lineWidth = 4;
        ctx.beginPath(); ctx.arc(c[0], c[1], 12 + 48 * t, 0, 7); ctx.stroke();
        ctx.fillStyle = rgba(a.color, 1 - t);
        ctx.beginPath(); ctx.arc(c[0], c[1], 8, 0, 7); ctx.fill();
      }
    }

    // ---- 工具条 ----
    const toolBtns = toolbarEl.querySelectorAll('.anno-tool');
    const colorBtns = toolbarEl.querySelectorAll('.anno-color');
    function refreshToolbarUI() {
      toolBtns.forEach((b) => b.classList.toggle('selected', b.getAttribute('data-tool') === tool));
      colorBtns.forEach((b) => b.classList.toggle('selected', b.getAttribute('data-color') === colorHex));
    }
    toolBtns.forEach((b) => b.addEventListener('click', () => { tool = b.getAttribute('data-tool'); refreshToolbarUI(); }));
    colorBtns.forEach((b) => b.addEventListener('click', () => { colorHex = b.getAttribute('data-color'); refreshToolbarUI(); }));
    const clearBtn = toolbarEl.querySelector('#anno-clear');
    const exitBtn = toolbarEl.querySelector('#anno-exit');
    if (clearBtn) clearBtn.addEventListener('click', () => {
      store.clear();
      send({ id: '', op: DrawOp.CLEAR, tool: '', x: 0, y: 0, x2: 0, y2: 0, color: colorHex, guest_id: getGuestId ? getGuestId() : '', ts: Date.now() });
    });
    if (exitBtn) exitBtn.addEventListener('click', () => setActive(false));

    // ---- 模式开关 ----
    function setActive(on) {
      if (active === on) return;
      active = on;
      canvasEl.style.pointerEvents = on ? 'auto' : 'none';
      toolbarEl.classList.toggle('hidden', !on);
      entryBtnEl.classList.toggle('hidden', on);
      if (on) { resizeCanvas(); refreshToolbarUI(); }
      else { drag.id = ''; }
      if (onModeChange) onModeChange(on);
    }
    entryBtnEl.addEventListener('click', () => setActive(true));
    window.addEventListener('resize', () => { if (active) resizeCanvas(); });

    resizeCanvas();
    rafId = requestAnimationFrame(draw);
    refreshToolbarUI();
  }
```

- [ ] **Step 2: 语法检查**

Run: `cd easyscreen-signaling/web && node --check annotation.js`
Expected: 无输出。

- [ ] **Step 3: 逻辑回归（确保未破坏核心）**

Run: `cd easyscreen-signaling/web && node annotation.test.js`
Expected: `✓ all web annotation logic tests passed`。

- [ ] **Step 4: 提交**

```bash
git add easyscreen-signaling/web/annotation.js
git commit -m "feat(web): 标注控制器（叠加 canvas 渲染、手势、工具条）"
```

---

## Task W4: app.js 接入

**Files:**
- Modify: `easyscreen-signaling/web/app.js`

- [ ] **Step 1: MSG 加常量**

在 `app.js` 的 `const MSG = { ... }`，`PONG: 'pong',` 之后加一行：
```js
    DRAW_COMMAND: 'draw_command',
```

- [ ] **Step 2: 加 DOM 引用**

在 DOM 引用区（`const videoCanvas = $('video-canvas');` 附近）新增：
```js
  const annotationCanvas = $('annotation-canvas');
  const annotationToolbar = $('annotation-toolbar');
  const annotateBtn = $('annotate-btn');
```

- [ ] **Step 3: 加标注模式状态并在手势处理早退**

在状态区（`let myGuestId = '';` 附近）新增：
```js
  let annotationActive = false;
```

在 wheel 缩放处理（`videoStage.addEventListener('wheel', (e) => {`）函数体首行 `if (videoStage.classList.contains('hidden')) return;` 之后加：
```js
    if (annotationActive) return;
```

在双击缩放处理（`videoStage.addEventListener('click', (e) => {`）函数体首行加：
```js
    if (annotationActive) return;
```

在 `videoCanvas` 的 `pointerdown` 与 `pointermove` 两个监听器函数体首行各加：
```js
    if (annotationActive) return;
```

- [ ] **Step 4: 初始化标注模块**

在 `app.js` 末尾、`resetView();` 调用（IIFE 结尾处）之前，加入初始化：
```js
  // ---------- 画笔标注 ----------
  if (window.AnnotationOverlay && annotationCanvas && annotationToolbar && annotateBtn) {
    window.AnnotationOverlay.init({
      stageEl: videoStage,
      videoEl: video,
      canvasEl: annotationCanvas,
      toolbarEl: annotationToolbar,
      entryBtnEl: annotateBtn,
      getFitCover: () => fitMode === 'cover',
      getGuestId: () => myGuestId,
      send: (payload) => send({ type: MSG.DRAW_COMMAND, payload: payload }),
      onModeChange: (on) => {
        annotationActive = on;
        if (on) resetView();   // 锁定缩放：复位到 100% 再画
      },
    });
  }
```

> `video`、`videoStage`、`fitMode`、`myGuestId`、`send`、`resetView` 均为 app.js 内已存在的引用/函数。`annotateBtn` 仅在视频阶段可见（CSS 定位在视频层内），输入阶段整个 `#video-view` 隐藏，故无需额外控制其显隐。

- [ ] **Step 5: 语法检查**

Run: `cd easyscreen-signaling/web && node --check app.js`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add easyscreen-signaling/web/app.js
git commit -m "feat(web): app.js 接入标注（发送 draw_command + 标注模式锁缩放）"
```

---

## Task W5: 冒烟测试 + 端到端验证

- [ ] **Step 1: 启动信令服务**

Run（后台）: `cd easyscreen-signaling && go run .`（监听 :8081，托管 web 静态资源）。

- [ ] **Step 2: 浏览器冒烟（页面加载无 JS 错误）**

用浏览器（或 Playwright）打开 `http://127.0.0.1:8081/`：
- 确认输入页正常渲染，控制台无报错（尤其 `annotation.js` 解析、`AnnotationOverlay.init` 不抛错）。
- 因为未连接真实会话，视频页不出现，`✎` 入口与工具条此时不可见属正常。
- 在控制台执行 `typeof window.AnnotationOverlay.init === 'function'` 应为 `true`，`window.AnnotationOverlay.CoordinateMapping.normalizedToPixel(0.5,0.5,100,100)` 应返回 `[50,50]`。

- [ ] **Step 3: 端到端（需老人端 Android 在共享）**

老人端 Android 共享屏幕并已授权悬浮窗；浏览器输入连接码进入视频页：
1. 点左下 `✎` → 工具条出现，缩放被复位锁定。
2. 选 ○ 在某按钮上拖圈 → 浏览器本地立即显示圈、**松手不塌缩**；老人端真实屏幕同位置浮现圈，约 5 秒淡出。
3. 依次验证箭头/自由画笔/波纹/指针/清空，行为与 Android 端一致。
4. 切 contain/cover（▣）后再画，核对老人端落点仍准确。
5. 退出标注（✕）→ 缩放/平移恢复可用。

- [ ] **Step 4: 记录结果**

每项 PASS/FAIL 记入 PR；任一 FAIL 回到对应 Task 修复后重测。

---

## Self-Review（计划编写者已核对）

- **规格覆盖**：5 工具（W2 工具条 + W3 渲染/手势）、自动淡出+清空（W1 store + W3 清空键）、坐标 contain/cover（W1+W3 norm）、发送 draw_command（W4）、锁缩放防双层坐标（W4 onModeChange + app.js 手势早退）、老人端/服务端零改动（确认未列入改动文件）。✅
- **塌缩 bug 预防**：W1 store END 不回退覆盖 + W3 onUp 带 lastN 作 x2/y2；W1 测试含专门回归断言。✅
- **类型/契约一致**：`init(opts)` 契约在总览定义，W3 实现、W4 调用一致；payload 字段与 Android `DrawPayload`、Go 透传逐字对齐（蛇形 guest_id）。✅
- **占位符扫描**：无 TBD；W1 先放 `init` 占位再于 W3 替换，属有意为之并注明。✅
- **已知取舍**：标注模式锁定缩放（与 Android 一致）；本地回显与视频回传轻微重影；HTML/CSS 结构正确性靠 W5 浏览器冒烟而非自动化。
