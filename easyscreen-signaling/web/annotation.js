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

  function init(opts) {
    if (root && root.console) root.console.warn('[annotation] init not yet implemented');
  }

  const api = { CoordinateMapping, AnnotationStore, DrawOp, DrawTool, init };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.AnnotationOverlay = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
