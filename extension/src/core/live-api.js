/**
 * 央视网直播 / 回看（时移）接口
 *
 * 直播：vdn 接口返回 cdrm 加密的 HLS 主列表（manifest.hls_cdrm），切片用 core/live-crypto.js 解密。
 * 回看：同一条流加上 begintimeabs / endtimeabs 两个毫秒时间戳，服务端返回该时段的点播式列表（含 #EXT-X-ENDLIST）。
 * 频道节目单（EPG）来自 api.cntv.cn/epg。
 *
 * vdn 接口要求 auth-key 请求头：`${ts}-${rand}-${md5(channel + ts + rand + KEY)}`。
 */
(function (global) {
  'use strict';

  const VDN_KEY = 'a4220a71b31746908fa3e7fdd7a6852a';
  const REFERER = 'https://tv.cctv.com/';

  // 央视网主要直播频道（channel id → 名称）。回看最多约 7 天。
  const CHANNELS = [
    ['cctv1', 'CCTV-1 综合'], ['cctv2', 'CCTV-2 财经'], ['cctv3', 'CCTV-3 综艺'],
    ['cctv4', 'CCTV-4 中文国际'], ['cctv5', 'CCTV-5 体育'], ['cctv5plus', 'CCTV-5+ 体育赛事'],
    ['cctv6', 'CCTV-6 电影'], ['cctv7', 'CCTV-7 国防军事'], ['cctv8', 'CCTV-8 电视剧'],
    ['cctvjilu', 'CCTV-9 纪录'], ['cctv10', 'CCTV-10 科教'], ['cctv11', 'CCTV-11 戏曲'],
    ['cctv12', 'CCTV-12 社会与法'], ['cctv13', 'CCTV-13 新闻'], ['cctvchild', 'CCTV-14 少儿'],
    ['cctv15', 'CCTV-15 音乐'], ['cctv16', 'CCTV-16 奥林匹克'], ['cctv17', 'CCTV-17 农业农村'],
  ];

  function authKey(channel) {
    const ts = Date.now();
    let rand = Math.floor(Math.random() * 1000);
    if (rand < 100) rand += 100;
    const sign = md5fallback(`${channel}${ts}${rand}${VDN_KEY}`);
    return { header: `${ts}-${rand}-${sign}`, ts };
  }

  async function vdn(path, channel, extra = {}) {
    const { header, ts } = authKey(channel);
    const params = new URLSearchParams({ channel, vn: '1', pdrm: '1', uid: '', hbss: String(ts), ...extra });
    const url = `https://vdnx.live.cntv.cn/api/v3/vdn/${path}?${params}`;
    const res = await fetch(url, { headers: { 'auth-key': header }, cache: 'no-store' });
    const data = await res.json();
    if (data.ack !== 'yes') throw new Error(data.tip_msg || '无法获取直播地址');
    return data;
  }

  /** 接口给出的全部候选主列表（主线路、备用线路），去重后按优先级排列 */
  function candidates(data, timeshift) {
    const m = data.manifest || {}, b = data.backup || {};
    const keys = timeshift ? ['hls_cdrm_timeshift', 'hls_cdrm', 'hls_nd'] : ['hls_cdrm', 'hls_nd'];
    const out = [];
    for (const k of keys) for (const src of [m, b]) if (src[k] && !out.includes(src[k])) out.push(src[k]);
    return out;
  }

  /** 依次打开候选主列表，返回第一个能读到媒体列表的线路 */
  async function openFirst(masters, channel, data) {
    const errors = [];
    for (const master of masters) {
      try {
        const v = await pickVariant(master);
        const text = v.text || await CctvApi.fetchText(v.url);
        if (!/#EXTINF/.test(text)) throw new Error('列表为空');
        return { url: v.url, master, channel, encrypted: /cdrm/.test(master), drm: data.drm === '1', alternates: masters.length - 1 };
      } catch (e) {
        let host = '';
        try { host = new URL(master).host; } catch (_) {}
        errors.push(`${host}：${e.message || e}`);
      }
    }
    throw new Error(`所有线路都无法访问（${errors.join('；')}）`);
  }

  /** 从主列表里挑最高码率的变体列表地址 */
  async function pickVariant(masterUrl) {
    const text = await CctvApi.fetchText(masterUrl);
    if (!/#EXT-X-STREAM-INF/i.test(text)) return { url: masterUrl, text };
    const lines = text.split(/\r?\n/);
    let best = null;
    for (let i = 0; i < lines.length; i++) {
      const m = lines[i].match(/#EXT-X-STREAM-INF.*BANDWIDTH=(\d+)/i);
      if (m) {
        const uri = (lines[i + 1] || '').trim();
        if (uri && (!best || +m[1] > best.bw)) best = { bw: +m[1], uri };
      }
    }
    if (!best) return { url: masterUrl, text };
    return { url: abs(best.uri, masterUrl) };
  }

  function abs(u, base) { try { return new URL(u, base).href; } catch (_) { return u; } }

  /** 解析 HLS 媒体列表：切片（含序号）、总时长、目标时长、是否结束 */
  function parseSegments(text, baseUrl) {
    const segs = [];
    let duration = 0;
    const seq0 = +((text.match(/#EXT-X-MEDIA-SEQUENCE:\s*(\d+)/) || [])[1] || 0);
    const target = +((text.match(/#EXT-X-TARGETDURATION:\s*(\d+)/) || [])[1] || 4);
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i].trim();
      if (l.startsWith('#EXTINF')) {
        const d = parseFloat(l.slice(8)) || 0;
        let j = i + 1;
        while (j < lines.length && lines[j].trim().startsWith('#')) j++;
        const uri = (lines[j] || '').trim();
        if (uri) { segs.push({ url: abs(uri, baseUrl), duration: d, seq: seq0 + segs.length }); duration += d; }
        i = j;
      }
    }
    return { segments: segs, duration, target, ended: /#EXT-X-ENDLIST/.test(text) };
  }

  const LiveApi = {
    channels: CHANNELS.map(([id, name]) => ({ id, name })),

    /** 频道当前正在直播的节目名与节目单 */
    async epg(channel, dateStr) {
      const d = dateStr || ymd(new Date());
      const url = `https://api.cntv.cn/epg/epginfo?serviceId=shiyi&d=${d}&c=${channel}`;
      const data = JSON.parse(await CctvApi.fetchText(url));
      const ch = data[channel] || {};
      const programs = (ch.program || []).map((p) => ({
        title: p.t, start: +p.st * 1000, end: +p.et * 1000, showTime: p.showTime, duration: +p.duration || (+p.et - +p.st),
      }));
      return { channelName: ch.channelName || channel, now: ch.isLive || '', programs };
    },

    /**
     * 直播流（用于录制）：主线路 + 备用线路依次尝试，返回第一个可用的媒体列表。
     * avoid：上次失败的主列表地址（录制中途线路失效时换线）。
     */
    async liveStream(channel, { avoid = '' } = {}) {
      const data = await vdn('live', channel);
      const masters = candidates(data, false).filter((u) => u !== avoid);
      if (!masters.length) throw new Error('该频道暂不支持下载');
      return openFirst(masters, channel, data);
    },

    /** 回看流：某个时间段（毫秒）的点播式列表，同样按主 / 备线路依次尝试 */
    async timeshiftStream(channel, startMs, endMs) {
      // 时移地址在 live 接口的 manifest.hls_cdrm_timeshift 里；livets 接口只做权限校验（版权限制时段会拒绝）
      const [data, perm] = await Promise.all([
        vdn('live', channel),
        vdn('livets', channel, { iflv: '1', 'video-player': '1', starttime: String(startMs) }).catch((e) => ({ play: '0', tip_msg: e.message })),
      ]);
      if (perm && perm.play === '0' && perm.tip_msg) throw new Error(perm.tip_msg);
      const masters = candidates(data, true).map((u) => `${u}${u.includes('?') ? '&' : '?'}begintimeabs=${startMs}&endtimeabs=${endMs}`);
      if (!masters.length) throw new Error('该频道暂不支持回看下载');
      const s = await openFirst(masters, channel, data);
      return { ...s, start: startMs, end: endMs };
    },

    /** 取某条流的切片列表（回看是完整列表；直播为滚动窗口，录制时需反复拉取） */
    async getSegments(streamUrl) {
      const text = await CctvApi.fetchText(streamUrl);
      return parseSegments(text, streamUrl);
    },

    /**
     * 识别直播 / 回看页面
     *   tv.cctv.com/live/<频道>/      → { mode: 'live', channel }
     *   tv.cctv.com/live/index.shtml  → { mode: 'epg', channel }（直播总页，取播放器当前频道）
     *   tv.cctv.com/epg/              → { mode: 'epg', channel }（节目单页，默认 CCTV-1）
     *   livechina.cctv.com/live_zb/…  → 慢直播，页面 data-guid 为频道号（无节目单）
     */
    async detect(url, hint) {
      let u;
      try { u = new URL(url); } catch (_) { return null; }
      const host = u.hostname;
      const known = (c) => !!c && CHANNELS.some((x) => x[0] === c);
      const m = u.pathname.match(/^\/live\/([a-z0-9]+)(\/|$)/i);
      if (/^(tv|www)\.cctv\.(com|cn)$/i.test(host) && /^\/live(\/|$)/i.test(u.pathname)) {
        const c = m && m[1].toLowerCase();
        if (known(c)) return { mode: 'live', channel: c };
        // 直播总页（/live/index.shtml 等）：频道在页面内切换，网址不变。
        // 用播放器实际请求的频道（hint），否则默认 CCTV-1，并给出频道选择。
        const h = (hint || '').toLowerCase();
        return { mode: 'epg', channel: known(h) ? h : 'cctv1' };
      }
      if (/^tv\.cctv\.(com|cn)$/i.test(host) && /^\/epg(\/|$)/i.test(u.pathname)) {
        const c = u.searchParams.get('channel') || u.searchParams.get('c') || 'cctv1';
        return { mode: 'epg', channel: c.toLowerCase() };
      }
      if (/(^|\.)livechina\.cctv\.com$/i.test(host) || /^\/live_zb\//.test(u.pathname)) {
        try {
          const html = await CctvApi.fetchText(url);
          const g = [...html.matchAll(/data-guid="([^"]+)"/g)].map((x) => x[1]).find((x) => !/^[0-9a-f]{32}$/i.test(x));
          const name = (html.match(/class="titleH2"><span>([^<]{1,40})</) || html.match(/<title>([^<_|]{1,40})/) || [])[1] || '';
          if (g) return { mode: 'live', channel: g.toLowerCase(), name: name.trim(), noEpg: true };
        } catch (_) {}
      }
      return null;
    },

    /** 频道信息 + 今日节目单 */
    async info(det) {
      const ch = CHANNELS.find((c) => c[0] === det.channel);
      const info = { ...det, channelName: det.name || (ch ? ch[1] : det.channel), channels: LiveApi.channels, now: '', programs: [] };
      if (!det.noEpg) {
        try {
          const e = await LiveApi.epg(det.channel);
          info.channelName = (ch && ch[1]) || e.channelName || info.channelName;
          info.now = e.now;
          info.programs = e.programs;
        } catch (_) {}
      }
      return info;
    },

    /** 录制 / 回看任务的标题 */
    titles: {
      live(channelName, programTitle) {
        const d = new Date();
        const stamp = `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}`;
        return `${programTitle ? programTitle + ' ' : ''}${channelName} ${stamp}`;
      },
      replay(channelName, title, start) {
        const d = new Date(start);
        return `${title || '回看'} ${channelName} ${d.getMonth() + 1}月${d.getDate()}日`;
      },
    },

    parseSegments,
    ymd,
  };

  function ymd(date) {
    return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}`;
  }

  // ---- 轻量 MD5（直播 auth-key 用；避免依赖 crypto.subtle，后者不支持 MD5）----
  function md5fallback(str) {
    function rl(n, c) { return (n << c) | (n >>> (32 - c)); }
    function add(x, y) { const l = (x & 0xffff) + (y & 0xffff); return (((x >> 16) + (y >> 16) + (l >> 16)) << 16) | (l & 0xffff); }
    function cmn(q, a, b, x, s, t) { return add(rl(add(add(a, q), add(x, t)), s), b); }
    function ff(a, b, c, d, x, s, t) { return cmn((b & c) | (~b & d), a, b, x, s, t); }
    function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
    function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
    function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }
    const bytes = [];
    for (let i = 0; i < str.length; i++) {
      let c = str.charCodeAt(i);
      if (c < 128) bytes.push(c);
      else if (c < 2048) bytes.push(192 | (c >> 6), 128 | (c & 63));
      else { bytes.push(224 | (c >> 12), 128 | ((c >> 6) & 63), 128 | (c & 63)); }
    }
    const n = bytes.length;
    const words = [];
    for (let i = 0; i < n; i++) words[i >> 2] = (words[i >> 2] || 0) | (bytes[i] << ((i % 4) * 8));
    words[n >> 2] = (words[n >> 2] || 0) | (0x80 << ((n % 4) * 8));
    words[(((n + 8) >> 6) + 1) * 16 - 2] = n * 8;
    let a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;
    for (let i = 0; i < words.length; i += 16) {
      const oa = a, ob = b, oc = c, od = d; const w = (k) => words[i + k] || 0;
      a = ff(a, b, c, d, w(0), 7, -680876936); d = ff(d, a, b, c, w(1), 12, -389564586); c = ff(c, d, a, b, w(2), 17, 606105819); b = ff(b, c, d, a, w(3), 22, -1044525330);
      a = ff(a, b, c, d, w(4), 7, -176418897); d = ff(d, a, b, c, w(5), 12, 1200080426); c = ff(c, d, a, b, w(6), 17, -1473231341); b = ff(b, c, d, a, w(7), 22, -45705983);
      a = ff(a, b, c, d, w(8), 7, 1770035416); d = ff(d, a, b, c, w(9), 12, -1958414417); c = ff(c, d, a, b, w(10), 17, -42063); b = ff(b, c, d, a, w(11), 22, -1990404162);
      a = ff(a, b, c, d, w(12), 7, 1804603682); d = ff(d, a, b, c, w(13), 12, -40341101); c = ff(c, d, a, b, w(14), 17, -1502002290); b = ff(b, c, d, a, w(15), 22, 1236535329);
      a = gg(a, b, c, d, w(1), 5, -165796510); d = gg(d, a, b, c, w(6), 9, -1069501632); c = gg(c, d, a, b, w(11), 14, 643717713); b = gg(b, c, d, a, w(0), 20, -373897302);
      a = gg(a, b, c, d, w(5), 5, -701558691); d = gg(d, a, b, c, w(10), 9, 38016083); c = gg(c, d, a, b, w(15), 14, -660478335); b = gg(b, c, d, a, w(4), 20, -405537848);
      a = gg(a, b, c, d, w(9), 5, 568446438); d = gg(d, a, b, c, w(14), 9, -1019803690); c = gg(c, d, a, b, w(3), 14, -187363961); b = gg(b, c, d, a, w(8), 20, 1163531501);
      a = gg(a, b, c, d, w(13), 5, -1444681467); d = gg(d, a, b, c, w(2), 9, -51403784); c = gg(c, d, a, b, w(7), 14, 1735328473); b = gg(b, c, d, a, w(12), 20, -1926607734);
      a = hh(a, b, c, d, w(5), 4, -378558); d = hh(d, a, b, c, w(8), 11, -2022574463); c = hh(c, d, a, b, w(11), 16, 1839030562); b = hh(b, c, d, a, w(14), 23, -35309556);
      a = hh(a, b, c, d, w(1), 4, -1530992060); d = hh(d, a, b, c, w(4), 11, 1272893353); c = hh(c, d, a, b, w(7), 16, -155497632); b = hh(b, c, d, a, w(10), 23, -1094730640);
      a = hh(a, b, c, d, w(13), 4, 681279174); d = hh(d, a, b, c, w(0), 11, -358537222); c = hh(c, d, a, b, w(3), 16, -722521979); b = hh(b, c, d, a, w(6), 23, 76029189);
      a = hh(a, b, c, d, w(9), 4, -640364487); d = hh(d, a, b, c, w(12), 11, -421815835); c = hh(c, d, a, b, w(15), 16, 530742520); b = hh(b, c, d, a, w(2), 23, -995338651);
      a = ii(a, b, c, d, w(0), 6, -198630844); d = ii(d, a, b, c, w(7), 10, 1126891415); c = ii(c, d, a, b, w(14), 15, -1416354905); b = ii(b, c, d, a, w(5), 21, -57434055);
      a = ii(a, b, c, d, w(12), 6, 1700485571); d = ii(d, a, b, c, w(3), 10, -1894986606); c = ii(c, d, a, b, w(10), 15, -1051523); b = ii(b, c, d, a, w(1), 21, -2054922799);
      a = ii(a, b, c, d, w(8), 6, 1873313359); d = ii(d, a, b, c, w(15), 10, -30611744); c = ii(c, d, a, b, w(6), 15, -1560198380); b = ii(b, c, d, a, w(13), 21, 1309151649);
      a = ii(a, b, c, d, w(4), 6, -145523070); d = ii(d, a, b, c, w(11), 10, -1120210379); c = ii(c, d, a, b, w(2), 15, 718787259); b = ii(b, c, d, a, w(9), 21, -343485551);
      a = add(a, oa); b = add(b, ob); c = add(c, oc); d = add(d, od);
    }
    const hex = (num) => { let s = ''; for (let i = 0; i < 4; i++) s += ('0' + ((num >> (i * 8)) & 0xff).toString(16)).slice(-2); return s; };
    return hex(a) + hex(b) + hex(c) + hex(d);
  }

  LiveApi.md5 = md5fallback;
  global.LiveApi = LiveApi;
})(typeof self !== 'undefined' ? self : globalThis);
