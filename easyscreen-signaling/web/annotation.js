// EasyScreen Web 画笔标注模块
// 逻辑核心（CoordinateMapping / AnnotationStore）与 Android 端 annotation/* 逐字对齐。
// 同时支持浏览器（window.AnnotationOverlay）与 Node（module.exports，仅逻辑测试）。
(function (root) {
  'use strict';

  function clamp01(v) { return Math.min(1, Math.max(0, v)); }

  const DrawOp = { BEGIN: 'begin', POINT: 'point', END: 'end', TAP: 'tap', CLEAR: 'clear' };
  const DrawTool = { LASER: 'laser', PEN: 'pen', CIRCLE: 'circle', ARROW: 'arrow', RIPPLE: 'ripple' };

  const CoordinateMapping = {
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
    // 归一化 [0,1] → 舞台内"视频内容区"像素（contain/cover 的 letterbox 反算，与 touchToNormalized 对称）
    normalizedToContentPixel: function (nx, ny, stageW, stageH, srcW, srcH, fillCover) {
      if (srcW <= 0 || srcH <= 0 || stageW <= 0 || stageH <= 0) return [nx * stageW, ny * stageH];
      const scale = fillCover
        ? Math.max(stageW / srcW, stageH / srcH)
        : Math.min(stageW / srcW, stageH / srcH);
      const cw = srcW * scale, ch = srcH * scale;
      const ox = (stageW - cw) / 2, oy = (stageH - ch) / 2;
      return [ox + nx * cw, oy + ny * ch];
    },
  };

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

    function resizeCanvas() {
      const rect = stageEl.getBoundingClientRect();
      dpr = window.devicePixelRatio || 1;
      canvasEl.width = Math.round(rect.width * dpr);
      canvasEl.height = Math.round(rect.height * dpr);
      canvasEl.style.width = rect.width + 'px';
      canvasEl.style.height = rect.height + 'px';
    }

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
      store.apply(p, performance.now());
      send(p);
    }

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

    // 映射回"视频内容区"而非整个舞台，与触点输入对称（修复本地回显以整页为坐标的偏移）
    function px(n, rect) {
      return CoordinateMapping.normalizedToContentPixel(
        n[0], n[1], rect.width, rect.height,
        videoEl.videoWidth || 0, videoEl.videoHeight || 0, !!getFitCover(),
      );
    }
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

    // 工具条位置（上/右/下/左，持久化），点 ⤢ 循环切换，适应不同遮挡
    const POSITIONS = ['top', 'right', 'bottom', 'left'];
    let posIdx = Math.max(0, POSITIONS.indexOf(localStorage.getItem('easyscreen.annoPos') || 'top'));
    function applyPos() {
      const p = POSITIONS[posIdx];
      toolbarEl.classList.remove('pos-top', 'pos-right', 'pos-bottom', 'pos-left');
      toolbarEl.classList.add('pos-' + p);
      try { localStorage.setItem('easyscreen.annoPos', p); } catch (_) {}
    }
    const posBtn = toolbarEl.querySelector('#anno-pos');
    if (posBtn) posBtn.addEventListener('click', () => { posIdx = (posIdx + 1) % POSITIONS.length; applyPos(); });
    applyPos();

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

    // 暴露给宿主：断开/重连时清空本地回显并退出标注模式，避免旧标注残留
    api.reset = function () { store.clear(); setActive(false); };
  }

  const api = { CoordinateMapping, AnnotationStore, DrawOp, DrawTool, init };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.AnnotationOverlay = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
