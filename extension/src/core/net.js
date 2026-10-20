/**
 * 切片下载网络层（运行于 Service Worker，可在 Node 中单测）
 *
 * 1. 自适应并发（按主机，所有任务共享）
 *    - 央视 CDN 只支持 HTTP/1.1，Chrome 对同一主机最多 6 条连接；"带凭据 / 不带凭据"的请求走两个独立连接池，
 *      因此最多可用 12 条连接。
 *    - 但并发并非越高越好：冷门视频的切片不在 CDN 边缘缓存，每个请求都要回源，并发过高会被限流（403/429/5xx）
 *      或回源超时。这里采用 AIMD：从 6 起步，连续成功则 +1（上限 12），出错则降到 60% 并短暂冷却。
 * 2. 尾延迟对冲（hedged request）
 *    - 切片必须按顺序合并，排在最前面的那个切片一旦卡住，后面所有已下载的切片都要等它。
 *      当它的耗时明显超过近期中位数时，另发一个相同请求，谁先完成用谁。这对小文件（切片少、单个请求延迟占主导）
 *      提速最明显。
 * 3. 有韧性的重试
 *    - 每个切片最多 8 次，指数退避（上限 15 秒）+ 随机抖动；404/410 视为永久错误立即失败。
 *    - 超时分为"等待响应头 15 秒"和"传输中 20 秒无数据"，大切片在慢网下不会被误判。
 */
(function (global) {
  'use strict';

  const MIN_LIMIT = 2;
  const MAX_LIMIT = 12;
  const START_LIMIT = 6;
  const PER_LANE = 6;
  const MAX_ATTEMPTS = 8;
  const HEADER_TIMEOUT = 15000;
  const IDLE_TIMEOUT = 20000;
  const WINDOW_AHEAD = 32;

  const sleep = (ms, signal) => new Promise((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    if (signal) signal.addEventListener('abort', () => { clearTimeout(t); reject(signal.reason); }, { once: true });
  });

  class HttpError extends Error {
    constructor(status) {
      super(`网络错误（HTTP ${status}）`);
      this.status = status;
      this.permanent = status === 404 || status === 410;
    }
  }

  // ---------------------------------------------------------------------------
  // 主机调度器
  // ---------------------------------------------------------------------------
  class HostScheduler {
    constructor() { this.hosts = new Map(); }

    state(host) {
      let s = this.hosts.get(host);
      if (!s) {
        s = { limit: START_LIMIT, lanes: [0, 0], waiters: [], streak: 0, errRun: 0, cooldownUntil: 0, wakeTimer: 0, noInclude: false };
        this.hosts.set(host, s);
      }
      return s;
    }

    inflight(s) { return s.lanes[0] + s.lanes[1]; }

    /** 申请一个连接名额。priority 越小越优先（对冲请求 / 排在前面的切片优先）。 */
    acquire(url, signal, priority = 0) {
      const s = this.state(new URL(url).host);
      return new Promise((resolve, reject) => {
        const waiter = { priority, resolve, reject, signal };
        const onAbort = () => {
          const i = s.waiters.indexOf(waiter);
          if (i >= 0) s.waiters.splice(i, 1);
          reject(signal.reason);
        };
        waiter.cleanup = () => signal && signal.removeEventListener('abort', onAbort);
        if (signal) {
          if (signal.aborted) return reject(signal.reason);
          signal.addEventListener('abort', onAbort, { once: true });
        }
        // 按优先级插入
        let i = s.waiters.findIndex((w) => w.priority > priority);
        if (i < 0) i = s.waiters.length;
        s.waiters.splice(i, 0, waiter);
        this.drain(s);
      });
    }

    drain(s) {
      const now = Date.now();
      if (now < s.cooldownUntil) {
        if (!s.wakeTimer) s.wakeTimer = setTimeout(() => { s.wakeTimer = 0; this.drain(s); }, s.cooldownUntil - now);
        return;
      }
      while (s.waiters.length && this.inflight(s) < s.limit) {
        const lane = s.noInclude || s.lanes[0] <= s.lanes[1] ? 0 : 1;
        if (s.lanes[lane] >= PER_LANE) break;
        const w = s.waiters.shift();
        w.cleanup();
        s.lanes[lane]++;
        let released = false;
        w.resolve({
          credentials: lane === 0 ? 'omit' : 'include',
          release: (outcome) => {
            if (released) return;
            released = true;
            s.lanes[lane]--;
            this.report(s, outcome);
            this.drain(s);
          },
        });
      }
    }

    report(s, outcome) {
      if (outcome === 'ok') {
        s.errRun = 0;
        if (++s.streak >= s.limit && s.limit < MAX_LIMIT) { s.limit++; s.streak = 0; }
      } else if (outcome === 'error') {
        s.streak = 0;
        s.errRun++;
        s.limit = Math.max(MIN_LIMIT, Math.floor(s.limit * 0.6));
        const pause = Math.min(8000, 500 * Math.pow(2, s.errRun - 1));
        s.cooldownUntil = Math.max(s.cooldownUntil, Date.now() + pause);
      }
      // 'cancel'：被对冲取消或用户取消，不计入
    }

    /**
     * 带凭据的请求在没有主机权限的 CDN 上会被 CORS 拦截（响应头 Access-Control-Allow-Origin: * 不允许带凭据）。
     * 一旦发现，立即对该主机停用这条通道，只用不带凭据的 6 条连接，避免一半请求反复失败。
     */
    disableInclude(url) {
      const s = this.state(new URL(url).host);
      if (!s.noInclude) { s.noInclude = true; console.warn('[net] 已停用带凭据通道：', new URL(url).host); }
    }

    snapshot(host) { const s = this.hosts.get(host); return s ? { limit: s.limit, inflight: this.inflight(s) } : null; }
  }

  // ---------------------------------------------------------------------------
  // 单次请求 + 重试
  // ---------------------------------------------------------------------------
  async function fetchOnce(fetchImpl, url, signal, credentials) {
    const ctrl = new AbortController();
    const onAbort = () => ctrl.abort(signal.reason);
    signal.addEventListener('abort', onAbort, { once: true });
    let timer = setTimeout(() => ctrl.abort(new Error('网络超时（无响应）')), HEADER_TIMEOUT);
    const arm = () => { clearTimeout(timer); timer = setTimeout(() => ctrl.abort(new Error('网络超时（传输中断）')), IDLE_TIMEOUT); };
    try {
      const res = await fetchImpl(url, { signal: ctrl.signal, cache: 'no-store', credentials });
      if (!res.ok) throw new HttpError(res.status);
      arm();
      const total = +res.headers.get('content-length') || 0;
      const reader = res.body.getReader();
      const chunks = [];
      let size = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        arm();
        chunks.push(value);
        size += value.byteLength;
      }
      if (!size) throw new Error('数据为空');
      if (total && size !== total) throw new Error('数据不完整');
      if (chunks.length === 1) return chunks[0];
      const buf = new Uint8Array(size);
      let o = 0;
      for (const c of chunks) { buf.set(c, o); o += c.byteLength; }
      return buf;
    } catch (e) {
      if (signal.aborted) throw signal.reason;
      throw (ctrl.signal.aborted && ctrl.signal.reason) || e;
    } finally {
      clearTimeout(timer);
      signal.removeEventListener('abort', onAbort);
    }
  }

  class SegmentFetcher {
    constructor({ fetchImpl, scheduler } = {}) {
      this.fetchImpl = fetchImpl || ((...a) => fetch(...a));
      this.scheduler = scheduler || new HostScheduler();
    }

    async fetch(url, signal, priority = 0, stats, onStart) {
      let lastErr;
      for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        if (signal.aborted) throw signal.reason;
        const slot = await this.scheduler.acquire(url, signal, priority);
        if (onStart) onStart();
        try {
          const buf = await fetchOnce(this.fetchImpl, url, signal, slot.credentials);
          slot.release('ok');
          return buf;
        } catch (e) {
          if (signal.aborted) { slot.release('cancel'); throw signal.reason; }
          if (slot.credentials === 'include' && e instanceof TypeError) {
            // CORS / 网络层拒绝：换到另一条通道立即重试，不计入失败
            this.scheduler.disableInclude(url);
            slot.release('cancel');
            attempt--;
            continue;
          }
          slot.release('error');
          lastErr = e;
          if (stats) stats.retries++;
          if (e && e.permanent) break;
          await sleep(Math.min(15000, 800 * Math.pow(2, attempt)) * (0.7 + Math.random() * 0.6), signal);
        }
      }
      throw lastErr;
    }

    /**
     * 并发下载切片并按顺序回调 onSegment(buf, index)。
     * onProgress({ bytes }) 在每个切片下载完成时调用。
     */
    fetchInOrder(urls, signal, onSegment, onProgress) {
      return new Promise((resolve, reject) => {
        const n = urls.length;
        const results = new Map();
        const pending = new Map(); // index -> { started, hedged, ctrls: [] }
        const durations = [];
        const stats = { retries: 0, hedges: 0, hedgeWins: 0 };
        let nextFetch = 0;
        let nextProcess = 0;
        let processing = false;
        let finished = false;

        const finish = (err) => {
          if (finished) return;
          finished = true;
          clearInterval(watchdog);
          for (const p of pending.values()) p.ctrls.forEach((c) => c.abort(new DOMException('结束', 'AbortError')));
          pending.clear();
          err ? reject(err) : resolve(stats);
        };
        signal.addEventListener('abort', () => finish(signal.reason || new DOMException('已取消', 'AbortError')), { once: true });

        const median = () => {
          if (!durations.length) return 0;
          const a = durations.slice(-20).sort((x, y) => x - y);
          return a[a.length >> 1];
        };

        const launch = (idx, hedge) => {
          const p = pending.get(idx);
          const ctrl = new AbortController();
          const relay = () => ctrl.abort(signal.reason);
          signal.addEventListener('abort', relay, { once: true });
          p.ctrls.push(ctrl);
          // 优先级：对冲请求最高；普通请求按距离处理位置的远近
          const priority = hedge ? -1 : idx - nextProcess;
          this.fetch(urls[idx], ctrl.signal, priority, stats, () => { if (!p.started) p.started = Date.now(); }).then((buf) => {
            signal.removeEventListener('abort', relay);
            if (finished || !pending.has(idx)) return; // 另一路已完成
            pending.delete(idx);
            p.ctrls.forEach((c) => { if (c !== ctrl) c.abort(new DOMException('对冲完成', 'AbortError')); });
            if (hedge) stats.hedgeWins++;
            durations.push(Date.now() - p.started);
            results.set(idx, buf);
            if (onProgress) onProgress({ bytes: buf.byteLength });
            processQueue();
            schedule();
          }, (e) => {
            signal.removeEventListener('abort', relay);
            if (finished || !pending.has(idx)) return;
            // 对冲中的一路失败，只要另一路还在就继续等待
            p.ctrls = p.ctrls.filter((c) => c !== ctrl);
            if (p.ctrls.length) return;
            finish(e);
          });
        };

        const schedule = () => {
          while (!finished && nextFetch < n && nextFetch < nextProcess + WINDOW_AHEAD && pending.size < MAX_LIMIT + 2) {
            const idx = nextFetch++;
            pending.set(idx, { started: 0, hedged: false, ctrls: [] });
            launch(idx, false);
          }
        };

        const processQueue = async () => {
          if (processing || finished) return;
          processing = true;
          try {
            while (!finished && results.has(nextProcess)) {
              const buf = results.get(nextProcess);
              results.delete(nextProcess);
              await onSegment(buf, nextProcess);
              nextProcess++;
            }
            if (!finished && nextProcess >= n) finish();
          } catch (e) {
            finish(e);
          } finally {
            processing = false;
          }
          if (!finished) schedule();
        };

        // 看门狗：排在最前面的 1~2 个切片明显变慢时发起对冲请求
        const watchdog = setInterval(() => {
          if (finished) return;
          const m = median();
          const threshold = Math.max(2500, (m || 2000) * 2.5);
          for (let idx = nextProcess; idx < nextProcess + 2 && idx < n; idx++) {
            const p = pending.get(idx);
            if (p && p.started && !p.hedged && Date.now() - p.started > threshold) {
              p.hedged = true;
              stats.hedges++;
              launch(idx, true);
            }
          }
        }, 400);

        if (!n) return finish();
        schedule();
      });
    }
  }

  global.CctvNet = { HostScheduler, SegmentFetcher, HttpError, fetchOnce, constants: { MIN_LIMIT, MAX_LIMIT, START_LIMIT, MAX_ATTEMPTS } };
})(typeof self !== 'undefined' ? self : globalThis);
