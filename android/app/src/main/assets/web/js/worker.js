/**
 * 下载引擎（Web Worker）
 *
 * 相当于扩展里的 Service Worker：视频解析、栏目 / 剧集识别、下载、解密、封装都在这里，
 * 不占用界面线程。界面通过 postMessage 调用下面的 handlers，进度通过 'jobs' 事件推送。
 */
importScripts(
  'env.js',
  '../lib/mux.min.js',
  '../core/h5e-decrypt.js',
  '../core/ts-pipeline.js',
  '../core/mp4-remux.js',
  '../core/cctv-api.js',
  '../core/net.js',
  '../core/verify.js',
  '../core/color.js',
  '../core/playlist.js',
  '../core/cntv-live-module.js',
  '../core/live-crypto.js',
  '../core/live-api.js'
);

// ---------------------------------------------------------------------------
// 持久化：状态由原生侧保存在应用私有目录，启动时随 init 一起传入
// ---------------------------------------------------------------------------
self.Store = (() => {
  let data = {};
  let timer = 0;
  return {
    load(json) { try { data = JSON.parse(json || '{}') || {}; } catch (_) { data = {}; } },
    get() { return data; },
    set(patch) {
      Object.assign(data, patch);
      clearTimeout(timer);
      timer = setTimeout(() => postMessage({ event: 'save', state: JSON.stringify(data) }), 300);
    },
  };
})();

// ---------------------------------------------------------------------------
// 输出文件：边处理边写入系统「下载」目录
// ---------------------------------------------------------------------------
self.NativeFile = (() => {
  const FLUSH = 4 * 1024 * 1024;
  async function call(path, params, body) {
    const res = await fetch(AppEnv.apiUrl(path, params), { method: 'POST', body: body || '' });
    let data = {};
    try { data = await res.json(); } catch (_) {}
    if (!res.ok) throw new Error(data.error || `保存失败（${res.status}）`);
    return data;
  }
  class Writer {
    constructor(id) { this.id = id; this.size = 0; this.buf = []; this.bufLen = 0; this.chain = Promise.resolve(); }
    async write(chunk) {
      if (!chunk || !chunk.length) return;
      this.buf.push(chunk.slice ? chunk.slice() : chunk);
      this.bufLen += chunk.length;
      this.size += chunk.length;
      if (this.bufLen >= FLUSH) await this.flush();
    }
    async flush() {
      if (!this.bufLen) return;
      const out = new Uint8Array(this.bufLen);
      let o = 0;
      for (const b of this.buf) { out.set(b, o); o += b.length; }
      this.buf = []; this.bufLen = 0;
      await call('/f/w', { id: this.id }, out);
    }
    async commit() { await this.flush(); return call('/f/commit', { id: this.id }); }
    async abort() { this.buf = []; this.bufLen = 0; try { await call('/f/abort', { id: this.id }); } catch (_) {} }
  }
  return {
    /** meta：标题、清晰度、剧集等信息，由原生侧按「保存设置」决定位置与文件名 */
    async open({ mime, meta }) {
      const res = await call('/f/open', { mime }, JSON.stringify(meta || {}));
      if (res.exists) return { exists: true, uri: res.uri, path: res.path, name: res.name, size: res.size };
      const w = new Writer(res.id);
      w.target = { path: res.path || '', name: res.name || '' };
      return w;
    },
  };
})();

importScripts('downloader.js');

const native = (fn, ...args) => postMessage({ event: 'native', fn, args });

let manager = null;

// ---------------------------------------------------------------------------
// 通知栏进度（前台服务）与完成通知
// ---------------------------------------------------------------------------
const ACTIVE = ['queued', 'preparing', 'downloading', 'recording', 'saving'];
let lastProgress = '';
self.onJobsProgress = (jobs) => {
  const active = jobs.filter((j) => ACTIVE.includes(j.status));
  let done = 0, total = 0;
  for (const j of active) { total += 1; done += j.total ? j.done / j.total : 0; }
  const recording = active.find((j) => j.status === 'recording');
  const percent = recording && active.length === 1 ? -1 : total ? Math.floor((done / total) * 100) : 0;
  const title = active.length === 1 ? (recording ? `录制中 · ${recording.title}` : active[0].title) : '';
  const key = `${active.length}|${percent}|${title}`;
  if (key === lastProgress) return;
  lastProgress = key;
  native('progress', active.length, percent, title);
};

const lastStatus = new Map();
function watchCompletion(jobs) {
  for (const j of jobs) {
    const prev = lastStatus.get(j.id);
    lastStatus.set(j.id, j.status);
    if (!prev || prev === j.status || !manager.settings.notify) continue;
    if (j.status === 'completed') {
      const size = j.size ? ` · ${(j.size / 1048576).toFixed(j.size > 1073741824 ? 0 : 1)} MB` : '';
      native('notifyDone', j.id, j.title, `${j.existed ? '文件已存在' : '已保存'} · ${j.filename || '下载'}${size}`, j.uri, true);
    } else if (j.status === 'failed' && ACTIVE.includes(prev)) {
      native('notifyDone', j.id, `下载失败：${j.title}`, j.error || '下载失败', '', false);
    }
  }
}

// ---------------------------------------------------------------------------
// 链接解析
// ---------------------------------------------------------------------------
const pageHtml = new Map();

function findUrl(text) {
  const m = String(text || '').match(/https?:\/\/[^\s"'<>，。；、）)\]】]+/i);
  return m ? m[0].replace(/[.,;!?]+$/, '') : '';
}

async function resolveGuids(guids) {
  const list = [];
  const errors = [];
  await Promise.all(guids.slice(0, 12).map(async (g) => {
    try { list.push(await CctvApi.getVideo(g)); }
    catch (e) { errors.push({ guid: g, error: e.message }); }
  }));
  list.sort((a, b) => guids.indexOf(a.guid) - guids.indexOf(b.guid));
  return { list, errors };
}

async function getHtml(url) {
  const c = pageHtml.get(url);
  if (c && Date.now() - c.t < 120000) return c.html;
  const html = await CctvApi.fetchText(url);
  pageHtml.set(url, { html, t: Date.now() });
  if (pageHtml.size > 20) pageHtml.delete(pageHtml.keys().next().value);
  return html;
}

async function enqueuePlaylist(items, pref, series) {
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      const item = items[index];
      try {
        const guid = await Playlist.resolveItemGuid(item);
        const video = await CctvApi.getVideo(guid);
        const q = Playlist.pickQuality(video.qualities, pref);
        if (!q) throw new Error('没有可用的清晰度');
        await manager.add({ video, qualityId: q.id, meta: { series: series || '', episode: item.episode || 0, pageUrl: item.url || '' } });
      } catch (e) {
        manager.addFailed({ title: item.title || '未知视频', cover: item.cover, error: e.message || String(e) });
      }
    }
  };
  await Promise.all([worker(), worker(), worker()]);
}

const SUPPORTED_HOST = /(^|\.)(cctv\.com|cctv\.cn|cntv\.cn|cntv\.com)$/i;

const handlers = {
  /** 解析用户输入（链接、带链接的分享文字或 GUID） */
  async 'link:resolve'({ input }) {
    const text = String(input || '').trim();
    if (!text) throw new Error('请输入视频链接');
    const url = findUrl(text);
    if (url) {
      const live = await LiveApi.detect(url);
      if (live) return { url, videos: [], live: await LiveApi.info(live) };
    }
    if (!url) {
      const guids = await CctvApi.resolveInput(text);
      const { list, errors } = await resolveGuids(guids);
      if (!list.length) throw new Error((errors[0] && errors[0].error) || '没有解析到视频');
      return { url: '', videos: list };
    }
    let host = '';
    try { host = new URL(url).hostname; } catch (_) {}
    if (/yangshipin\.cn$/i.test(host)) throw new Error('暂不支持央视频（yangshipin.cn）链接，请使用央视网（cctv.com）链接');
    if (!SUPPORTED_HOST.test(host)) throw new Error('这不是央视网链接');
    const guids = [];
    const pid = url.match(/[?&](?:pid|guid)=([0-9a-fA-F]{32})/);
    if (pid) guids.push(pid[1].toLowerCase());
    let html = '';
    try {
      html = await getHtml(url);
      for (const g of CctvApi.extractGuidsFromHtml(html)) if (!guids.includes(g)) guids.push(g);
    } catch (e) {
      if (!guids.length) throw new Error(`无法打开此页面：${e.message}`);
    }
    const { list, errors } = await resolveGuids(guids);
    return { url, videos: list, errors, hasPage: !!html };
  },

  /** 识别链接所在页面的栏目 / 剧集 / 合集 */
  async 'link:playlist'({ url }) {
    if (!url) return { source: '', total: 0, items: [] };
    const html = await getHtml(url);
    return Playlist.extract(html, url);
  },

  async 'playlist:enqueue'({ items, pref, series }) {
    enqueuePlaylist(items.slice(0, 300), pref, series).catch((e) => console.error('[playlist]', e));
    return { accepted: Math.min(items.length, 300) };
  },
  async 'job:add'({ video, qualityId, meta }) { const job = await manager.add({ video, qualityId, meta }); return { id: job.id }; },
  /** 原生侧检查文件后回报：文件已被删除或移动 */
  async 'job:missing'({ ids }) { manager.markMissing(ids || []); return {}; },
  async 'job:cancel'({ id }) { manager.cancel(id); return {}; },
  async 'job:stop'({ id }) { manager.stop(id); return {}; },
  async 'job:extend'({ id, minutes }) { return { until: manager.extend(id, +minutes || 30) }; },
  // ---- 直播 / 回看 ----
  async 'live:channels'() { return { channels: LiveApi.channels }; },
  async 'live:epg'({ channel, date }) { return LiveApi.epg(channel, date); },
  async 'live:info'({ channel }) { return LiveApi.info({ mode: 'live', channel }); },
  /** 多个频道的「正在播出」（直播页频道列表用） */
  async 'live:now'({ channels }) {
    const ids = (channels && channels.length ? channels : LiveApi.channels.map((c) => c.id)).join(',');
    const data = JSON.parse(await CctvApi.fetchText(`https://api.cntv.cn/epg/epginfo?serviceId=shiyi&d=${LiveApi.ymd(new Date())}&c=${ids}`));
    const now = Date.now();
    const out = {};
    for (const [id, ch] of Object.entries(data || {})) {
      if (!ch || typeof ch !== 'object') continue;
      const ps = (ch.program || []).map((p) => ({ title: p.t, start: +p.st * 1000, end: +p.et * 1000 }));
      const cur = ps.find((p) => p.start <= now && p.end > now);
      const next = ps.find((p) => p.start > now);
      out[id] = { now: cur || null, next: next || null };
    }
    return { now: out };
  },
  async 'live:record'({ channel, channelName, until, programTitle, pageUrl }) {
    const title = LiveApi.titles.live(channelName || channel, programTitle);
    const job = await manager.addLive({ kind: 'live', channel, channelName, title, until: +until || 0, meta: { series: channelName || '', pageUrl } });
    return { id: job.id };
  },
  async 'live:replay'({ channel, channelName, start, end, title }) {
    const name = LiveApi.titles.replay(channelName || channel, title, start);
    const job = await manager.addLive({ kind: 'replay', channel, channelName, title: name, start: +start, end: +end, meta: { series: channelName || '' } });
    return { id: job.id };
  },
  async 'job:retry'({ id }) { manager.retry(id); return {}; },
  async 'job:remove'({ id }) { manager.remove(id); return {}; },
  async 'jobs:clear'() { manager.clearFinished(); return {}; },
  async 'jobs:list'() { await manager.ready; return { jobs: manager.snapshot() }; },
  async 'settings:get'() { await manager.ready; return { settings: manager.settings }; },
  async 'settings:set'({ patch }) { return { settings: await manager.updateSettings(patch) }; },
  async 'cover:color'({ url }) { return { color: await CoverColor.fromUrl(url) }; },
};

self.onmessage = async (ev) => {
  const msg = ev.data || {};
  if (msg.type === 'init') {
    Store.load(msg.state);
    manager = new DownloadManager();
    manager.ready.then(() => {
      for (const j of manager.snapshot()) lastStatus.set(j.id, j.status);
      manager.persist();
      postMessage({ event: 'jobs', jobs: manager.snapshot() });
      postMessage({ event: 'ready', settings: manager.settings });
    });
    manager.subscribe((jobs) => { postMessage({ event: 'jobs', jobs }); watchCompletion(jobs); });
    return;
  }
  const fn = handlers[msg.type];
  if (!fn) { postMessage({ reply: msg.id, ok: false, error: `未知操作：${msg.type}` }); return; }
  try {
    if (manager) await manager.ready;
    const data = await fn(msg.payload || {});
    postMessage({ reply: msg.id, ok: true, data });
  } catch (e) {
    postMessage({ reply: msg.id, ok: false, error: (e && e.message) || String(e) });
  }
};
