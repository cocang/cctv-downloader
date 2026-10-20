/**
 * 工具栏进度环 + 系统通知（运行于 Service Worker）
 */
(function (global) {
  'use strict';

  const ACTIVE = ['queued', 'preparing', 'downloading', 'recording', 'saving'];
  const ICON_PATH = 'assets/icons/icon128.png';

  // ---------------------------------------------------------------------------
  // 工具栏进度环：图标缩小居中，外圈画进度
  // ---------------------------------------------------------------------------
  class ToolbarProgress {
    constructor() {
      this.base = null;
      this.last = '';
      this.timer = 0;
      this.pending = null;
      this.flashUntil = 0;
    }

    async bitmap() {
      if (!this.base) {
        this.base = fetch(chrome.runtime.getURL(ICON_PATH)).then((r) => r.blob()).then((b) => createImageBitmap(b)).catch(() => null);
      }
      return this.base;
    }

    draw(size, bmp, frac, color, done) {
      const c = new OffscreenCanvas(size, size);
      const g = c.getContext('2d');
      const lw = Math.max(2, size * 0.12);
      const r = size / 2 - lw / 2;
      const inner = size - lw * 2 - size * 0.08;
      if (bmp) {
        const o = (size - inner) / 2;
        g.save();
        const rr = inner * 0.22;
        g.beginPath();
        g.roundRect(o, o, inner, inner, rr);
        g.clip();
        g.drawImage(bmp, o, o, inner, inner);
        g.restore();
      }
      g.lineCap = 'round';
      g.lineWidth = lw;
      g.strokeStyle = 'rgba(128,128,128,0.35)';
      g.beginPath(); g.arc(size / 2, size / 2, r, 0, Math.PI * 2); g.stroke();
      g.strokeStyle = done ? '#30C85A' : color;
      g.beginPath();
      g.arc(size / 2, size / 2, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * Math.max(0.03, Math.min(1, frac)));
      g.stroke();
      return g.getImageData(0, 0, size, size);
    }

    /** jobs：任务快照 */
    update(jobs) {
      this.pending = jobs;
      if (this.timer) return;
      this.timer = setTimeout(() => { this.timer = 0; this.render(this.pending); }, 300);
    }

    async render(jobs) {
      const active = jobs.filter((j) => ACTIVE.includes(j.status));
      if (!active.length) {
        if (Date.now() < this.flashUntil) return;
        if (this.last !== 'idle') {
          this.last = 'idle';
          chrome.action.setIcon({ path: { 128: ICON_PATH } }).catch(() => {});
          chrome.action.setTitle({ title: 'CCTV 下载器' }).catch(() => {});
        }
        return;
      }
      let done = 0, total = 0;
      for (const j of active) { done += j.done || 0; total += j.total || 0; }
      const recording = active.some((j) => j.status === 'recording');
      const frac = total ? done / total : recording ? 1 : 0;
      const lead = active.find((j) => j.status === 'downloading') || active[0];
      const color = recording && !total ? '#FF453A' : (lead.tint && global.CoverColor) ? global.CoverColor.css(lead.tint) : '#0A84FF';
      const key = `${Math.round(frac * 100)}|${color}`;
      if (key === this.last) return;
      this.last = key;
      const bmp = await this.bitmap();
      chrome.action.setIcon({ imageData: { 16: this.draw(16, bmp, frac, color), 32: this.draw(32, bmp, frac, color) } }).catch(() => {});
      chrome.action.setTitle({ title: recording && !total ? `正在录制直播` : `正在下载 ${active.length} 个视频 · ${Math.floor(frac * 100)}%` }).catch(() => {});
    }

    /** 完成时短暂显示满环（绿色） */
    async flashDone() {
      this.flashUntil = Date.now() + 2500;
      this.last = 'done';
      const bmp = await this.bitmap();
      chrome.action.setIcon({ imageData: { 16: this.draw(16, bmp, 1, '', true), 32: this.draw(32, bmp, 1, '', true) } }).catch(() => {});
      setTimeout(() => { this.flashUntil = 0; this.last = ''; this.render(this.pending || []); }, 2600);
    }
  }

  // ---------------------------------------------------------------------------
  // 系统通知
  // ---------------------------------------------------------------------------
  async function coverDataUrl(url, w, h) {
    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 4000);
      const blob = await (await fetch(url, { credentials: 'omit', signal: ctrl.signal })).blob();
      clearTimeout(timer);
      const bmp = await createImageBitmap(blob);
      const c = new OffscreenCanvas(w, h);
      const g = c.getContext('2d');
      // 居中裁切填满
      const s = Math.max(w / bmp.width, h / bmp.height);
      const dw = bmp.width * s, dh = bmp.height * s;
      g.drawImage(bmp, (w - dw) / 2, (h - dh) / 2, dw, dh);
      const out = await c.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
      const bytes = new Uint8Array(await out.arrayBuffer());
      let bin = '';
      for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      return `data:image/jpeg;base64,${btoa(bin)}`;
    } catch (_) {
      return '';
    }
  }

  class Notifier {
    constructor(manager) {
      this.manager = manager;
      this.prev = null;
      this.map = new Map(); // notificationId -> downloadId
      this.platform = chrome.runtime.getPlatformInfo().then((p) => p.os).catch(() => '');
      if (!chrome.notifications) return;
      chrome.notifications.onClicked.addListener((id) => this.open(id, 'play'));
      chrome.notifications.onButtonClicked.addListener((id, idx) => this.open(id, idx === 0 ? 'play' : 'folder'));
      chrome.notifications.onClosed.addListener((id) => this.map.delete(id));
    }

    open(id, action) {
      const downloadId = this.map.get(id);
      chrome.notifications.clear(id);
      if (downloadId == null) return;
      if (action === 'folder') { chrome.downloads.show(downloadId); return; }
      // 通知点击属于用户手势，可直接用系统播放器打开；失败时退回到在文件夹中显示
      try {
        const r = chrome.downloads.open(downloadId);
        if (r && r.catch) r.catch(() => chrome.downloads.show(downloadId));
      } catch (_) { chrome.downloads.show(downloadId); }
    }

    onJobs(jobs) {
      const statuses = new Map(jobs.map((j) => [j.id, j.status]));
      if (!this.prev) { this.prev = statuses; return; } // 启动时不补发历史通知
      for (const j of jobs) {
        const before = this.prev.get(j.id);
        if (before === j.status || !before) continue;
        if (j.status === 'completed') { global.toolbarProgress && global.toolbarProgress.flashDone(); this.notify(j); }
        else if (j.status === 'failed') this.notify(j);
      }
      this.prev = statuses;
    }

    async notify(j) {
      if (!chrome.notifications || this.manager.settings.notify === false) return;
      const os = await this.platform;
      const ok = j.status === 'completed';
      const warn = ok && j.check && ['format', 'decode', 'suspect'].includes(j.check.level);
      const title = !ok ? '下载失败' : warn ? '下载完成，但画面可能有问题' : '下载完成';
      const sizeText = j.size ? ` · ${(j.size / 1048576).toFixed(1)} MB` : '';
      const message = j.title;
      const context = !ok ? (j.error || '') : warn ? j.check.message : `${j.quality.name} · ${j.format.toUpperCase()}${sizeText}`;
      const appIcon = chrome.runtime.getURL(ICON_PATH);
      let opts;
      if (os === 'mac' || !j.cover) {
        const icon = j.cover ? await coverDataUrl(j.cover, 160, 160) : '';
        opts = { type: 'basic', iconUrl: icon || appIcon, title, message, contextMessage: context };
      } else {
        const image = await coverDataUrl(j.cover, 360, 202);
        opts = image
          ? { type: 'image', iconUrl: appIcon, imageUrl: image, title, message, contextMessage: context }
          : { type: 'basic', iconUrl: appIcon, title, message, contextMessage: context };
      }
      if (ok && j.downloadId != null) opts.buttons = [{ title: '播放' }, { title: '在文件夹中显示' }];
      const id = `job-${j.id}`;
      if (ok && j.downloadId != null) this.map.set(id, j.downloadId);
      await this.create(id, opts);
    }

    /**
     * 创建通知；失败时逐级降级（去掉图片 → 去掉按钮），并返回最终结果。
     * Windows / macOS 使用系统通知中心，若系统层面关闭了 Chrome 的通知，create 仍会"成功"但不显示，
     * 这种情况只能由用户在系统设置中开启。
     */
    async create(id, opts) {
      const attempt = (o) => new Promise((resolve) => {
        chrome.notifications.create(id, { priority: 2, ...o }, (nid) => {
          const err = chrome.runtime.lastError;
          resolve(err ? { ok: false, error: err.message } : { ok: true, id: nid });
        });
      });
      const appIcon = chrome.runtime.getURL(ICON_PATH);
      const chain = [
        opts,
        { ...opts, type: 'basic', imageUrl: undefined },
        { type: 'basic', iconUrl: appIcon, title: opts.title, message: opts.message || ' ', contextMessage: opts.contextMessage },
      ];
      let last;
      for (const o of chain) {
        if (o.imageUrl === undefined) delete o.imageUrl;
        last = await attempt(o);
        if (last.ok) return last;
        console.warn('[notify] 创建通知失败，降级重试：', last.error);
      }
      return last;
    }
  }

  global.ToolbarProgress = ToolbarProgress;
  global.Notifier = Notifier;
})(typeof self !== 'undefined' ? self : globalThis);
