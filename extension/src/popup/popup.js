/**
 * 弹出页：只负责展示与交互，所有解析 / 下载 / 解密都交给 Service Worker。
 */
(() => {
  'use strict';

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];

  const state = {
    view: 'page',
    settings: { format: 'mp4', maxJobs: 2, folder: 'CCTV', saveAs: false, theme: 'auto', notify: true },
    playlist: null,   // { source, total, items }
    plSelected: new Set(),
    plPref: 'best',
    videos: [],
    expanded: 0,
    choice: {},       // guid -> { quality }
    jobs: [],
  };

  // ---------- 通信 ----------
  function send(type, payload = {}) {
    return new Promise((resolve, reject) => {
      chrome.runtime.sendMessage({ type, ...payload }, (res) => {
        if (chrome.runtime.lastError) return reject(new Error(chrome.runtime.lastError.message));
        if (!res || !res.ok) return reject(new Error((res && res.error) || '后台无响应'));
        resolve(res);
      });
    });
  }

  const port = chrome.runtime.connect({ name: 'ui' });
  port.onMessage.addListener((msg) => {
    if (msg.type === 'jobs') { state.jobs = msg.jobs; renderJobs(); syncDownloadButtons(); }
  });

  // ---------- 工具 ----------
  const fmtDuration = (s) => {
    s = Math.round(s || 0);
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    const p = (n) => String(n).padStart(2, '0');
    return h ? `${h}:${p(m)}:${p(sec)}` : `${m}:${p(sec)}`;
  };
  const fmtBytes = (b) => {
    if (!b) return '0 B';
    const u = ['B', 'KB', 'MB', 'GB'];
    const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
    return `${(b / Math.pow(1024, i)).toFixed(i >= 2 ? 1 : 0)} ${u[i]}`;
  };
  const fmtEta = (s) => {
    if (!s || !isFinite(s)) return '';
    if (s < 60) return `剩余 ${Math.ceil(s)} 秒`;
    if (s < 3600) return `剩余 ${Math.ceil(s / 60)} 分钟`;
    return `剩余 ${(s / 3600).toFixed(1)} 小时`;
  };
  const fmtDate = (str) => {
    if (!str) return '';
    const m = String(str).match(/(\d{4})-(\d{2})-(\d{2})/);
    return m ? `${m[1]}年${+m[2]}月${+m[3]}日` : str;
  };
  const icon = (id) => `<svg><use href="#i-${id}"/></svg>`;

  let toastTimer;
  function toast(text, isError = false) {
    const el = $('#toast');
    el.textContent = text;
    el.classList.toggle('error', isError);
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2400);
  }

  // ---------- 分段控件 ----------
  function setSegment(seg, value) {
    const buttons = $$('.seg', seg);
    let idx = buttons.findIndex((b) => (b.dataset.value || b.dataset.view) === value);
    if (idx < 0) idx = 0;
    buttons.forEach((b, i) => b.classList.toggle('active', i === idx));
    const thumb = $('.seg-thumb', seg);
    requestAnimationFrame(() => {
      const btn = buttons[idx];
      thumb.style.width = `${btn.offsetWidth}px`;
      thumb.style.transform = `translateX(${btn.offsetLeft}px)`;
    });
  }

  // ---------- 视图切换 ----------
  function switchView(view) {
    state.view = view;
    setSegment($('.tabs'), view);
    $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${view}`));
  }
  $$('.tabs .seg').forEach((b) => b.addEventListener('click', () => switchView(b.dataset.view)));

  // ---------- 当前页面 ----------
  function renderPageState(kind, opts = {}) {
    const box = $('#page-state');
    if (kind === 'loading') {
      box.innerHTML = `
        <article class="card skeleton glass">
          <div class="cover"></div>
          <div class="card-body"><div class="sk" style="width:38%"></div><div class="sk" style="width:92%;height:16px;margin-top:10px"></div><div class="sk" style="width:64%;height:16px"></div></div>
        </article>`;
      return;
    }
    if (kind === 'empty') {
      box.innerHTML = `
        <div class="empty">
          <div class="empty-art glass">${icon(opts.icon || 'tv')}</div>
          <h2>${opts.title}</h2>
          <p>${opts.text}</p>
        </div>`;
      return;
    }
    box.innerHTML = '';
  }

  function choiceFor(video) {
    if (!state.choice[video.guid]) {
      state.choice[video.guid] = { quality: video.qualities[0] && video.qualities[0].id };
    }
    return state.choice[video.guid];
  }

  function renderVideos() {
    const list = $('#video-list');
    list.innerHTML = '';
    const tpl = $('#tpl-video');
    state.videos.forEach((video, index) => {
      const node = tpl.content.firstElementChild.cloneNode(true);
      const expanded = index === state.expanded;
      node.classList.toggle('compact', !expanded);
      node.dataset.guid = video.guid;
      const img = $('.cover img', node);
      if (video.cover) img.src = video.cover; else img.remove();
      $('.duration', node).textContent = fmtDuration(video.duration);
      $('.channel', node).textContent = video.channel || video.column || '央视网';
      $('.date', node).textContent = fmtDate(video.publishedAt);
      $('.title', node).textContent = video.title;
      $('.title', node).title = video.title;

      if (!expanded) {
        node.addEventListener('click', () => { state.expanded = index; renderVideos(); });
        list.appendChild(node);
        return;
      }

      const choice = choiceFor(video);
      const chips = $('.quality', node);
      video.qualities.forEach((q) => {
        const b = document.createElement('button');
        b.className = 'q' + (q.id === choice.quality ? ' active' : '');
        b.innerHTML = `<b>${q.name}</b><small>${q.short || (q.rate + 'K')}</small>`;
        b.setAttribute('role', 'radio');
        b.setAttribute('aria-checked', String(q.id === choice.quality));
        b.title = `${q.resolution || ''} ${Math.round(q.bandwidth / 1000)} kbps`;
        b.addEventListener('click', () => {
          choice.quality = q.id;
          $$('.q', chips).forEach((x) => { x.classList.toggle('active', x === b); x.setAttribute('aria-checked', String(x === b)); });
        });
        chips.appendChild(b);
      });

      const btn = $('.download', node);
      btn.addEventListener('click', async () => {
        btn.disabled = true;
        try {
          await send('job:add', { video, qualityId: choice.quality, format: state.settings.format });
          toast('已开始下载');
          setTimeout(() => switchView('jobs'), 350);
        } catch (e) {
          toast(e.message, true);
        } finally {
          btn.disabled = false;
        }
      });

      list.appendChild(node);
    });
    const cur = state.videos[state.expanded];
    setAmbient(cur && cur.cover);
    applyTint(cur && cur.cover);
    syncDownloadButtons();
  }

  function syncDownloadButtons() {
    $$('#video-list .card').forEach((card) => {
      const btn = $('.download', card);
      if (!btn) return;
      const active = state.jobs.find((j) => j.guid === card.dataset.guid && ['queued', 'preparing', 'downloading', 'saving'].includes(j.status));
      const label = $('span', btn);
      if (active) {
        const pct = active.total ? Math.floor((active.done / active.total) * 100) : 0;
        label.textContent = active.status === 'downloading' ? `正在下载 ${pct}%` : active.status === 'queued' ? '等待中' : '正在处理';
        btn.classList.add('busy');
        btn.style.setProperty('--p', `${active.status === 'downloading' ? pct : 0}%`);
      } else {
        label.textContent = '下载';
        btn.classList.remove('busy');
        btn.style.removeProperty('--p');
      }
    });
  }

  async function detect() {
    renderPageState('loading');
    $('#video-list').innerHTML = '';
    $('#playlist-slot').innerHTML = '';
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab) throw new Error('无法读取当前标签页');
      const plPromise = send('page:playlist', { tabId: tab.id }).catch(() => null);
      const res = await send('page:detect', { tabId: tab.id });
      plPromise.then((pl) => {
        if (!pl || !pl.items || pl.items.length < 2) return;
        state.playlist = pl;
        // 没有识别到单个视频（如栏目首页）时，用合集卡片替换空状态
        if (!state.videos.length) renderPageState('none');
        renderPlaylistCard();
      });
      state.videos = [];
      if (res.live) { renderPageState('none'); renderLive(res.live); return; }
      if (!res.supported) {
        renderPageState('empty', {
          title: '没有可下载的视频',
          text: '打开一个央视网视频页面，或在下方粘贴视频链接。',
        });
        return;
      }
      if (!res.videos.length) {
        renderPageState('empty', {
          icon: 'play',
          title: '未找到视频',
          text: res.errors && res.errors.length ? res.errors[0].error : '播放页面中的视频后再试一次。',
        });
        return;
      }
      state.videos = res.videos;
      state.expanded = 0;
      renderPageState('none');
      renderVideos();
    } catch (e) {
      renderPageState('empty', { icon: 'x', title: '无法读取此页面', text: e.message });
    }
  }

  // ---------- 直播 / 回看 ----------
  const pad2 = (n) => String(n).padStart(2, '0');
  const hm = (ms) => { const d = new Date(ms); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`; };
  const ymdOf = (d) => `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`;

  // ---------- 录制时长：时间轴规划 ----------
  // 时间轴是分段线性的「近密远疏」刻度：前半段 0–1 小时（精确到分钟），再到 3 小时，最后 20% 到 6 小时。
  const REC = { MIN: 5, MAX: 360, KNOTS: [[0, 0], [0.5, 60], [0.8, 180], [1, 360]], MBPS: 1.85 };
  const f2m = (f) => { const K = REC.KNOTS; for (let i = 1; i < K.length; i++) if (f <= K[i][0]) { const [f0, m0] = K[i - 1], [f1, m1] = K[i]; return m0 + ((f - f0) / (f1 - f0)) * (m1 - m0); } return REC.MAX; };
  const m2f = (m) => { const K = REC.KNOTS; for (let i = 1; i < K.length; i++) if (m <= K[i][1]) { const [f0, m0] = K[i - 1], [f1, m1] = K[i]; return f0 + ((m - m0) / (m1 - m0)) * (f1 - f0); } return 1; };
  const fmtSpan = (min) => {
    min = Math.max(0, Math.round(min));
    const h = Math.floor(min / 60), m = min % 60;
    if (!h) return `<b>${m}</b><small>分钟</small>`;
    return m ? `<b>${h}</b><small>小时</small><b>${m}</b><small>分</small>` : `<b>${h}</b><small>小时</small>`;
  };
  const fmtSize = (min) => { const mb = (min * 60 * REC.MBPS) / 8; return mb >= 1000 ? `${(mb / 1024).toFixed(1)} GB` : `${Math.max(10, Math.round(mb / 10) * 10)} MB`; };
  /** 计划 → 结束时间：按节目则取节目结束时刻，按时长则从「现在」起算 */
  const planUntil = (plan) => plan.prog ? plan.prog.end : Date.now() + plan.min * 60000;
  const planMinutes = (plan) => (planUntil(plan) - Date.now()) / 60000;

  function upcoming(programs) {
    const now = Date.now(), lim = now + REC.MAX * 60000;
    return (programs || []).filter((p) => p.end > now && p.start < lim).sort((a, b) => a.start - b.start);
  }

  function defaultPlan(programs) {
    const up = upcoming(programs);
    const cur = up.find((p) => p.start <= Date.now());
    const pick = [cur, up[1]].find((p) => p && (p.end - Date.now()) / 60000 >= REC.MIN);
    return pick ? { prog: pick } : { min: 60 };
  }

  function renderLive(info) {
    const savedMode = localStorage.getItem('rec.mode') || 'timed';
    state.live = { ...info, day: 0, recMode: savedMode, plan: defaultPlan(info.programs) };
    const list = $('#video-list');
    list.innerHTML = '';
    const card = document.createElement('article');
    card.className = 'card live glass sheen';
    list.appendChild(card);
    const replay = document.createElement('article');
    replay.className = 'card epg glass';
    if (!info.noEpg) list.appendChild(replay);
    paintLiveCard(card);
    if (!info.noEpg) paintEpg(replay);
    extendTimeline(card);
  }

  /** 6 小时内跨过零点时，补上第二天的节目单，让时间轴的节目分段连续 */
  async function extendTimeline(card) {
    const L = state.live;
    const end = new Date(Date.now() + REC.MAX * 60000);
    if (L.noEpg || end.getDate() === new Date().getDate()) return;
    try {
      const more = (await send('live:epg', { channel: L.channel, date: ymdOf(end) })).programs || [];
      const seen = new Set((L.programs || []).map((p) => p.start));
      L.programs = [...(L.programs || []), ...more.filter((p) => !seen.has(p.start))];
      if (state.live === L && card.isConnected) paintTimeline(card);
    } catch (_) {}
  }

  function paintLiveCard(card) {
    const L = state.live;
    const options = L.mode === 'epg'
      ? `<label class="ch-select"><span>频道</span><select id="ch-pick">${L.channels.map((c) => `<option value="${c.id}" ${c.id === L.channel ? 'selected' : ''}>${escapeHtml(c.name)}</option>`).join('')}</select></label>`
      : '';
    const current = (L.programs || []).find((p) => p.start <= Date.now() && p.end > Date.now());
    card.innerHTML = `
      <div class="live-hero">
        <div class="live-badge"><span class="live-dot"></span>直播</div>
        <h3 class="title">${escapeHtml(L.channelName)}</h3>
        <p class="live-now">${current ? `正在播出 · ${escapeHtml(current.title)} <span class="dim">${hm(current.start)}–${hm(current.end)}</span>` : (L.now ? `正在播出 · ${escapeHtml(L.now)}` : '实时直播')}</p>
        ${options}
      </div>
      <div class="card-body">
        <div class="rec-head">
          <span class="field-label">录制</span>
          <div class="segmented mini rec-mode" id="rec-mode">
            <span class="seg-thumb"></span>
            <button class="seg" data-value="timed">定时结束</button>
            <button class="seg" data-value="manual">直到停止</button>
          </div>
        </div>
        <div class="rec-fold ${L.recMode === 'timed' ? 'open' : ''}">
          <div class="rec-fold-in">
            <div class="rec-readout">
              <div class="rr-dur" id="rr-dur"></div>
              <div class="rr-end"><span id="rr-end"></span><small>结束</small></div>
            </div>
            <p class="rr-ctx" id="rr-ctx"></p>
            <div class="tl" id="tl">
              <div class="tl-track">
                <div class="tl-layer base"></div>
                <div class="tl-layer fill"></div>
              </div>
              <div class="tl-thumb"></div>
              <input class="tl-input" type="range" min="0" max="1000" step="1" aria-label="录制时长">
            </div>
            <div class="tl-scale"><span style="left:0">现在</span><span style="left:25%">30 分</span><span style="left:50%">1 时</span><span style="left:65%">2 时</span><span style="left:80%">3 时</span><span style="left:100%">6 时</span></div>
            <div class="rec-quick" id="rec-quick"></div>
          </div>
        </div>
        <div class="rec-fold ${L.recMode === 'manual' ? 'open' : ''}">
          <div class="rec-fold-in"><p class="rec-manual">一直录制，直到你在下载列表里停止。停止后已录内容会自动保存。</p></div>
        </div>
        <button class="primary-btn rec-btn" id="rec-go"><span class="rec-icon"></span><span id="rec-go-t">开始录制</span></button>
        <p class="hint">以原始画质保存为 MP4。关闭弹出页不影响录制。</p>
      </div>`;
    const seg = $('#rec-mode', card);
    setSegment(seg, L.recMode);
    $$('.seg', seg).forEach((b) => b.addEventListener('click', () => {
      L.recMode = b.dataset.value;
      localStorage.setItem('rec.mode', L.recMode);
      setSegment(seg, L.recMode);
      const folds = $$('.rec-fold', card);
      folds[0].classList.toggle('open', L.recMode === 'timed');
      folds[1].classList.toggle('open', L.recMode === 'manual');
      paintReadout(card);
    }));
    const pick = $('#ch-pick', card);
    if (pick) pick.addEventListener('change', async () => {
      const ch = L.channels.find((c) => c.id === pick.value);
      try {
        const info = await send('live:info', { channel: pick.value });
        Object.assign(L, { channel: pick.value, channelName: (ch && ch.name) || info.channelName, programs: info.programs, now: info.now, plan: defaultPlan(info.programs) });
        paintLiveCard(card);
        extendTimeline(card);
        const epg = $('.card.epg'); if (epg) paintEpg(epg);
      } catch (e) { toast(e.message, true); }
    });
    bindTimeline(card);
    paintTimeline(card);
    $('#rec-go', card).addEventListener('click', async () => {
      const timed = L.recMode === 'timed';
      const until = timed ? planUntil(L.plan) : 0;
      if (timed && until - Date.now() < 60000) { L.plan = defaultPlan(L.programs); paintTimeline(card); toast('所选节目已结束，已重新规划', true); return; }
      try {
        await send('live:record', { channel: L.channel, channelName: L.channelName, until, programTitle: timed && L.plan.prog ? L.plan.prog.title : '' });
        toast(timed ? `已开始录制，${hm(until)} 自动结束` : '已开始录制');
        switchView('jobs');
      } catch (e) { toast(e.message, true); }
    });
    clearInterval(state.recTick);
    state.recTick = setInterval(() => { if (card.isConnected && state.live === L) paintTimeline(card); else clearInterval(state.recTick); }, 20000);
  }

  /** 时间轴：节目分段 + 已选区间高亮 + 拖动吸附到节目边界 */
  function paintTimeline(card) {
    const L = state.live;
    const tl = $('#tl', card);
    if (!tl) return;
    const now = Date.now();
    const up = upcoming(L.programs);
    // 节目分段（无节目单时退化为每小时一段）
    const blocks = up.length
      ? up.map((p) => [Math.max(0, (p.start - now) / 60000), Math.min(REC.MAX, (p.end - now) / 60000), p])
      : [0, 60, 120, 180, 240, 300].map((m) => [m, Math.min(REC.MAX, m + 60), null]);
    const html = blocks.map(([s, e]) => `<i style="left:${(m2f(s) * 100).toFixed(3)}%;width:${((m2f(e) - m2f(s)) * 100).toFixed(3)}%"></i>`).join('');
    $('.tl-layer.base', tl).innerHTML = html;
    $('.tl-layer.fill', tl).innerHTML = html;
    L.bounds = up.map((p) => ({ p, m: (p.end - now) / 60000 })).filter((b) => b.m >= REC.MIN && b.m <= REC.MAX);
    // 当前计划过期（节目已结束 / 不足 5 分钟）时重新规划
    if (L.plan.prog && planMinutes(L.plan) < REC.MIN) L.plan = defaultPlan(L.programs);
    placeThumb(card);
    paintQuick(card);
    paintReadout(card);
  }

  function placeThumb(card) {
    const L = state.live;
    const tl = $('#tl', card);
    const f = m2f(Math.min(REC.MAX, Math.max(REC.MIN, planMinutes(L.plan))));
    tl.style.setProperty('--pos', `${(f * 100).toFixed(3)}%`);
    const input = $('.tl-input', tl);
    if (document.activeElement !== input || !tl.classList.contains('dragging')) input.value = String(Math.round(f * 1000));
  }

  /** 结束时刻正在播出的节目 */
  function endsInside(L, t) { return (L.programs || []).find((p) => p.start < t && p.end > t); }

  function bindTimeline(card) {
    const L = state.live;
    const tl = $('#tl', card);
    const input = $('.tl-input', tl);
    let lastSnap = null;
    const onInput = () => {
      const f = +input.value / 1000;
      const width = tl.clientWidth || 300;
      let m = Math.max(REC.MIN, f2m(f));
      // 吸附：离节目结束点 8px 以内就对齐到节目
      let snap = null, best = 8;
      for (const b of L.bounds || []) { const d = Math.abs(m2f(b.m) - f) * width; if (d < best) { best = d; snap = b; } }
      if (snap) L.plan = { prog: snap.p };
      else {
        const g = m <= 60 ? 1 : m <= 180 ? 5 : 10;
        L.plan = { min: Math.min(REC.MAX, Math.max(REC.MIN, Math.round(m / g) * g)) };
      }
      const key = snap ? snap.p.start : null;
      if (key !== lastSnap) { lastSnap = key; if (key !== null) { tl.classList.remove('snap'); void tl.offsetWidth; tl.classList.add('snap'); } }
      placeThumb(card);
      paintQuick(card);
      paintReadout(card);
    };
    input.addEventListener('input', onInput);
    const on = () => tl.classList.add('dragging');
    const off = () => { tl.classList.remove('dragging'); placeThumb(card); };
    input.addEventListener('pointerdown', on);
    input.addEventListener('pointerup', off);
    input.addEventListener('pointercancel', off);
    input.addEventListener('blur', off);
  }

  /** 快捷项：有节目单时给「本节目 / 下一节目」，否则给常用时长 */
  function paintQuick(card) {
    const L = state.live;
    const box = $('#rec-quick', card);
    if (!box) return;
    const now = Date.now();
    const up = upcoming(L.programs).filter((p) => (p.end - now) / 60000 >= REC.MIN);
    let items;
    if (up.length) {
      items = up.slice(0, 2).map((p, i) => ({
        label: p.start <= now ? '录完本节目' : i === 0 ? '录完下一节目' : '再录一个节目',
        sub: `${escapeHtml(p.title)} · ${hm(p.end)}`, plan: { prog: p }, on: !!(L.plan.prog && L.plan.prog.start === p.start),
      }));
      if (items.length && up[0].start <= now && up[1]) items[1].label = '录完下一节目';
    } else {
      items = [30, 60, 120].map((m) => ({ label: m < 60 ? `${m} 分钟` : `${m / 60} 小时`, sub: `至 ${hm(now + m * 60000)}`, plan: { min: m }, on: !L.plan.prog && L.plan.min === m }));
    }
    box.innerHTML = items.map((it, i) => `<button class="rq ${it.on ? 'on' : ''}" data-i="${i}"><span>${it.label}</span><small>${it.sub}</small></button>`).join('');
    $$('.rq', box).forEach((b) => b.addEventListener('click', () => {
      L.plan = items[+b.dataset.i].plan;
      const tl = $('#tl', card);
      tl.classList.add('glide');
      placeThumb(card); paintQuick(card); paintReadout(card);
      setTimeout(() => tl.classList.remove('glide'), 450);
    }));
  }

  function paintReadout(card) {
    const L = state.live;
    const go = $('#rec-go-t', card);
    if (L.recMode !== 'timed') { if (go) go.textContent = '开始录制'; return; }
    const min = planMinutes(L.plan);
    const until = planUntil(L.plan);
    $('#rr-dur', card).innerHTML = fmtSpan(min);
    $('#rr-end', card).textContent = hm(until);
    const at = endsInside(L, until);
    const ctx = L.plan.prog ? `录完《${escapeHtml(L.plan.prog.title)}》` : at ? `结束时正在播《${escapeHtml(at.title)}》` : '按时长结束';
    $('#rr-ctx', card).innerHTML = `${ctx}<span class="sep">·</span>约 ${fmtSize(min)}`;
    const h = Math.floor(Math.round(min) / 60), m = Math.round(min) % 60;
    if (go) go.textContent = `录制 ${h ? `${h} 小时${m ? ` ${m} 分` : ''}` : `${m} 分钟`}`;
  }

  async function paintEpg(card) {
    const L = state.live;
    const days = [];
    for (let i = 0; i < 7; i++) {
      const d = new Date(); d.setDate(d.getDate() - i);
      days.push({ i, label: i === 0 ? '今天' : i === 1 ? '昨天' : `${d.getMonth() + 1}/${d.getDate()}`, key: ymdOf(d) });
    }
    card.innerHTML = `
      <div class="epg-head">
        <h3>回看</h3>
        <p>保存 7 天内播出过的节目</p>
      </div>
      <div class="day-strip">${days.map((d) => `<button class="day ${d.i === L.day ? 'active' : ''}" data-i="${d.i}">${d.label}</button>`).join('')}</div>
      <div class="epg-list"><div class="sk" style="width:80%"></div><div class="sk" style="width:60%"></div></div>`;
    $$('.day', card).forEach((b) => b.addEventListener('click', () => { L.day = +b.dataset.i; paintEpg(card); }));
    let programs = L.programs || [];
    if (L.day !== 0 || !programs.length) {
      try { programs = (await send('live:epg', { channel: L.channel, date: days[L.day].key })).programs || []; }
      catch (e) { $('.epg-list', card).innerHTML = `<p class="hint">${escapeHtml(e.message)}</p>`; return; }
    }
    const now = Date.now();
    const box = $('.epg-list', card);
    if (!programs.length) { box.innerHTML = '<p class="hint">这一天没有节目单。</p>'; return; }
    box.innerHTML = programs.map((p, k) => {
      const live = p.start <= now && p.end > now;
      const future = p.start > now;
      const btn = future ? '<span class="tag">未播</span>'
        : live ? '<span class="tag on">直播中</span>'
        : `<button class="mini-btn" data-k="${k}">${icon('down')}保存</button>`;
      return `<div class="epg-row ${live ? 'now' : ''} ${future ? 'future' : ''}"><time>${hm(p.start)}</time><span class="t" title="${escapeHtml(p.title)}">${escapeHtml(p.title)}</span>${btn}</div>`;
    }).join('');
    const nowRow = $('.epg-row.now', box);
    if (nowRow) requestAnimationFrame(() => { box.scrollTop = Math.max(0, nowRow.offsetTop - box.clientHeight / 2); });
    $$('.mini-btn', box).forEach((b) => b.addEventListener('click', async () => {
      const p = programs[+b.dataset.k];
      b.disabled = true;
      try {
        await send('live:replay', { channel: L.channel, channelName: L.channelName, start: p.start, end: p.end, title: p.title });
        b.innerHTML = `${icon('check')}已加入`;
        b.classList.add('done');
        toast(`已加入下载：${p.title}`);
      } catch (e) { b.disabled = false; toast(e.message, true); }
    }));
  }

  // ---------- 链接解析 ----------
  $('#omnibox').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const input = $('#omni-input').value.trim();
    if (!input) return;
    const go = $('.omni-go');
    go.classList.add('loading');
    go.innerHTML = icon('retry');
    try {
      const res = await send('video:resolve', { input });
      if (res.live) { renderPageState('none'); $('#playlist-slot').innerHTML = ''; renderLive(res.live); switchView('page'); $('#omni-input').value = ''; return; }
      state.videos = res.videos;
      state.expanded = 0;
      renderPageState('none');
      renderVideos();
      switchView('page');
      $('#omni-input').value = '';
    } catch (e) {
      toast(e.message, true);
    } finally {
      go.classList.remove('loading');
      go.innerHTML = icon('arrow');
    }
  });

  // ---------- 任务列表 ----------
  const STATUS_TEXT = {
    queued: '等待中',
    preparing: '准备中',
    recording: '录制中',
    saving: '正在保存',
    cancelled: '已取消',
  };

  function jobMeta(j) {
    const q = j.quality.name;
    switch (j.status) {
      case 'recording': {
        const gap = j.gaps ? ` · <span class="warn">${icon('warn')}缺 ${j.gaps} 段</span>` : '';
        const left = j.until ? ` · 至 ${hm(j.until)}` : ' · 直到停止';
        return `<span class="rec-dot"></span>已录 ${fmtDuration(j.recorded)} · ${fmtBytes(j.bytes)}${left}${gap}`;
      }
      case 'downloading': {
        const pct = j.total ? Math.floor((j.done / j.total) * 100) : 0;
        const warn = j.check && ['format', 'decode'].includes(j.check.level) ? `<span class="warn">${icon('warn')}自检异常</span>` : '';
        return [warn, `${pct}%`, `${fmtBytes(j.speed)}/s`, fmtEta(j.eta)].filter(Boolean).join(' · ');
      }
      case 'completed':
        if (j.check && ['format', 'decode', 'suspect'].includes(j.check.level)) {
          return `<span class="warn" title="${escapeHtml(j.check.message)}">${icon('warn')}画面可能异常</span> · ${q} · ${fmtBytes(j.size)}`;
        }
        return `<span class="ok">已完成</span> · ${q} · ${fmtBytes(j.size)}`;
      case 'failed':
        return `<span class="err">${escapeHtml(j.error || '下载失败')}</span>`;
      default:
        return `${STATUS_TEXT[j.status] || j.status} · ${q}`;
    }
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  const jobNodes = new Map();

  function renderJobs() {
    const list = $('#job-list');
    const active = state.jobs.filter((j) => ['queued', 'preparing', 'downloading', 'recording', 'saving'].includes(j.status)).length;
    const count = $('#job-count');
    count.hidden = !active;
    count.textContent = active;
    $('#jobs-footer').hidden = !state.jobs.some((j) => ['completed', 'failed', 'cancelled'].includes(j.status));

    if (!state.jobs.length) {
      jobNodes.clear();
      list.innerHTML = `
        <div class="empty">
          <div class="empty-art glass">${icon('down')}</div>
          <h2>没有下载</h2>
          <p>你下载的视频会显示在这里。</p>
        </div>`;
      return;
    }
    if (list.querySelector('.empty')) list.innerHTML = '';

    const seen = new Set();
    state.jobs.forEach((j, i) => {
      seen.add(j.id);
      let node = jobNodes.get(j.id);
      if (!node) {
        node = $('#tpl-job').content.firstElementChild.cloneNode(true);
        const img = $('img', node);
        if (j.cover) img.src = j.cover;
        else if (j.kind) { img.remove(); $('.job-thumb', node).classList.add(j.kind === 'live' ? 'thumb-live' : 'thumb-replay'); $('.job-thumb', node).innerHTML = icon(j.kind === 'live' ? 'tv' : 'retry'); }
        else img.remove();
        $('.job-title', node).textContent = j.title;
        $('.job-title', node).title = j.title;
        jobNodes.set(j.id, node);
      }
      node.className = `job glass sheen ${j.status}`;
      if (j.tint && !node.dataset.tint) {
        node.dataset.tint = '1';
        node.style.setProperty('--tint', CoverColor.css(j.tint));
        node.style.setProperty('--tint-glow', CoverColor.css(j.tint, 0.45));
      }
      if (j.status === 'downloading' && j.check && j.check.level && !['ok', 'pending'].includes(j.check.level)) {
        $('.job-meta', node).title = j.check.message;
      }
      $('.job-meta', node).innerHTML = jobMeta(j);
      let pct = j.total ? (j.done / j.total) * 100 : 0;
      if (j.status === 'recording') {
        pct = j.until && j.recordStartedAt ? Math.min(100, ((Date.now() - j.recordStartedAt) / (j.until - j.recordStartedAt)) * 100) : 100;
        node.classList.toggle('open-ended', !j.until);
      }
      $('.progress span', node).style.width = `${pct}%`;

      const actions = $('.job-actions', node);
      const key = `${j.status}`;
      if (actions.dataset.key !== key) {
        actions.dataset.key = key;
        actions.innerHTML = '';
        const add = (ico, title, fn) => {
          const b = document.createElement('button');
          b.className = 'icon-btn';
          b.title = title;
          b.innerHTML = icon(ico);
          b.addEventListener('click', fn);
          actions.appendChild(b);
        };
        if (j.status === 'recording') {
          if (j.until) add('plus', '延长 30 分钟', async () => {
            try { const r = await send('job:extend', { id: j.id, minutes: 30 }); toast(`已延长，${hm(r.until)} 结束`); } catch (e) { toast(e.message, true); }
          });
          add('stop', '停止录制并保存', () => { send('job:stop', { id: j.id }); toast('正在停止录制，已录内容会保存'); });
          add('x', '取消录制（不保存）', () => send('job:cancel', { id: j.id }));
        } else if (['queued', 'preparing', 'downloading', 'saving'].includes(j.status)) {
          add('x', '取消', () => send('job:cancel', { id: j.id }));
        } else if (j.status === 'completed') {
          add('play', '播放', () => playDownload(j));
          add('folder', '在文件夹中显示', () => send('download:show', { downloadId: j.downloadId }));
        } else {
          if (!j.unresolved) add('retry', '重试', () => send('job:retry', { id: j.id }));
          add('x', '移除', () => send('job:remove', { id: j.id }));
        }
      }
      if (list.children[i] !== node) list.insertBefore(node, list.children[i] || null);
    });
    for (const [id, node] of jobNodes) {
      if (!seen.has(id)) { node.remove(); jobNodes.delete(id); }
    }
  }

  $('#btn-clear').addEventListener('click', () => send('jobs:clear'));

  // ---------- 设置 ----------
  /**
   * 用系统默认播放器打开已下载的视频。
   * chrome.downloads.open 必须在用户手势内直接调用（经由后台转发会丢失手势，只能退化为"在文件夹中显示"），
   * 因此这里在弹出页的点击事件中直接调用，并需要 downloads.open 权限。
   */
  function playDownload(j) {
    // 先同步调用，确保仍处于点击手势内
    const fail = async () => {
      const [item] = await chrome.downloads.search({ id: j.downloadId }).catch(() => []);
      toast(!item || item.exists === false ? '文件已被移动或删除' : '无法打开此文件', true);
    };
    try {
      const r = chrome.downloads.open(j.downloadId);
      if (r && r.catch) r.catch(fail);
    } catch (_) { fail(); }
  }

  function openSheet(open) {
    const sheet = $('#sheet');
    const backdrop = $('#sheet-backdrop');
    const app = $('#app');
    if (open) {
      backdrop.hidden = false;
      sheet.inert = false;
      app.inert = true;
      requestAnimationFrame(() => { backdrop.classList.add('show'); sheet.classList.add('show'); fillSettings(); });
      setTimeout(() => $('#btn-sheet-done').focus({ preventScroll: true }), 60);
    } else {
      // 先把焦点移出面板再设为 inert，避免"焦点位于隐藏元素内"的无障碍警告
      app.inert = false;
      $('#btn-settings').focus({ preventScroll: true });
      sheet.inert = true;
      backdrop.classList.remove('show');
      sheet.classList.remove('show');
      setTimeout(() => { backdrop.hidden = true; }, 350);
    }
  }

  // ---------- 外观 ----------
  const THEMES = ['auto', 'light', 'dark'];
  const THEME_ICON = { auto: 'auto', light: 'sun', dark: 'moon' };
  const THEME_NAME = { auto: '自动', light: '浅色', dark: '深色' };
  function applyTheme(theme, persist) {
    if (!THEMES.includes(theme)) theme = 'auto';
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('theme', theme); } catch (_) {}
    const btn = $('#btn-theme');
    btn.innerHTML = icon(THEME_ICON[theme]);
    btn.title = `外观：${THEME_NAME[theme]}`;
    state.settings.theme = theme;
    setSegment($('#set-theme'), theme);
    if (persist) saveSettings({ theme });
  }
  $('#btn-theme').addEventListener('click', () => {
    const next = THEMES[(THEMES.indexOf(state.settings.theme || 'auto') + 1) % THEMES.length];
    applyTheme(next, true);
    toast(`外观：${THEME_NAME[next]}`);
  });
  $$('#set-theme .seg').forEach((b) => b.addEventListener('click', () => applyTheme(b.dataset.value, true)));

  function setAmbient(url) {
    const img = $('#ambient-img');
    if (!url) { img.classList.remove('on'); return; }
    if (img.getAttribute('src') === url) return;
    img.classList.remove('on');
    img.onload = () => img.classList.add('on');
    img.src = url;
  }

  function fillSettings() {
    const s = state.settings;
    setSegment($('#set-theme'), s.theme || 'auto');
    $$('.stepper').forEach((st) => { $('output', st).textContent = s[st.dataset.key]; });
    $('#set-folder').value = s.folder || '';
    $('#set-saveas').checked = !!s.saveAs;
    $('#set-notify').checked = s.notify !== false;
  }

  async function saveSettings(patch) {
    try {
      const res = await send('settings:set', { patch });
      state.settings = res.settings;
      fillSettings();
    } catch (e) { toast(e.message, true); }
  }

  $('#btn-settings').addEventListener('click', () => openSheet(true));
  $('#btn-sheet-done').addEventListener('click', () => openSheet(false));
  $('#sheet-backdrop').addEventListener('click', () => {
    if ($('#pl-sheet').classList.contains('show')) openPlaylist(false); else openSheet(false);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if ($('#pl-sheet').classList.contains('show')) { e.preventDefault(); openPlaylist(false); }
    else if ($('#sheet').classList.contains('show')) { e.preventDefault(); openSheet(false); }
  });
  $$('.stepper').forEach((st) => {
    $$('button', st).forEach((b) => b.addEventListener('click', () => {
      const key = st.dataset.key;
      const v = Math.min(+st.dataset.max, Math.max(+st.dataset.min, (+state.settings[key] || 0) + +b.dataset.step));
      state.settings[key] = v;
      $('output', st).textContent = v;
      saveSettings({ [key]: v });
    }));
  });
  $('#set-folder').addEventListener('change', (e) => saveSettings({ folder: e.target.value.trim() }));
  $('#set-saveas').addEventListener('change', (e) => saveSettings({ saveAs: e.target.checked }));
  $('#set-notify').addEventListener('change', (e) => saveSettings({ notify: e.target.checked }));

  // ---------- 封面主色 ----------
  let tintToken = 0;
  async function applyTint(url) {
    const token = ++tintToken;
    const root = document.documentElement;
    const c = url ? await CoverColor.fromUrl(url) : null;
    if (token !== tintToken) return;
    if (!c) { ['--tint', '--tint-glow', '--orb-a', '--orb-c'].forEach((k) => root.style.removeProperty(k)); return; }
    const dark = matchMedia('(prefers-color-scheme: dark)').matches && root.dataset.theme !== 'light' || root.dataset.theme === 'dark';
    root.style.setProperty('--tint', CoverColor.css(c));
    root.style.setProperty('--tint-glow', CoverColor.css(c, 0.45));
    root.style.setProperty('--orb-a', CoverColor.css(c, dark ? 0.42 : 0.5, dark ? -8 : 10));
    root.style.setProperty('--orb-c', CoverColor.css({ ...c, h: (c.h + 28) % 360 }, dark ? 0.36 : 0.42, dark ? -10 : 14));
  }

  // ---------- 玻璃反光：高光跟随指针 ----------
  document.addEventListener('pointermove', (e) => {
    const el = e.target.closest && e.target.closest('.sheen');
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty('--mx', `${e.clientX - r.left}px`);
    el.style.setProperty('--my', `${e.clientY - r.top}px`);
  }, { passive: true });

  // ---------- 合集 / 栏目 ----------
  const PL_LABEL = { column: '本栏目', album: '本剧集', vplist: '本栏目', page: '本页视频' };

  function renderPlaylistCard() {
    const pl = state.playlist;
    const slot = $('#playlist-slot');
    if (!pl) { slot.innerHTML = ''; return; }
    const covers = pl.items.filter((x) => x.cover).slice(0, 3);
    const more = pl.total > pl.items.length ? `，显示最近 ${pl.items.length} 个` : '';
    slot.innerHTML = `
      <button class="pl-card glass sheen" id="pl-open">
        <span class="pl-stack">${covers.map((c, i) => `<img src="${escapeHtml(c.cover)}" alt="" style="--i:${i}">`).join('') || icon('stack')}</span>
        <span class="pl-text">
          <b>${escapeHtml(pl.name || PL_LABEL[pl.source] || '合集')} · ${pl.total} ${pl.source === 'album' ? '集' : '个视频'}</b>
          <small>勾选多集，一起加入下载${more}</small>
        </span>
        <span class="pl-chev">${icon('chev')}</span>
      </button>`;
    $('#pl-open').addEventListener('click', () => openPlaylist(true));
  }

  function renderPlaylistList() {
    const list = $('#pl-list');
    const pl = state.playlist;
    list.innerHTML = '';
    const frag = document.createDocumentFragment();
    pl.items.forEach((it, i) => {
      const row = document.createElement('div');
      row.className = 'pl-row' + (state.plSelected.has(i) ? ' on' : '');
      row.setAttribute('role', 'option');
      row.setAttribute('aria-selected', String(state.plSelected.has(i)));
      row.tabIndex = 0;
      const meta = [it.date ? fmtDate(it.date) : '', it.duration ? fmtDuration(it.duration) : ''].filter(Boolean).join(' · ');
      row.innerHTML = `
        <span class="pl-thumb">${it.cover ? `<img src="${escapeHtml(it.cover)}" alt="" loading="lazy">` : ''}</span>
        <span class="pl-info"><b>${escapeHtml(it.title)}</b>${meta ? `<small>${meta}</small>` : ''}</span>
        <span class="pl-check">${icon('check')}</span>`;
      const toggle = () => {
        if (state.plSelected.has(i)) state.plSelected.delete(i); else state.plSelected.add(i);
        row.classList.toggle('on', state.plSelected.has(i));
        row.setAttribute('aria-selected', String(state.plSelected.has(i)));
        syncPlaylistFooter();
      };
      row.addEventListener('click', toggle);
      row.addEventListener('keydown', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); toggle(); } });
      frag.appendChild(row);
    });
    list.appendChild(frag);
    syncPlaylistFooter();
  }

  function syncPlaylistFooter() {
    const n = state.plSelected.size;
    const total = state.playlist ? state.playlist.items.length : 0;
    $('#pl-go').disabled = !n;
    $('#pl-go span').textContent = n ? `下载 ${n} 个视频` : '选择要下载的视频';
    $('#pl-all').textContent = n === total ? '取消全选' : '全选';
    $('#pl-sub').textContent = `${state.playlist.name || PL_LABEL[state.playlist.source] || '合集'} · 已选 ${n} / ${total}`;
  }

  function openPlaylist(open) {
    const sheet = $('#pl-sheet');
    const backdrop = $('#sheet-backdrop');
    const app = $('#app');
    if (open) {
      renderPlaylistList();
      setSegment($('#pl-quality'), state.plPref);
      backdrop.hidden = false;
      sheet.inert = false;
      app.inert = true;
      requestAnimationFrame(() => { backdrop.classList.add('show'); sheet.classList.add('show'); setSegment($('#pl-quality'), state.plPref); });
      setTimeout(() => $('#pl-close').focus({ preventScroll: true }), 60);
    } else {
      app.inert = false;
      const opener = $('#pl-open');
      if (opener) opener.focus({ preventScroll: true });
      sheet.inert = true;
      backdrop.classList.remove('show');
      sheet.classList.remove('show');
      setTimeout(() => { if (!$('#sheet').classList.contains('show')) backdrop.hidden = true; }, 350);
    }
  }

  $('#pl-close').addEventListener('click', () => openPlaylist(false));
  $('#pl-all').addEventListener('click', () => {
    const total = state.playlist.items.length;
    if (state.plSelected.size === total) state.plSelected.clear();
    else state.playlist.items.forEach((_, i) => state.plSelected.add(i));
    renderPlaylistList();
  });
  $$('#pl-quality .seg').forEach((b) => b.addEventListener('click', () => {
    state.plPref = b.dataset.value;
    setSegment($('#pl-quality'), state.plPref);
  }));
  $('#pl-go').addEventListener('click', async () => {
    const items = [...state.plSelected].sort((a, b) => a - b).map((i) => state.playlist.items[i]);
    if (!items.length) return;
    $('#pl-go').disabled = true;
    try {
      const res = await send('playlist:enqueue', { items, pref: state.plPref, format: state.settings.format });
      toast(`已加入 ${res.accepted} 个视频`);
      state.plSelected.clear();
      openPlaylist(false);
      setTimeout(() => switchView('jobs'), 350);
    } catch (e) {
      toast(e.message, true);
      $('#pl-go').disabled = false;
    }
  });

  // ---------- 启动 ----------
  (async () => {
    setSegment($('.tabs'), 'page');
    applyTheme(document.documentElement.dataset.theme || 'auto', false);
    try {
      const { settings } = await send('settings:get');
      state.settings = { ...state.settings, ...settings };
      applyTheme(state.settings.theme || document.documentElement.dataset.theme, false);
      fillSettings();
    } catch (_) {}
    const { jobs } = await send('jobs:list').catch(() => ({ jobs: [] }));
    state.jobs = jobs;
    renderJobs();
    if (jobs.some((j) => ['queued', 'preparing', 'downloading', 'saving'].includes(j.status))) switchView('jobs');
    detect();
  })();
})();
