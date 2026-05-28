// EasyScreen Web Guest
// 扮演控制端（Guest）：扫码 → 加入房间 → 创建 Offer → 接收远端视频
// 严格匹配 Android Guest 的信令协议（参见 easyscreen-android/.../SignalingMessage.kt）

(() => {
  'use strict';

  // ---------- 信令消息常量（与 Go 服务端一致） ----------
  const MSG = {
    REGISTER: 'register',
    JOIN: 'join',
    OFFER: 'offer',
    ANSWER: 'answer',
    CANDIDATE: 'candidate',
    ERROR: 'error',
    ROOM_READY: 'room_ready',
    DISCONNECT: 'disconnect',
    PING: 'ping',
    PONG: 'pong',
  };

  // ---------- ICE 服务器：STUN + 公共 TURN ----------
  // 对称 NAT / 移动数据下若 P2P 打洞失败，会回退到 TURN 中继
  const DEFAULT_ICE_SERVERS = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun.jiyun.com:3478' },
    { urls: 'stun:stun.qq.com:3478' },
    {
      urls: [
        'turn:openrelay.metered.ca:80',
        'turn:openrelay.metered.ca:443',
        'turn:openrelay.metered.ca:443?transport=tcp',
      ],
      username: 'openrelayproject',
      credential: 'openrelayproject',
    },
  ];

  // ---------- DOM ----------
  const $ = (id) => document.getElementById(id);
  const codeInput = $('code');
  const connectBtn = $('connect-btn');
  const refreshBtn = $('refresh-btn');
  const clearCodeBtn = $('clear-code-btn');
  const disconnectBtn = $('disconnect-btn');
  const reconnectBtn = $('reconnect-btn');
  const diagBtn = $('diag-btn');
  const diagClose = $('diag-close');
  const diagPanel = $('diag-panel');
  const diagBody = $('diag-body');
  const statusEl = $('status');
  const videoStage = $('video-view');
  const videoCanvas = $('video-canvas');
  const fsBtn = $('fs-btn');
  const fitBtn = $('fit-btn');
  const backHomeBtn = $('back-home-btn');
  const zoomIn = $('zoom-in');
  const zoomOut = $('zoom-out');
  const zoomReset = $('zoom-reset');
  const zoomLabel = $('zoom-label');
  const joinView = $('join-view');
  const videoView = $('video-view');
  const video = $('remote-video');
  const overlay = $('overlay');
  const overlayText = $('overlay-text');
  const statsEl = $('stats');
  const turnInput = $('turn');

  // ---------- 状态 ----------
  let ws = null;
  let pc = null;
  let statsTimer = null;
  let pendingRemoteCandidates = [];
  let hasRemoteDescription = false;
  let combinedStream = null;
  let currentRoomId = '';
  let myGuestId = '';

  const LS_KEY_LAST_CODE = 'easyscreen.lastCode';

  // ---------- UI ----------
  function setStatus(text, kind) {
    statusEl.textContent = text;
    statusEl.className = 'status' + (kind ? ' ' + kind : '');
  }

  function setOverlay(text, visible) {
    overlayText.textContent = text;
    overlay.classList.toggle('hidden', !visible);
  }

  function switchToVideo() {
    joinView.classList.add('hidden');
    videoView.classList.remove('hidden');
  }

  function switchToJoin() {
    videoView.classList.add('hidden');
    joinView.classList.remove('hidden');
    setOverlay('正在建立连接…', true);
    video.srcObject = null;
  }

  // 启动时回显上次输入
  const savedCode = localStorage.getItem(LS_KEY_LAST_CODE) || '';
  if (savedCode && /^\d{6}$/.test(savedCode)) {
    codeInput.value = savedCode;
    setStatus(`上次连接的房间号：${savedCode}（点击"开始预览"重新连接）`);
  }
  updateInputUI();

  // 输入限制：仅数字 + 满 6 位才允许点击
  function updateInputUI() {
    connectBtn.disabled = codeInput.value.length !== 6;
    clearCodeBtn.classList.toggle('hidden', codeInput.value.length === 0);
    refreshBtn.classList.add('hidden');
  }
  codeInput.addEventListener('input', () => {
    codeInput.value = codeInput.value.replace(/\D/g, '').slice(0, 6);
    updateInputUI();
  });
  clearCodeBtn.addEventListener('click', () => {
    codeInput.value = '';
    updateInputUI();
    codeInput.focus();
  });

  codeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !connectBtn.disabled) connectBtn.click();
  });

  connectBtn.addEventListener('click', () => start(codeInput.value));
  refreshBtn.addEventListener('click', () => {
    // 重置 UI 并重试
    refreshBtn.classList.add('hidden');
    connectBtn.disabled = codeInput.value.length !== 6;
    setStatus('准备重试…');
    if (codeInput.value.length === 6) start(codeInput.value);
  });
  disconnectBtn.addEventListener('click', () => teardown('user'));
  reconnectBtn.addEventListener('click', () => {
    const code = currentRoomId || codeInput.value || localStorage.getItem(LS_KEY_LAST_CODE);
    if (!code) { setStatus('没有可重连的房间号', 'error'); return; }
    teardownInternal();
    setOverlay('正在重连…', true);
    start(code);
  });

  function showReconnect(show) {
    reconnectBtn.classList.toggle('hidden', !show);
    // 处于"无法连接"或"对方已离线"状态时，同时显示返回主页按钮
    backHomeBtn.classList.toggle('hidden', !show);
  }

  backHomeBtn.addEventListener('click', () => teardown('user'));

  // ---------- 诊断面板 ----------
  diagBtn.addEventListener('click', () => {
    diagPanel.classList.toggle('hidden');
  });
  diagClose.addEventListener('click', () => {
    diagPanel.classList.add('hidden');
  });

  function renderDiag(snap) {
    if (!snap) {
      diagBody.innerHTML = '<div class="diag-row"><span class="label">状态</span><span class="value">无数据</span></div>';
      return;
    }
    const modeClass =
      snap.mode === '中继 (TURN)' ? 'mode-relay' :
      snap.mode === '局域网直连' ? 'mode-direct' :
      snap.mode === 'NAT 穿透' ? 'mode-nat' : '';
    const row = (l, v, cls = '') => `<div class="diag-row ${cls}"><span class="label">${l}</span><span class="value">${v}</span></div>`;
    diagBody.innerHTML = [
      row('连接模式', snap.mode, modeClass),
      row('Candidate 路径', snap.pathType),
      row('编码', snap.codec),
      row('分辨率', snap.resolution),
      row('帧率', snap.fps.toFixed(1) + ' fps'),
      row('接收码率', snap.kbps.toFixed(0) + ' kbps'),
      row('累计接收', formatBytes(snap.bytesReceived)),
      row('RTT', snap.rttMs == null ? '—' : snap.rttMs.toFixed(0) + ' ms'),
      row('丢包', snap.packetsLost),
      row('NACK 发出', snap.nackCount),
      row('PLI 发出', snap.pliCount),
      row('Jitter', snap.jitterMs == null ? '—' : snap.jitterMs.toFixed(0) + ' ms'),
      row('Guest ID', myGuestId ? myGuestId.slice(0, 8) + '…' : '—'),
    ].join('');
  }

  function formatBytes(b) {
    if (b < 1024) return `${b} B`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
    if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`;
    return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }

  // 判断 IP 是否为私有/链路本地（不可路由）
  function isPrivateAddr(addr) {
    if (!addr) return false;
    // IPv4 私网 / 链路本地 / 回环
    if (/^10\./.test(addr)) return true;
    if (/^192\.168\./.test(addr)) return true;
    if (/^172\.(1[6-9]|2[0-9]|3[01])\./.test(addr)) return true;
    if (/^169\.254\./.test(addr)) return true;  // link-local
    if (/^127\./.test(addr)) return true;
    if (/^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\./.test(addr)) return true;  // CGNAT 100.64/10
    // IPv6 link-local fe80::, unique local fc00::/7, loopback ::1
    if (/^fe[89ab][0-9a-f]/i.test(addr)) return true;
    if (/^f[cd][0-9a-f]{2}/i.test(addr)) return true;
    if (addr === '::1') return true;
    return false;
  }

  function deriveMode(localCand, remoteCand) {
    const lt = (localCand && localCand.candidateType || '').toLowerCase();
    const rt = (remoteCand && remoteCand.candidateType || '').toLowerCase();
    if (lt === 'relay' || rt === 'relay') return '中继 (TURN)';
    if (lt === 'host' && rt === 'host') {
      const la = localCand && localCand.address || '';
      const ra = remoteCand && remoteCand.address || '';
      // 任意一端是公网地址（如 IPv6 全局地址）→ 公网直连
      if (la && ra && (!isPrivateAddr(la) || !isPrivateAddr(ra))) return '公网直连';
      return '局域网直连';
    }
    if ((lt === 'srflx' || lt === 'prflx') && (rt === 'srflx' || rt === 'prflx')) return 'NAT 穿透';
    if ((lt === 'host' && (rt === 'srflx' || rt === 'prflx')) ||
        ((lt === 'srflx' || lt === 'prflx') && rt === 'host')) return 'NAT 穿透';
    return '未知';
  }

  // ---------- 入口 ----------
  function start(roomId) {
    setStatus('正在连接信令服务器…');
    connectBtn.disabled = true;
    currentRoomId = roomId;
    try { localStorage.setItem(LS_KEY_LAST_CODE, roomId); } catch (_) {}

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    // 同源连接；自定义请通过 ?ws=wss://... 覆盖
    const params = new URLSearchParams(location.search);
    const wsUrl = params.get('ws') || `${proto}//${location.host}/ws`;

    try {
      ws = new WebSocket(wsUrl);
    } catch (e) {
      setStatus('无法创建 WebSocket：' + e.message, 'error');
      connectBtn.disabled = false;
      return;
    }

    ws.onopen = () => {
      setStatus('已连接，正在加入房间…');
      send({ type: MSG.JOIN, room_id: roomId });
    };

    ws.onmessage = (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return;
      }
      handleSignal(msg);
    };

    ws.onclose = () => {
      console.log('[ws] closed');
      if (pc) {
        // 媒体侧可能还活着，但失去了信令通道 —— 给重连
        setStatus('信令通道断开，点 ↻ 重连', 'warning');
        showReconnect(true);
      } else {
        setStatus('已断开', 'warning');
        connectBtn.disabled = false;
      }
    };

    ws.onerror = (e) => {
      console.warn('[ws] error', e);
      setStatus('连接信令服务器失败，点 ↻ 重试', 'error');
      showReconnect(true);
    };
  }

  function send(msg) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  }

  // ---------- 信令处理 ----------
  async function handleSignal(msg) {
    switch (msg.type) {
      case MSG.ROOM_READY:
        // 服务端会在 payload.guest_id 给本端分配的 ID
        if (msg.payload && msg.payload.guest_id) {
          myGuestId = msg.payload.guest_id;
          console.log('[room_ready] my guest_id =', myGuestId);
        }
        switchToVideo();
        setOverlay('正在协商连接…', true);
        await createPeerAndOffer();
        break;

      case MSG.ANSWER:
        await onAnswer(msg.payload);
        break;

      case MSG.CANDIDATE:
        await onCandidate(msg.payload);
        break;

      case MSG.PING:
        send({ type: MSG.PONG });
        break;

      case MSG.ERROR: {
        const m = (msg.data && msg.data.message) || '未知错误';
        console.warn('[server-error]', m);

        // 房间已满 / 房间不存在 / 房主拒绝 等"入场前"错误：用户还在输入界面，提供 refresh 按钮
        const isPreJoin = /房间已满|房间不存在或已过期|房间暂无被控端|已被房主断开/.test(m);
        if (isPreJoin) {
          setStatus(m + '，可点 ↻ 重试', 'warning');
          // 触发自动 teardown 以便重新连接
          teardownInternal();
          // 显示输入界面上的 refresh 按钮
          if (!videoStage.classList.contains('hidden')) switchToJoin();
          connectBtn.disabled = true;
          refreshBtn.classList.remove('hidden');
          break;
        }

        // 对端缺席类错误：不 teardown，保留 video 视图 + 重连按钮
        if (/对端未连接|被控端已断开/.test(m)) {
          setStatus('对方已离线：' + m + '，点 ↻ 重试', 'warning');
          setOverlay('对方已离线，等待重新上线', true);
          showReconnect(true);
        } else {
          setStatus('错误：' + m, 'error');
          showReconnect(true);
        }
        break;
      }

      case MSG.DISCONNECT:
        setStatus('对方已断开，点 ↻ 重连', 'warning');
        setOverlay('对方已断开', true);
        showReconnect(true);
        break;
    }
  }

  // ---------- WebRTC ----------
  async function createPeerAndOffer() {
    const iceServers = buildIceServers();
    pc = new RTCPeerConnection({
      iceServers,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
    });

    // 纯接收方：声明 recvonly 收发器，无需调 addTrack
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });

    pc.onicecandidate = (e) => {
      if (!e.candidate) return;
      send({
        type: MSG.CANDIDATE,
        payload: {
          candidate: e.candidate.candidate,
          sdpMid: e.candidate.sdpMid || '',
          sdpMLineIndex: e.candidate.sdpMLineIndex || 0,
          guest_id: myGuestId,  // 服务端会强制覆盖为本端实际 guestId
        },
      });
    };

    // 远端 video 和 audio 可能来自两个不同的 MediaStream
    // 合并进同一个本地 stream，srcObject 只赋值一次以避免 play 被打断
    combinedStream = new MediaStream();

    pc.ontrack = (e) => {
      console.log('[ontrack]', e.track.kind, 'streams=', e.streams.length);
      combinedStream.addTrack(e.track);

      if (video.srcObject !== combinedStream) {
        video.srcObject = combinedStream;
        video.play().catch((err) => console.warn('[video] play failed', err));
      }

      // 一旦收到 track 就先把 overlay 撤下来
      setOverlay('', false);
      setStatus('已连接', 'success');
      startStats();

      // track 自身结束时打印
      e.track.onended = () => console.log('[track ended]', e.track.kind);
    };

    video.onloadedmetadata = () => {
      console.log('[video] metadata', video.videoWidth, 'x', video.videoHeight);
    };
    video.onresize = () => {
      console.log('[video] resize', video.videoWidth, 'x', video.videoHeight);
    };

    pc.oniceconnectionstatechange = () => {
      const s = pc.iceConnectionState;
      console.log('[ice]', s);
      if (s === 'failed') {
        setStatus('P2P 已断开，点 ↻ 重新连接', 'error');
        setOverlay('连接已断开', true);
        showReconnect(true);
      } else if (s === 'disconnected') {
        setStatus('连接不稳定…', 'warning');
        setOverlay('网络抖动中…', true);
        // 抖动 3 秒还没恢复就显示重连按钮，不强制 teardown
        setTimeout(() => {
          if (pc && pc.iceConnectionState === 'disconnected') {
            showReconnect(true);
          }
        }, 3000);
      } else if (s === 'connected' || s === 'completed') {
        setStatus('已连接', 'success');
        setOverlay('', false);
        showReconnect(false);
      }
    };

    pc.onconnectionstatechange = () => {
      console.log('[pc]', pc.connectionState);
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        setOverlay('连接已断开', true);
        showReconnect(true);
      }
    };

    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      send({
        type: MSG.OFFER,
        payload: { sdp: offer.sdp, type: 'offer', guest_id: myGuestId },
      });
    } catch (err) {
      setStatus('创建 Offer 失败：' + err.message, 'error');
      teardown('offer-failed');
    }
  }

  async function onAnswer(payload) {
    if (!pc || !payload) return;
    try {
      await pc.setRemoteDescription({ type: 'answer', sdp: payload.sdp });
      hasRemoteDescription = true;
      // 应用缓冲的 ICE
      for (const c of pendingRemoteCandidates) {
        try { await pc.addIceCandidate(c); } catch (e) { console.warn(e); }
      }
      pendingRemoteCandidates = [];
    } catch (err) {
      setStatus('设置远端描述失败：' + err.message, 'error');
    }
  }

  async function onCandidate(payload) {
    if (!pc || !payload || !payload.candidate) return;
    const cand = new RTCIceCandidate({
      candidate: payload.candidate,
      sdpMid: payload.sdpMid || '',
      sdpMLineIndex: payload.sdpMLineIndex || 0,
    });
    if (!hasRemoteDescription) {
      pendingRemoteCandidates.push(cand);
      return;
    }
    try { await pc.addIceCandidate(cand); } catch (e) { console.warn(e); }
  }

  function buildIceServers() {
    const urlTurn = (new URLSearchParams(location.search)).get('turn');
    const userTurn = (turnInput && turnInput.value.trim()) || urlTurn;
    if (!userTurn) return DEFAULT_ICE_SERVERS;

    // 支持 turn:user:pass@host:port 简写
    const m = userTurn.match(/^turns?:(?:([^:@]+):([^@]+)@)?(.+)$/);
    if (!m) return DEFAULT_ICE_SERVERS;
    const [, user, pass, hostPart] = m;
    const proto = userTurn.startsWith('turns:') ? 'turns:' : 'turn:';
    const turnEntry = { urls: proto + hostPart };
    if (user) { turnEntry.username = user; turnEntry.credential = pass; }
    return [...DEFAULT_ICE_SERVERS, turnEntry];
  }

  // ---------- 统计信息 + 流冻结检测 + 诊断快照 ----------
  function startStats() {
    stopStats();
    let lastBytes = 0;
    let lastTs = 0;
    let frozenSince = 0;
    let frozenReported = false;

    statsTimer = setInterval(async () => {
      if (!pc) return;
      const report = await pc.getStats();
      const stats = {};
      report.forEach((s) => { stats[s.id] = s; });

      // 找选中的 candidate-pair
      let selectedPair = null;
      for (const id in stats) {
        const s = stats[id];
        if (s.type === 'transport' && s.selectedCandidatePairId) {
          selectedPair = stats[s.selectedCandidatePairId];
          break;
        }
      }
      if (!selectedPair) {
        for (const id in stats) {
          const s = stats[id];
          if (s.type === 'candidate-pair' && s.nominated && s.state === 'succeeded') {
            selectedPair = s; break;
          }
        }
      }
      const localCand = selectedPair && stats[selectedPair.localCandidateId];
      const remoteCand = selectedPair && stats[selectedPair.remoteCandidateId];
      const localType = localCand ? localCand.candidateType : '?';
      const remoteType = remoteCand ? remoteCand.candidateType : '?';
      const pathType = `${localType}(${localCand?.address || '?'}) ↔ ${remoteType}(${remoteCand?.address || '?'})`;
      const mode = deriveMode(localCand, remoteCand);
      const rttMs = selectedPair && selectedPair.currentRoundTripTime != null
        ? selectedPair.currentRoundTripTime * 1000 : null;

      // inbound-rtp video
      let inboundVideo = null;
      for (const id in stats) {
        const s = stats[id];
        if (s.type === 'inbound-rtp' && s.kind === 'video') { inboundVideo = s; break; }
      }
      if (!inboundVideo) return;

      const now = inboundVideo.timestamp;
      const bytes = inboundVideo.bytesReceived || 0;
      let kbps = 0;
      if (lastTs && now > lastTs) kbps = ((bytes - lastBytes) * 8) / (now - lastTs);

      // 冻结检测
      if (bytes === lastBytes && lastBytes > 0) {
        if (!frozenSince) frozenSince = Date.now();
        const frozenMs = Date.now() - frozenSince;
        if (frozenMs >= 4000 && !frozenReported) {
          frozenReported = true;
          console.warn('[stream-frozen] no bytes received for', frozenMs, 'ms');
          setStatus('画面已停止 (无数据)，点 ↻ 重连', 'error');
          setOverlay('画面已停止', true);
          showReconnect(true);
        }
      } else {
        if (frozenReported) {
          setStatus('已连接', 'success');
          setOverlay('', false);
          showReconnect(false);
        }
        frozenSince = 0;
        frozenReported = false;
      }

      lastBytes = bytes;
      lastTs = now;

      const codecId = inboundVideo.codecId;
      const codec = (codecId && stats[codecId] && stats[codecId].mimeType)
        ? stats[codecId].mimeType.replace('video/', '') : '—';
      const w = video.videoWidth, h = video.videoHeight;
      const resolution = (w > 0 && h > 0) ? `${w}×${h}` : '—';
      const fps = inboundVideo.framesPerSecond || 0;

      // 底部小字
      statsEl.textContent = `${resolution}  ${kbps.toFixed(0)} kbps  ${mode}`;

      // 诊断面板（如果打开）
      if (!diagPanel.classList.contains('hidden')) {
        renderDiag({
          mode, pathType, codec, resolution, fps, kbps,
          bytesReceived: bytes,
          rttMs,
          packetsLost: inboundVideo.packetsLost || 0,
          nackCount: inboundVideo.nackCount || 0,
          pliCount: inboundVideo.pliCount || 0,
          jitterMs: inboundVideo.jitter != null ? inboundVideo.jitter * 1000 : null,
        });
      }
    }, 1000);
  }

  function stopStats() {
    if (statsTimer) clearInterval(statsTimer);
    statsTimer = null;
    statsEl.textContent = '';
  }

  // ---------- 清理 ----------
  function teardownInternal() {
    console.log('[teardownInternal]');
    stopStats();
    pendingRemoteCandidates = [];
    hasRemoteDescription = false;

    if (combinedStream) {
      combinedStream.getTracks().forEach((t) => { try { t.stop(); } catch {} });
      combinedStream = null;
    }
    if (video.srcObject) {
      video.srcObject = null;
    }
    if (pc) {
      try { pc.close(); } catch {}
      pc = null;
    }
    if (ws) {
      try {
        if (ws.readyState === WebSocket.OPEN) {
          send({ type: MSG.DISCONNECT });
        }
        ws.close();
      } catch {}
      ws = null;
    }
  }

  function teardown(reason) {
    console.log('[teardown]', reason);
    teardownInternal();
    showReconnect(false);
    if (reason === 'user') {
      switchToJoin();
      setStatus('已断开');
      connectBtn.disabled = codeInput.value.length !== 6;
    }
  }

  window.addEventListener('beforeunload', () => teardown('unload'));

  // 浏览器本机网络变化
  window.addEventListener('offline', () => {
    console.warn('[net] offline');
    if (pc || ws) {
      setStatus('您的网络已断开', 'error');
      setOverlay('您的网络已断开', true);
      showReconnect(true);
    }
  });
  window.addEventListener('online', () => {
    console.log('[net] online');
    if (pc && pc.iceConnectionState !== 'connected') {
      setStatus('网络已恢复，可点 ↻ 重连', 'warning');
      showReconnect(true);
    }
  });

  // ===================== 缩放 + 平移 + 全屏 =====================
  const ZOOM_MIN = 1.0;
  const ZOOM_MAX = 6.0;
  const ZOOM_STEP = 0.25;
  const view = { scale: 1, tx: 0, ty: 0 };

  function applyTransform() {
    videoCanvas.style.transform =
      `translate(${view.tx}px, ${view.ty}px) scale(${view.scale})`;
    zoomLabel.textContent = `${Math.round(view.scale * 100)}%`;
  }

  // 在某一焦点处缩放（焦点屏幕坐标 → 保持该点在屏幕上的位置不变）
  function zoomAt(newScale, focalX, focalY) {
    newScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, newScale));
    if (newScale === view.scale) return;
    const ratio = newScale / view.scale;
    view.tx = focalX - (focalX - view.tx) * ratio;
    view.ty = focalY - (focalY - view.ty) * ratio;
    view.scale = newScale;
    clampPan();
    applyTransform();
  }

  // 限制平移范围：缩放后画面不能比舞台还小（避免拖出去）
  function clampPan() {
    const rect = videoStage.getBoundingClientRect();
    const scaledW = rect.width * view.scale;
    const scaledH = rect.height * view.scale;
    if (scaledW <= rect.width) {
      view.tx = (rect.width - scaledW) / 2;
    } else {
      const minTx = rect.width - scaledW;
      view.tx = Math.max(minTx, Math.min(0, view.tx));
    }
    if (scaledH <= rect.height) {
      view.ty = (rect.height - scaledH) / 2;
    } else {
      const minTy = rect.height - scaledH;
      view.ty = Math.max(minTy, Math.min(0, view.ty));
    }
  }

  function resetView() {
    view.scale = 1; view.tx = 0; view.ty = 0;
    applyTransform();
  }

  zoomIn.addEventListener('click', () => {
    const r = videoStage.getBoundingClientRect();
    zoomAt(view.scale + ZOOM_STEP, r.width / 2, r.height / 2);
  });
  zoomOut.addEventListener('click', () => {
    const r = videoStage.getBoundingClientRect();
    zoomAt(view.scale - ZOOM_STEP, r.width / 2, r.height / 2);
  });
  zoomReset.addEventListener('click', resetView);

  // ---- 滚轮缩放（PC） ----
  videoStage.addEventListener('wheel', (e) => {
    if (videoStage.classList.contains('hidden')) return;
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    zoomAt(view.scale * factor, e.clientX, e.clientY);
  }, { passive: false });

  // ---- 双击切换 100%/200% ----
  let lastTap = 0;
  videoStage.addEventListener('click', (e) => {
    // 忽略浮动按钮点击
    if (e.target.closest('.floating-action') || e.target.closest('.zoom-bar') ||
        e.target.closest('.diag-panel') || e.target.closest('.floating-stats')) return;
    const now = Date.now();
    if (now - lastTap < 300) {
      if (view.scale > 1.01) resetView();
      else zoomAt(2.0, e.clientX, e.clientY);
    }
    lastTap = now;
  });

  // ---- 触摸/鼠标 平移 + 双指捏合 ----
  const pointers = new Map();  // pointerId → { x, y }
  let pinchStartDist = 0;
  let pinchStartScale = 1;
  let pinchCenter = { x: 0, y: 0 };
  let panStart = null;

  videoCanvas.addEventListener('pointerdown', (e) => {
    videoCanvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 1) {
      panStart = { x: e.clientX, y: e.clientY, tx: view.tx, ty: view.ty };
    } else if (pointers.size === 2) {
      const pts = [...pointers.values()];
      pinchStartDist = dist(pts[0], pts[1]);
      pinchStartScale = view.scale;
      pinchCenter = midpoint(pts[0], pts[1]);
      panStart = null;
    }
  });

  videoCanvas.addEventListener('pointermove', (e) => {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) {
      const pts = [...pointers.values()];
      const d = dist(pts[0], pts[1]);
      if (pinchStartDist > 0) {
        const newScale = pinchStartScale * (d / pinchStartDist);
        zoomAt(newScale, pinchCenter.x, pinchCenter.y);
      }
    } else if (pointers.size === 1 && panStart && view.scale > 1) {
      view.tx = panStart.tx + (e.clientX - panStart.x);
      view.ty = panStart.ty + (e.clientY - panStart.y);
      clampPan();
      applyTransform();
    }
  });

  function endPointer(e) {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) {
      pinchStartDist = 0;
      const remaining = [...pointers.values()][0];
      if (remaining) {
        panStart = { x: remaining.x, y: remaining.y, tx: view.tx, ty: view.ty };
      } else {
        panStart = null;
      }
    }
  }
  videoCanvas.addEventListener('pointerup', endPointer);
  videoCanvas.addEventListener('pointercancel', endPointer);

  function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }
  function midpoint(a, b) { return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }; }

  // ---- 适应 / 充满 切换（contain ↔ cover）----
  // 移动端 / 触屏设备默认充满（手机源比例和手机视口接近，避免大面积黑边）；PC 默认适应
  const defaultFit = window.matchMedia('(pointer:coarse)').matches ? 'cover' : 'contain';
  let fitMode = localStorage.getItem('easyscreen.fitMode') || defaultFit;
  applyFitMode();
  function applyFitMode() {
    video.classList.remove('fit-contain', 'fit-cover');
    video.classList.add(fitMode === 'cover' ? 'fit-cover' : 'fit-contain');
    fitBtn.textContent = fitMode === 'cover' ? '▣' : '▢';
    fitBtn.title = fitMode === 'cover' ? '当前：充满（裁剪）' : '当前：适应（保留全部画面）';
  }
  fitBtn.addEventListener('click', () => {
    fitMode = fitMode === 'cover' ? 'contain' : 'cover';
    localStorage.setItem('easyscreen.fitMode', fitMode);
    applyFitMode();
  });

  // ---- 全屏 ----
  fsBtn.addEventListener('click', async () => {
    try {
      if (!document.fullscreenElement) {
        await videoStage.requestFullscreen({ navigationUI: 'hide' });
        // 横屏锁定（仅移动端 + 支持的浏览器）
        if (screen.orientation && screen.orientation.lock) {
          try { await screen.orientation.lock('landscape'); } catch (_) {}
        }
      } else {
        await document.exitFullscreen();
        if (screen.orientation && screen.orientation.unlock) {
          try { screen.orientation.unlock(); } catch (_) {}
        }
      }
    } catch (err) {
      console.warn('[fullscreen] failed', err);
    }
  });

  // 进入视频阶段时重置 transform
  new MutationObserver(() => {
    if (!videoStage.classList.contains('hidden')) {
      resetView();
    }
  }).observe(videoStage, { attributes: true, attributeFilter: ['class'] });

  // 窗口尺寸变化（横竖屏切换）时重新校准平移边界
  window.addEventListener('resize', () => {
    clampPan();
    applyTransform();
  });

  // 初始化一次
  resetView();
})();
