/**
 * CCTV 下载器 · Service Worker
 *
 * 所有核心工作都在这里完成，不向网页注入任何脚本：
 *   - 视频识别：读取标签页地址 → 抓取页面 HTML 提取 GUID；同时被动监听播放器发出的视频详情 / h5e 播放列表请求
 *   - 视频解析：getHttpVideoInfo → hls_h5e 主列表 → 各清晰度
 *   - 下载流水线：并发拉取切片 → 合并 → h5e 原始流解密 → MP4 封装 → 暂存 → chrome.downloads
 */
if (typeof importScripts === 'function') importScripts(  // Chrome / Edge：Service Worker；Firefox 由 manifest 的 background.scripts 加载
  'lib/mux.min.js',
  'core/h5e-decrypt.js',
  'core/ts-pipeline.js',
  'core/mp4-remux.js',
  'core/cctv-api.js',
  'core/net.js',
  'core/storage.js',
  'core/offscreen-bridge.js',
  'core/color.js',
  'core/playlist.js',
  'core/ui-signals.js',
  'core/cntv-live-module.js',
  'core/live-crypto.js',
  'core/live-api.js',
  'core/downloader.js'
);

const manager = new DownloadManager();
self.toolbarProgress = new ToolbarProgress();
const notifier = new Notifier(manager);
manager.ready.then(() => { notifier.onJobs(manager.snapshot()); manager.updateBadge(); });
manager.subscribe((jobs) => notifier.onJobs(jobs));

// ---------------------------------------------------------------------------
// 视频识别：被动监听播放器请求（不修改、不拦截）
// ---------------------------------------------------------------------------
const SUPPORTED_HOST = /(^|\.)(cctv\.com|cctv\.cn|cntv\.cn|cntv\.com|yangshipin\.cn)$/i;

async function getTabGuids(tabId) {
  const key = `tab:${tabId}`;
  const data = await chrome.storage.session.get(key);
  return data[key] || [];
}

async function addTabGuid(tabId, guid) {
  if (tabId < 0) return;
  const key = `tab:${tabId}`;
  const list = await getTabGuids(tabId);
  if (!list.includes(guid)) {
    list.push(guid);
    await chrome.storage.session.set({ [key]: list.slice(-20) });
  }
}

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const m = details.url.match(/[?&]pid=([0-9a-fA-F]{32})/) || details.url.match(/\/([0-9a-fA-F]{32})\/[^/]*\.m3u8/);
    if (m) addTabGuid(details.tabId, m[1].toLowerCase());
  },
  {
    urls: [
      '*://vdn.apps.cntv.cn/api/getHttpVideoInfo.do*',
      '*://*/asp/h5e/*',
      '*://*/asp/hls/*',
      '*://*/asp/enc/*',
      '*://*/asp/enc2/*',
    ],
  }
);

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.url) chrome.storage.session.remove(`tab:${tabId}`);
});
chrome.tabs.onRemoved.addListener((tabId) => chrome.storage.session.remove([`tab:${tabId}`, `tabch:${tabId}`]));

// ---------------------------------------------------------------------------
// 直播 / 回看页面识别
//   tv.cctv.com/live/<频道>/        → 频道直播（可录制，可回看 7 天内节目）
//   tv.cctv.com/epg/                → 节目单（回看）
//   livechina.cctv.com/live_zb/…    → 直播中国等慢直播（页面里的 data-guid 是频道号）
// ---------------------------------------------------------------------------
const detectLive = (url) => LiveApi.detect(url);
const liveInfo = (det) => LiveApi.info(det);

// 直播总页在页面内切换频道、网址不变：记下播放器实际请求的频道
chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (details.tabId < 0) return;
    const m = details.url.match(/[?&]channel=(?:pa:\/\/cctv_p2p_hd)?([a-z0-9]+)/i) || details.url.match(/\/cdrm(?:ld)?(cctv[a-z0-9]+?)_\d/i);
    if (m) chrome.storage.session.set({ [`tabch:${details.tabId}`]: m[1].toLowerCase() }).catch(() => {});
  },
  { urls: ['*://*.live.cntv.cn/api/*/vdn/live*', '*://*/*/cdrm*/index.m3u8*'] }
);
async function getTabChannel(tabId) {
  const key = `tabch:${tabId}`;
  try { return (await chrome.storage.session.get(key))[key] || ''; } catch (_) { return ''; }
}

async function detectTab(tabId) {
  const tab = await chrome.tabs.get(tabId);
  const url = tab.url || '';
  let host = '';
  try { host = new URL(url).hostname; } catch (_) {}
  if (!SUPPORTED_HOST.test(host)) return { url, supported: false, videos: [] };
  const live = await LiveApi.detect(url, await getTabChannel(tabId));
  if (live) return { url, supported: true, videos: [], live: await liveInfo(live) };

  const guids = new Set(await getTabGuids(tabId));
  try {
    const html = await CctvApi.fetchText(url);
    pageHtml.set(tabId, { url, html, t: Date.now() });
    CctvApi.extractGuidsFromHtml(html).forEach((g) => guids.add(g));
  } catch (e) {
    console.warn('[detect] 页面抓取失败', e);
  }
  const videos = await resolveGuids([...guids]);
  return { url, supported: true, videos: videos.list, errors: videos.errors };
}

// 最近一次识别时抓取的页面 HTML，供合集识别复用
const pageHtml = new Map();

async function getPageHtml(tabId) {
  const tab = await chrome.tabs.get(tabId);
  const cached = pageHtml.get(tabId);
  if (cached && cached.url === tab.url && Date.now() - cached.t < 120000) return cached;
  const html = await CctvApi.fetchText(tab.url);
  const entry = { url: tab.url, html, t: Date.now() };
  pageHtml.set(tabId, entry);
  return entry;
}

/** 批量排队：逐个解析 GUID → 视频详情 → 按偏好选清晰度 → 加入下载队列 */
async function enqueuePlaylist(items, pref, format) {
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const item = items[next++];
      try {
        const guid = await Playlist.resolveItemGuid(item);
        const video = await CctvApi.getVideo(guid);
        const q = Playlist.pickQuality(video.qualities, pref);
        if (!q) throw new Error('没有可用的清晰度');
        await manager.add({ video, qualityId: q.id, format });
      } catch (e) {
        manager.addFailed({ title: item.title || '未知视频', cover: item.cover, error: e.message || String(e) });
      }
    }
  };
  await Promise.all([worker(), worker(), worker()]);
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

// ---------------------------------------------------------------------------
// 消息接口（供弹出页使用）
// ---------------------------------------------------------------------------
const handlers = {
  async 'page:detect'({ tabId }) {
    return detectTab(tabId);
  },
  async 'page:playlist'({ tabId }) {
    const tab = await chrome.tabs.get(tabId);
    let host = '';
    try { host = new URL(tab.url || '').hostname; } catch (_) {}
    if (!SUPPORTED_HOST.test(host)) return { source: '', total: 0, items: [] };
    const { url, html } = await getPageHtml(tabId);
    return Playlist.extract(html, url);
  },
  async 'playlist:enqueue'({ items, pref, format }) {
    enqueuePlaylist(items.slice(0, 300), pref, format).catch((e) => console.error('[playlist]', e));
    return { accepted: Math.min(items.length, 300) };
  },
  async 'video:resolve'({ input }) {
    const link = (String(input).match(/https?:\/\/[^\s]+/) || [])[0];
    const live = link && await detectLive(link);
    if (live) return { videos: [], live: await liveInfo(live) };
    const guids = await CctvApi.resolveInput(input);
    const { list, errors } = await resolveGuids(guids);
    if (!list.length) throw new Error((errors[0] && errors[0].error) || '没有解析到视频');
    return { videos: list };
  },
  async 'job:add'({ video, qualityId, format }) {
    const job = await manager.add({ video, qualityId, format });
    return { id: job.id };
  },
  async 'job:cancel'({ id }) { manager.cancel(id); return {}; },
  async 'job:stop'({ id }) { manager.stop(id); return {}; },
  async 'job:extend'({ id, minutes }) { return { until: manager.extend(id, +minutes || 30) }; },
  async 'live:epg'({ channel, date }) { return LiveApi.epg(channel, date); },
  async 'live:info'({ channel }) { return liveInfo({ mode: 'live', channel }); },
  /** 开始录制直播：until 为结束时间（毫秒，0 表示手动停止） */
  async 'live:record'({ channel, channelName, until, programTitle }) {
    const title = LiveApi.titles.live(channelName || channel, programTitle);
    const job = await manager.addLive({ kind: 'live', channel, channelName, title, until: +until || 0 });
    return { id: job.id };
  },
  /** 保存回看：start / end 为毫秒时间戳 */
  async 'live:replay'({ channel, channelName, start, end, title }) {
    const name = LiveApi.titles.replay(channelName || channel, title, start);
    const job = await manager.addLive({ kind: 'replay', channel, channelName, title: name, start: +start, end: +end });
    return { id: job.id };
  },
  async 'job:retry'({ id }) { manager.retry(id); return {}; },
  async 'job:remove'({ id }) { manager.remove(id); return {}; },
  async 'jobs:clear'() { manager.clearFinished(); return {}; },
  async 'jobs:list'() { await manager.ready; return { jobs: manager.snapshot() }; },
  async 'settings:get'() { await manager.ready; return { settings: manager.settings }; },
  async 'settings:set'({ patch }) { return { settings: await manager.updateSettings(patch) }; },
  async 'download:show'({ downloadId }) {
    try { chrome.downloads.show(downloadId); } catch (_) { chrome.downloads.showDefaultFolder(); }
    return {};
  },
};

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || msg.target === 'offscreen') return false;
  const fn = handlers[msg.type];
  if (!fn) return false;
  Promise.resolve(fn(msg))
    .then((data) => sendResponse({ ok: true, ...data }))
    .catch((e) => sendResponse({ ok: false, error: e && e.message ? e.message : String(e) }));
  return true;
});

// 弹出页通过长连接实时接收任务进度
chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== 'ui') return;
  manager.ready.then(() => port.postMessage({ type: 'jobs', jobs: manager.snapshot() }));
  const off = manager.subscribe((jobs) => {
    try { port.postMessage({ type: 'jobs', jobs }); } catch (_) {}
  });
  port.onDisconnect.addListener(off);
});

chrome.runtime.onInstalled.addListener(() => chrome.action.setBadgeText({ text: '' }));  // 清理旧版本遗留的文字角标
