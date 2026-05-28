package main

// statsHTML 是 /stats 可视化页面，自包含（无外部依赖），每 3 秒拉取 /stats.json。
// 注意：内嵌 JS 不能使用反引号（与 Go raw string 冲突），统一用普通字符串拼接。
const statsHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
<title>远程看屏 · 服务器状态</title>
<style>
  :root {
    --bg: #0f1115;
    --surface: #1a1d24;
    --surface-2: #242832;
    --border: #2c313c;
    --text: #e6e8ec;
    --text-dim: #8b919c;
    --primary: #4f9cff;
    --green: #3ddc84;
    --amber: #ffb74d;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    padding: 20px;
    max-width: 880px;
    margin: 0 auto;
  }
  header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 4px;
  }
  h1 { font-size: 20px; font-weight: 600; }
  .uptime { color: var(--text-dim); font-size: 13px; }
  .updated { color: var(--text-dim); font-size: 12px; margin-bottom: 20px; }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 12px;
    margin-bottom: 20px;
  }
  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px;
  }
  .card .label { color: var(--text-dim); font-size: 13px; margin-bottom: 8px; }
  .card .value { font-size: 28px; font-weight: 700; line-height: 1; }
  .card .value.green { color: var(--green); }
  .card .value.primary { color: var(--primary); }
  .card .value.amber { color: var(--amber); }
  .card .sub { color: var(--text-dim); font-size: 12px; margin-top: 6px; }
  .section-title { font-size: 14px; color: var(--text-dim); margin: 8px 0 12px; }
  .dist {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px;
  }
  .dist-empty { color: var(--text-dim); font-size: 13px; }
  .bar-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
  .bar-row .idx { width: 64px; color: var(--text-dim); font-size: 12px; }
  .bar-track { flex: 1; background: var(--surface-2); border-radius: 6px; height: 18px; overflow: hidden; }
  .bar-fill { height: 100%; background: var(--primary); border-radius: 6px; min-width: 2px; }
  .bar-row .cnt { width: 56px; text-align: right; font-size: 13px; }
  footer { color: var(--text-dim); font-size: 12px; margin-top: 24px; text-align: center; }
  .offline { color: var(--amber); }
</style>
</head>
<body>
  <header>
    <h1>远程看屏 · 服务器状态</h1>
    <span class="uptime" id="uptime">运行时长 --</span>
  </header>
  <div class="updated" id="updated">正在加载…</div>

  <div class="grid">
    <div class="card">
      <div class="label">在线房间</div>
      <div class="value primary" id="active-rooms">--</div>
      <div class="sub" id="rooms-sub">--</div>
    </div>
    <div class="card">
      <div class="label">当前连接</div>
      <div class="value" id="total-conn">--</div>
      <div class="sub" id="conn-sub">--</div>
    </div>
    <div class="card">
      <div class="label">被控端（共享方）</div>
      <div class="value green" id="host-count">--</div>
    </div>
    <div class="card">
      <div class="label">观看端（观看方）</div>
      <div class="value green" id="guest-count">--</div>
    </div>
    <div class="card">
      <div class="label">累计创建房间</div>
      <div class="value" id="total-rooms">--</div>
    </div>
    <div class="card">
      <div class="label">累计观看接入</div>
      <div class="value" id="total-guests">--</div>
    </div>
  </div>

  <div class="section-title">各房间观看人数分布（匿名）</div>
  <div class="dist" id="dist"><span class="dist-empty">暂无观看中的房间</span></div>

  <footer>数据每 3 秒自动刷新 · 全部为匿名聚合统计</footer>

<script>
(function () {
  function fmtUptime(sec) {
    sec = Math.max(0, Math.floor(sec));
    var d = Math.floor(sec / 86400);
    var h = Math.floor((sec % 86400) / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    var parts = [];
    if (d > 0) parts.push(d + ' 天');
    if (h > 0) parts.push(h + ' 时');
    if (m > 0) parts.push(m + ' 分');
    parts.push(s + ' 秒');
    return parts.join(' ');
  }

  function setText(id, v) { document.getElementById(id).textContent = v; }

  function renderDist(list) {
    var box = document.getElementById('dist');
    if (!list || list.length === 0) {
      box.innerHTML = '<span class="dist-empty">暂无观看中的房间</span>';
      return;
    }
    var sorted = list.slice().sort(function (a, b) { return b - a; });
    var max = sorted[0] || 1;
    var html = '';
    for (var i = 0; i < sorted.length; i++) {
      var pct = Math.round((sorted[i] / max) * 100);
      html += '<div class="bar-row">'
        + '<span class="idx">房间 ' + (i + 1) + '</span>'
        + '<span class="bar-track"><span class="bar-fill" style="width:' + pct + '%"></span></span>'
        + '<span class="cnt">' + sorted[i] + ' 人</span>'
        + '</div>';
    }
    box.innerHTML = html;
  }

  function refresh() {
    fetch('/stats.json', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        setText('uptime', '运行时长 ' + fmtUptime(d.uptime_seconds));
        setText('active-rooms', d.active_rooms);
        var roomsSub = '在线 ' + d.rooms_with_host + ' · ';
        if (d.rooms_awaiting_host > 0) {
          roomsSub += '等待重连 ' + d.rooms_awaiting_host;
        } else {
          roomsSub += '等待重连 0';
        }
        setText('rooms-sub', roomsSub);
        setText('total-conn', d.total_connections);
        setText('conn-sub', '被控端 ' + d.host_count + ' · 观看端 ' + d.guest_count);
        setText('host-count', d.host_count);
        setText('guest-count', d.guest_count);
        setText('total-rooms', d.total_rooms_created);
        setText('total-guests', d.total_guests_joined);
        renderDist(d.guest_distribution);
        var now = new Date();
        var hh = String(now.getHours()).padStart(2, '0');
        var mm = String(now.getMinutes()).padStart(2, '0');
        var ss = String(now.getSeconds()).padStart(2, '0');
        document.getElementById('updated').textContent = '最后更新 ' + hh + ':' + mm + ':' + ss;
        document.getElementById('updated').classList.remove('offline');
      })
      .catch(function () {
        var el = document.getElementById('updated');
        el.textContent = '无法连接服务器，重试中…';
        el.classList.add('offline');
      });
  }

  refresh();
  setInterval(refresh, 3000);
})();
</script>
</body>
</html>`
