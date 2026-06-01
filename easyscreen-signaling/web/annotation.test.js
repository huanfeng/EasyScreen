'use strict';
const assert = require('assert');
const { CoordinateMapping, AnnotationStore, DrawOp, DrawTool } = require('./annotation.js');

// ---- CoordinateMapping ----
{
  const r = CoordinateMapping.touchToNormalized(540, 1170, 1080, 2340, 1080, 2340, false);
  assert(r && Math.abs(r[0] - 0.5) < 1e-3 && Math.abs(r[1] - 0.5) < 1e-3, 'sameAspect center');
}
{
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

// ---- 渲染映射与触点输入对称（本地回显对齐回归）----
{
  // contain：源 1000x500，舞台 1000x600（上下黑边各 50）；触点中心应原样还原
  const n = CoordinateMapping.touchToNormalized(500, 300, 1000, 600, 1000, 500, false);
  const p = CoordinateMapping.normalizedToContentPixel(n[0], n[1], 1000, 600, 1000, 500, false);
  assert(Math.abs(p[0] - 500) < 1e-3 && Math.abs(p[1] - 300) < 1e-3, 'contain round-trip center');
}
{
  // 内容区左上角：touch=(0,50) → norm≈(0,0) → 还原到 (0,50)，而非 (0,0)
  const n = CoordinateMapping.touchToNormalized(0, 50, 1000, 600, 1000, 500, false);
  const p = CoordinateMapping.normalizedToContentPixel(n[0], n[1], 1000, 600, 1000, 500, false);
  assert(Math.abs(p[0] - 0) < 1e-3 && Math.abs(p[1] - 50) < 1e-3, 'contain round-trip corner (letterbox offset preserved)');
}
{
  // cover：源 1000x500，舞台 600x600（左右裁剪）；触点原样还原
  const n = CoordinateMapping.touchToNormalized(300, 300, 600, 600, 1000, 500, true);
  const p = CoordinateMapping.normalizedToContentPixel(n[0], n[1], 600, 600, 1000, 500, true);
  assert(Math.abs(p[0] - 300) < 1e-3 && Math.abs(p[1] - 300) < 1e-3, 'cover round-trip');
}

console.log('✓ all web annotation logic tests passed');
