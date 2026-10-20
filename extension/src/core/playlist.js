/**
 * 栏目 / 合集识别（运行于 Service Worker，不注入网页脚本）
 *
 * 依次尝试三种来源，合并去重：
 *   1. 栏目接口：页面中含 TOPC 栏目 ID → api.cntv.cn/NewVideo/getVideoListByColumn（如 tv.cctv.com/lm/* 及其节目页）
 *   2. 小央视频栏目：页面中含 lm_mid / pd_id 变量 → media.app.cctv.com/vapi/video/vplist.do（如 v.cctv.com/jiaoyu/mls/）
 *   3. 页面静态链接：抓取 HTML 中所有 VIDE*.shtml 视频链接及其标题、封面（如 finance.cctv.com/special/*）
 *      封面文件名通常就是视频 GUID，可省去逐个打开页面。
 */
(function (global) {
  'use strict';

  const MAX_ITEMS = 300;
  const GUID_IN_IMG = /\/([0-9a-f]{32})-\d+\.(?:jpe?g|png|webp)?/i;

  const decodeEntities = (s) => String(s)
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n));
  const stripTags = (s) => decodeEntities(String(s).replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();

  function absUrl(u, base) {
    try { return new URL(u.trim(), base).href.replace(/^http:/, 'https:').replace(/[?#].*$/, ''); } catch (_) { return ''; }
  }

  function parseLength(v) {
    if (typeof v === 'number') return v;
    const m = String(v || '').match(/^(?:(\d+):)?(\d+):(\d+)$/);
    return m ? (+m[1] || 0) * 3600 + +m[2] * 60 + +m[3] : +v || 0;
  }

  /** 来源 3：页面静态链接 */
  function fromAnchors(html, pageUrl) {
    const map = new Map();
    const re = /<a\b([^>]*?)href\s*=\s*["']([^"']*VIDE[A-Za-z0-9]{10,}\.shtml[^"']*)["']([^>]*)>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = re.exec(html))) {
      const url = absUrl(m[2], pageUrl);
      if (!url) continue;
      const attrs = m[1] + m[3];
      const inner = m[4];
      let item = map.get(url);
      if (!item) { item = { url, title: '', cover: '', guid: '', duration: 0 }; map.set(url, item); }
      const img = inner.match(/<img\b[^>]*?(?:data-src|src)\s*=\s*["']([^"']+)["'][^>]*>/i);
      if (img && !item.cover) item.cover = absUrl(img[1], pageUrl);
      const alt = img && (img[0].match(/alt\s*=\s*["']([^"']+)["']/i) || [])[1];
      const titleAttr = (attrs.match(/title\s*=\s*["']([^"']+)["']/i) || [])[1];
      const text = stripTags(inner);
      for (const t of [text, titleAttr && decodeEntities(titleAttr), alt && decodeEntities(alt)]) {
        if (t && t.length > item.title.length && t.length < 120) item.title = t;
      }
      const g = item.cover.match(GUID_IN_IMG);
      if (g) item.guid = g[1].toLowerCase();
    }
    return [...map.values()].filter((x) => x.title);
  }

  /**
   * 找出页面"自己声明"的栏目 ID。页面里常有大量无关的 TOPC（导航、推荐、column_idArray 等），
   * 不能取第一个出现的；只认这几种声明：
   *   - 视频页：var column_id = "TOPC…"（tv.cctv.com 节目视频页）
   *   - 栏目页：var topicID = 'TOPC…' / var lmtopId = 'TOPC…' 以及同类 *topId / *columnId 变量
   * 视频页没有 column_id（例如 4K 专区、单条新闻）时，即视为不属于栏目。
   */
  function findColumnId(html) {
    const patterns = [
      /\bvar\s+column_id\s*=\s*["'](TOPC\d{10,})["']/,
      /\bvar\s+(?:topicID|topicId|lmtopId|lmTopId|columnId|columnID|topId)\s*=\s*["'](TOPC\d{10,})["']/,
    ];
    for (const re of patterns) {
      const m = html.match(re);
      if (m) return m[1];
    }
    return '';
  }

  /** 来源 1：栏目接口 */
  async function fromColumn(html, forcedId) {
    const id = forcedId || findColumnId(html);
    if (!id) return { total: 0, items: [] };
    const url = `https://api.cntv.cn/NewVideo/getVideoListByColumn?id=${id}&n=100&sort=desc&p=1&mode=0&serviceId=tvcctv`;
    const data = JSON.parse(await global.CctvApi.fetchText(url));
    const list = (data && data.data && data.data.list) || [];
    // 栏目名：取节目标题中的《…》，否则用页面标题
    const nameFrom = (t) => ((t || '').match(/《([^》]{1,20})》/) || [])[1] || '';
    const counts = {};
    for (const x of list) { const n = nameFrom(x.title); if (n) counts[n] = (counts[n] || 0) + 1; }
    const name = Object.keys(counts).sort((a, b) => counts[b] - counts[a])[0] || '';
    return {
      id,
      name,
      total: (data && data.data && +data.data.total) || list.length,
      items: list.map((x) => ({
        url: absUrl(x.url || '', 'https://tv.cctv.com/'),
        guid: String(x.guid || '').toLowerCase(),
        title: x.title || '',
        cover: x.image ? absUrl(x.image.replace(/\.$/, '.jpg'), 'https://p1.img.cctvpic.com/') : '',
        duration: parseLength(x.length),
        date: x.time || '',
      })),
    };
  }

  /**
   * 来源 1b：剧集 / 系列（视频集）。
   * 电视剧、纪录片等视频页不声明 column_id，选集列表由 videoinfoByGuid 返回的 album_id
   * 再经 getVideoListByAlbumIdNew 拉取（与页面"选集"使用同一接口）。
   */
  async function fromAlbum(html) {
    let albumId = ((html.match(/var\s+videotvCodes\s*=\s*["'](VIDA\w+)/) || [])[1]) || '';
    let name = '';
    const guid = ((html.match(/var\s+guid\s*=\s*["']([0-9a-f]{32})["']/i) || [])[1]) || '';
    if (!albumId && guid) {
      for (const sid of ['tvcctv', 'cctv4k']) {
        try {
          const info = JSON.parse(await global.CctvApi.fetchText(`https://api.cntv.cn/video/videoinfoByGuid?serviceId=${sid}&guid=${guid}`));
          if (info && info.album_id) {
            albumId = String(info.album_id).split(',')[0];
            name = (info.vset_title || '').replace(/[《》]/g, '').trim();
            break;
          }
        } catch (_) { /* 换一个 serviceId 再试 */ }
      }
    }
    if (!albumId) return { total: 0, items: [] };
    const list = [];
    let total = 0;
    for (let p = 1; p <= 5 && list.length < MAX_ITEMS; p++) {
      const data = JSON.parse(await global.CctvApi.fetchText(
        `https://api.cntv.cn/NewVideo/getVideoListByAlbumIdNew?id=${albumId}&serviceId=tvcctv&p=${p}&n=100&sort=asc&mode=0&pub=1`));
      const page = (data && data.data && data.data.list) || [];
      total = (data && data.data && +data.data.total) || total;
      list.push(...page);
      if (page.length < 100 || list.length >= total) break;
    }
    // 必须是同一部作品：大部分标题带同一个《…》。像"小央视频"这类大杂烩视频集不当剧集处理。
    const counts = {};
    for (const x of list) { const n = ((x.title || '').match(/《([^》]{1,30})》/) || [])[1]; if (n) counts[n] = (counts[n] || 0) + 1; }
    const top = Object.keys(counts).sort((a, b) => counts[b] - counts[a])[0];
    if (!top || counts[top] < list.length * 0.7) return { total: 0, items: [] };
    name = top;
    // 按集数排序（接口偶尔会把重新上传的某一集排在末尾）
    if (list.length && list.every((x) => +x.part > 0)) list.sort((a, b) => +a.part - +b.part);
    return {
      id: albumId,
      name,
      total: total || list.length,
      items: list.map((x) => ({
        url: absUrl(x.url || '', 'https://tv.cctv.com/'),
        guid: String(x.guid || '').toLowerCase(),
        title: x.title || '',
        cover: x.image ? absUrl(x.image, 'https://p1.img.cctvpic.com/') : '',
        duration: parseLength(x.length),
        date: x.time || '',
      })),
    };
  }

  /** 来源 2：小央视频栏目接口 */
  async function fromVplist(html) {
    const mid = (html.match(/var\s+lm_mid\w*\s*=\s*['"](\w+)['"]/) || [])[1];
    const chid = (html.match(/var\s+pd_id\w*\s*=\s*['"](\w+)['"]/) || [])[1];
    if (!mid || !chid) return { total: 0, items: [] };
    const items = [];
    let total = 0;
    for (let p = 1; p <= 3 && items.length < MAX_ITEMS; p++) {
      const text = await global.CctvApi.fetchText(`https://media.app.cctv.com/vapi/video/vplist.do?chid=${chid}&mid=${mid}&p=${p}&n=100&cb=cb`);
      const json = JSON.parse(text.replace(/^[^(]*\(/, '').replace(/\)\s*;?\s*$/, ''));
      total = +json.count || 0;
      const list = json.data || [];
      for (const x of list) {
        items.push({
          url: absUrl(x.wwwUrl || '', 'https://v.cctv.com/'),
          guid: String(x.guid || '').toLowerCase(),
          title: x.title || '',
          cover: x.image1 || x.image2 || '',
          duration: +x.vduration || 0,
          date: x.pubTime ? new Date(+x.pubTime).toISOString().slice(0, 10) : '',
        });
      }
      if (items.length >= total || !list.length) break;
    }
    const name = (html.match(/<title>\s*([^_<|]{1,20})/) || [])[1] || '';
    return { total, items, name: name.trim() };
  }

  const isVideoPage = (url) => /\/VIDE[A-Za-z0-9]{10,}\.shtml/i.test(url || '');

  async function extract(html, pageUrl) {
    const results = [];
    let source = '';
    let total = 0;
    let name = '';
    try {
      const col = await fromColumn(html);
      if (col.items && col.items.length) { results.push(...col.items); source = 'column'; total = col.total; name = col.name; }
    } catch (e) { console.warn('[playlist] 栏目接口失败', e); }
    if (!results.length && isVideoPage(pageUrl)) {
      try {
        const al = await fromAlbum(html);
        // 只有一集的"视频集"不算剧集
        if (al.items.length > 1) { results.push(...al.items); source = 'album'; total = al.total; name = al.name; }
      } catch (e) { console.warn('[playlist] 剧集接口失败', e); }
    }
    if (!results.length) {
      try {
        const vp = await fromVplist(html);
        if (vp.items.length) { results.push(...vp.items); source = 'vplist'; total = vp.total; name = vp.name; }
      } catch (e) { console.warn('[playlist] 小央视频接口失败', e); }
    }
    // 栏目页（/lm/xxx/）没有声明栏目 ID 时：打开页面里的第一条视频，读取它声明的 column_id
    if (!results.length && /\/lm\/[^/]+/i.test(pageUrl || '')) {
      try {
        const first = fromAnchors(html, pageUrl)[0];
        if (first) {
          const id = findColumnId(await global.CctvApi.fetchText(first.url));
          const col = id ? await fromColumn('', id) : null;
          if (col && col.items.length) { results.push(...col.items); source = 'column'; total = col.total; name = col.name; }
        }
      } catch (e) { console.warn('[playlist] 栏目推断失败', e); }
    }
    // 页面链接只用于专题 / 合集页。视频页上的视频链接几乎都是导航、推荐、固定入口，不是栏目内容。
    if (!results.length && !isVideoPage(pageUrl)) {
      const anchors = fromAnchors(html, pageUrl);
      if (anchors.length >= 4) {
        results.push(...anchors);
        source = 'page';
        total = anchors.length;
        name = ((html.match(/<title>\s*([^_<|]{1,20})/) || [])[1] || '').trim();
      }
    }
    // 去重（按 GUID 或链接）
    const seen = new Set();
    const items = [];
    for (const it of results) {
      const key = it.guid || it.url;
      if (!key || seen.has(key)) continue;
      seen.add(key);
      items.push(it);
      if (items.length >= MAX_ITEMS) break;
    }
    return { source, name, total: Math.max(total, items.length), items };
  }

  /** 解析一个合集条目的 GUID（必要时打开它的页面） */
  async function resolveItemGuid(item) {
    if (item.guid && /^[0-9a-f]{32}$/.test(item.guid)) return item.guid;
    if (!item.url) throw new Error('缺少视频地址');
    const html = await global.CctvApi.fetchText(item.url);
    const g = global.CctvApi.extractGuidsFromHtml(html)[0];
    if (!g) throw new Error('无法识别视频');
    return g;
  }

  /** 按偏好选择清晰度：best | 1080 | 720 | 360（取不超过该高度的最高一档） */
  function pickQuality(qualities, pref) {
    if (!qualities.length) return null;
    if (!pref || pref === 'best') return qualities[0];
    const limit = +pref;
    const height = (q) => +((q.resolution || '').split('x')[1] || 0);
    return qualities.find((q) => height(q) && height(q) <= limit) || qualities[qualities.length - 1];
  }

  global.Playlist = { extract, fromAnchors, findColumnId, resolveItemGuid, pickQuality };
})(typeof self !== 'undefined' ? self : globalThis);
