/**
 * 运行环境适配（页面与 Web Worker 共用）
 *
 * 扩展里的核心代码直接 fetch 央视网接口；在应用里，页面运行在 http://127.0.0.1（本机服务），
 * 跨域请求统一改走本机代理 /p。不同上游主机分散到不同端口（每个来源最多 6 条连接），
 * 保持与扩展一致的切片并发度。
 */
(function (g) {
  'use strict';

  function readParams() {
    // 页面：#t=令牌&p=端口列表；Worker：脚本地址 ?t=…&p=…
    const src = (g.document ? g.location.hash.slice(1) : g.location.search.slice(1)) || '';
    const q = new URLSearchParams(src);
    const ports = (q.get('p') || '').split(',').map((x) => +x).filter(Boolean);
    return { token: q.get('t') || '', ports: ports.length ? ports : [+g.location.port || 80] };
  }

  const P = readParams();
  const ORIGIN = g.location.origin;

  function hash(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
    return Math.abs(h);
  }

  function proxyUrl(url, authKey) {
    let host = '';
    try { host = new URL(url).host; } catch (_) {}
    const port = P.ports[hash(host) % P.ports.length];
    return `http://127.0.0.1:${port}/p?t=${P.token}&u=${encodeURIComponent(url)}${authKey ? '&ak=' + encodeURIComponent(authKey) : ''}`;
  }

  function apiUrl(path, params = {}) {
    const q = new URLSearchParams({ t: P.token, ...params });
    return `http://127.0.0.1:${P.ports[0]}${path}?${q}`;
  }

  const nativeFetch = g.fetch.bind(g);
  g.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || String(input);
    if (/^https?:\/\//i.test(url) && !url.startsWith(ORIGIN + '/') && !/^http:\/\/127\.0\.0\.1:\d+\/(p|f\/)/.test(url)) {
      // 自定义请求头（直播接口的 auth-key）由本机代理代发，避免跨域预检
      let ak = '';
      if (init && init.headers) {
        const h = init.headers;
        ak = (typeof h.get === 'function' ? h.get('auth-key') : h['auth-key']) || '';
        if (ak) { init = { ...init }; delete init.headers; }
      }
      return nativeFetch(proxyUrl(url, ak), init);
    }
    return nativeFetch(input, init);
  };

  g.AppEnv = { token: P.token, ports: P.ports, proxyUrl, apiUrl, params: P };
})(typeof self !== 'undefined' ? self : globalThis);
