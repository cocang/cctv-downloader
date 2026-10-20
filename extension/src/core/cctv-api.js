/**
 * 央视网视频信息解析（运行于 Service Worker）
 *
 * 流程参考 letr007/CCTVVideoDownloader · ContentResolver：
 *   1. 从页面 HTML（var guid / itemguid / videoCenterId）或播放器请求中拿到视频 GUID
 *   2. 调用 vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=GUID 获取视频详情
 *   3. manifest.hls_h5e_url（回退 hls_enc_url / hls_enc2_url）→ 主播放列表 → 各清晰度子列表
 *   4. 子列表中的 TS 切片即为 h5e 加密流，下载后由 H5eSession 解密
 *   CCTV-4K 频道走明文 hls_url 的 4000 码率（与参考实现一致）。
 */
(function (global) {
  'use strict';

  const INFO_API = 'https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do';
  const GUID_RE = /^[0-9a-f]{32}$/i;

  const QUALITY_LABELS = {
    450: { name: '流畅', short: '270P' },
    850: { name: '标清', short: '360P' },
    1200: { name: '高清', short: '720P' },
    2000: { name: '超清', short: '720P+' },
    3000: { name: '超清', short: '1080P' },
    4000: { name: '蓝光', short: '1080P' },
  };

  async function fetchText(url, { signal, timeout = 15000 } = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(new Error('请求超时')), timeout);
    const onAbort = () => ctrl.abort(signal.reason);
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
    try {
      const res = await fetch(url, { signal: ctrl.signal, cache: 'no-store', credentials: 'omit' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.text();
    } finally {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', onAbort);
    }
  }

  /** 从页面 HTML 中提取视频 GUID（可能有多个） */
  function extractGuidsFromHtml(html) {
    const found = [];
    const push = (g) => { if (g && GUID_RE.test(g) && !found.includes(g.toLowerCase())) found.push(g.toLowerCase()); };
    const patterns = [
      /\bvar\s+guid\s*=\s*["']([0-9a-fA-F]{32})["']/g,
      /\bvar\s+itemguid\s*=\s*["']([0-9a-fA-F]{32})["']/g,
      /\bvideoCenterId\s*[:=]\s*["']([0-9a-fA-F]{32})["']/g,
      /\bguid\s*:\s*["']([0-9a-fA-F]{32})["']/g,
      /["']videoCenterId["']\s*:\s*["']([0-9a-fA-F]{32})["']/g,
      /getHttpVideoInfo\.do\?pid=([0-9a-fA-F]{32})/g,
    ];
    for (const re of patterns) {
      let m;
      while ((m = re.exec(html))) push(m[1]);
    }
    return found;
  }

  /** 解析用户输入：GUID / 视频页面链接 */
  async function resolveInput(input) {
    const text = String(input || '').trim();
    const direct = text.match(/[0-9a-fA-F]{32}/);
    if (GUID_RE.test(text)) return [text.toLowerCase()];
    if (/^https?:\/\//i.test(text)) {
      const pidInUrl = text.match(/[?&](?:pid|guid)=([0-9a-fA-F]{32})/);
      if (pidInUrl) return [pidInUrl[1].toLowerCase()];
      const html = await fetchText(text);
      const guids = extractGuidsFromHtml(html);
      if (guids.length) return guids;
      throw new Error('此页面中没有视频');
    }
    if (direct) return [direct[0].toLowerCase()];
    throw new Error('无法识别此链接');
  }

  function absUrl(ref, base) {
    if (ref.startsWith('//')) return new URL(base).protocol + ref;
    return new URL(ref, base).href;
  }

  /** 解析主播放列表中的各码率 */
  function parseMaster(text, baseUrl) {
    const variants = [];
    let pending = null;
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      if (line.startsWith('#EXT-X-STREAM-INF')) {
        const bw = line.match(/BANDWIDTH=(\d+)/i);
        const res = line.match(/RESOLUTION=(\d+x\d+)/i);
        pending = { bandwidth: bw ? +bw[1] : 0, resolution: res ? res[1] : '' };
      } else if (!line.startsWith('#') && pending) {
        variants.push({ ...pending, url: absUrl(line, baseUrl) });
        pending = null;
      }
    }
    return variants;
  }

  /** 解析媒体播放列表，返回切片 URL 与总时长 */
  function parseMedia(text, baseUrl) {
    const segments = [];
    let duration = 0;
    let pendingDur = 0;
    let keyInfo = null;
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      if (line.startsWith('#EXTINF:')) {
        pendingDur = parseFloat(line.slice(8)) || 0;
      } else if (line.startsWith('#EXT-X-KEY')) {
        keyInfo = line;
      } else if (!line.startsWith('#')) {
        segments.push({ url: absUrl(line, baseUrl), duration: pendingDur });
        duration += pendingDur;
        pendingDur = 0;
      }
    }
    if (keyInfo && !/METHOD=NONE/i.test(keyInfo)) {
      throw new Error('该视频使用了不支持的 HLS 加密方式');
    }
    return { segments, duration };
  }

  function rateFromUrl(url) {
    const m = url.match(/\/(?:hls|h5e\/hls|enc\/hls|enc2\/hls)\/(\d{3,4})\//) || url.match(/\/(\d{3,4})\.m3u8/);
    return m ? +m[1] : 0;
  }

  function describeVariant(v) {
    const rate = rateFromUrl(v.url) || Math.round(v.bandwidth / 1024);
    const label = QUALITY_LABELS[rate] || { name: `${rate}K`, short: '' };
    const height = v.resolution ? +v.resolution.split('x')[1] : 0;
    return {
      id: String(rate),
      rate,
      name: label.name,
      resolution: v.resolution,
      short: height ? `${height}P` : label.short,
      bandwidth: v.bandwidth,
      url: v.url,
    };
  }

  function clearVariantUrl(hlsUrl, quality) {
    const dir = /\/asp\/hls\/(main|4000|3000|2000|1200|850|450)(?=\/)/;
    if (!dir.test(hlsUrl)) return '';
    return hlsUrl.replace(dir, '/asp/hls/' + quality).replace(/\/main\.m3u8(?=([?#]|$))/, '/' + quality + '.m3u8');
  }

  function normalizeEncryptedPlaylistUrl(url) {
    return url.replace(/https:\/\/[^/]+\/asp\/enc2\//, 'https://drm.cntv.vod.dnsv1.com/asp/enc2/');
  }

  function fixImage(url) {
    if (!url) return '';
    if (url.startsWith('//')) return 'https:' + url;
    return url.replace(/^http:\/\//, 'https://');
  }

  const infoCache = new Map();

  /** 获取视频详情与可选清晰度 */
  async function getVideo(guid) {
    guid = guid.toLowerCase();
    if (infoCache.has(guid)) return infoCache.get(guid);
    const p = (async () => {
      const raw = await fetchText(`${INFO_API}?pid=${guid}`);
      let info;
      try { info = JSON.parse(raw); } catch { throw new Error('视频详情数据格式无效'); }
      if (!info || (info.status && String(info.status) !== '001' && !info.manifest && !info.hls_url)) {
        throw new Error(info && info.tip_msg ? info.tip_msg : '无法获取视频详情');
      }
      const manifest = info.manifest || {};
      const channel = info.play_channel || '';
      const duration = parseFloat(info.video && info.video.totalLength) || 0;
      let source = 'h5e';
      let variants = [];

      if (/CCTV-4K/i.test(channel) && info.hls_url) {
        const url4k = clearVariantUrl(info.hls_url, '4000');
        if (url4k) {
          source = 'clear';
          variants = [{ bandwidth: 4000000, resolution: '3840x2160', url: url4k }];
        }
      }

      if (!variants.length) {
        let encUrl = manifest.hls_h5e_url || manifest.hls_enc_url || manifest.hls_enc2_url || '';
        encUrl = normalizeEncryptedPlaylistUrl(encUrl);
        if (encUrl) {
          // 去掉 maxbr 限制，让主列表返回全部码率
          const masterUrl = encUrl.replace(/([?&])maxbr=\d+&?/, '$1').replace(/[?&]$/, '');
          let master = '';
          try { master = await fetchText(masterUrl); } catch { master = await fetchText(encUrl); }
          variants = parseMaster(master, masterUrl);
        }
      }

      if (!variants.length && info.hls_url) {
        source = 'clear';
        const master = await fetchText(info.hls_url);
        variants = parseMaster(master, info.hls_url);
      }

      if (!variants.length) throw new Error('无法获取视频播放列表');

      const qualities = variants.map(describeVariant)
        .filter((q, i, arr) => arr.findIndex((x) => x.id === q.id) === i)
        .sort((a, b) => b.bandwidth - a.bandwidth);

      return {
        guid,
        title: (info.title || '未命名视频').replace(/^\s*\[视频\]\s*/, '').trim(),
        channel: channel.replace(/高清$/, ''),
        column: info.column || '',
        cover: fixImage(info.image),
        duration,
        publishedAt: info.f_pgmtime || '',
        source,
        encrypted: source === 'h5e',
        qualities,
      };
    })();
    infoCache.set(guid, p);
    p.catch(() => infoCache.delete(guid));
    return p;
  }

  /** 获取指定清晰度的切片列表 */
  async function getSegments(quality, signal) {
    const text = await fetchText(quality.url, { signal });
    const media = parseMedia(text, quality.url);
    if (!media.segments.length) throw new Error('播放列表中没有切片');
    return media;
  }

  global.CctvApi = {
    fetchText, extractGuidsFromHtml, resolveInput, getVideo, getSegments,
    parseMaster, parseMedia, QUALITY_LABELS,
  };
})(typeof self !== 'undefined' ? self : globalThis);
