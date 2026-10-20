/**
 * 下载任务管理器（运行于 Service Worker）
 *
 * 每个任务：解析切片列表 → 并发下载 → 按序合并 + h5e 解密 →（可选）封装 MP4 → 写入暂存文件 → 交给浏览器下载
 */
(function (global) {
  'use strict';

  const { CctvApi, TsPipeline, Mp4Remux, OutputStorage } = global;

  const DEFAULT_SETTINGS = {
    format: 'mp4',        // 固定 MP4（2.4 起移除 TS 选项；内部仍保留 TS 流水线）
    maxJobs: 2,           // 同时进行的任务数
    folder: 'CCTV',       // 下载子目录（空字符串表示直接保存到默认下载目录）
    saveAs: false,        // 每次询问保存位置
    notify: true,         // 下载完成时发送系统通知
  };

  const fetcher = new global.CctvNet.SegmentFetcher();

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function sanitizeFilename(name) {
    return String(name)
      .replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ')
      .replace(/\s+/g, ' ')
      .replace(/^[.\s]+|[.\s]+$/g, '')
      .slice(0, 120) || 'CCTV视频';
  }

  class DownloadManager {
    constructor() {
      this.jobs = new Map();
      this.order = [];
      this.settings = { ...DEFAULT_SETTINGS };
      this.listeners = new Set();
      this.emitTimer = null;
      this.keepAliveTimer = null;
      this.ready = this.restore();
    }

    // ---------- 持久化 ----------
    async restore() {
      const { settings, history } = await chrome.storage.local.get(['settings', 'history']);
      if (settings) this.settings = { ...DEFAULT_SETTINGS, ...settings, format: 'mp4' };
      for (const job of history || []) {
        if (['queued', 'preparing', 'downloading', 'recording', 'saving'].includes(job.status)) {
          job.status = 'failed';
          job.error = '浏览器关闭或扩展重启，任务已中断';
        }
        this.jobs.set(job.id, job);
        this.order.push(job.id);
      }
      await OutputStorage.purge([]);
    }

    persist() {
      const history = this.order.slice(-40).map((id) => {
        const { runtime, ...plain } = this.jobs.get(id);
        return plain;
      });
      chrome.storage.local.set({ history }).catch(() => {});
    }

    async updateSettings(patch) {
      await this.ready;
      this.settings = { ...this.settings, ...patch };
      delete this.settings.concurrency; // 旧版本的"下载速度"设置，已由自动调度取代
      this.settings.format = 'mp4';
      this.settings.maxJobs = Math.min(4, Math.max(1, +this.settings.maxJobs || 2));
      await chrome.storage.local.set({ settings: this.settings });
      this.pump();
      return this.settings;
    }

    // ---------- 状态广播 ----------
    snapshot() {
      return this.order.slice().reverse().map((id) => {
        const { runtime, ...plain } = this.jobs.get(id);
        return plain;
      });
    }

    subscribe(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }

    emit(immediate = false) {
      const fire = () => {
        this.emitTimer = null;
        const jobs = this.snapshot();
        for (const fn of this.listeners) { try { fn(jobs); } catch (_) {} }
        this.updateBadge();
      };
      if (immediate) { clearTimeout(this.emitTimer); fire(); return; }
      if (!this.emitTimer) this.emitTimer = setTimeout(fire, 200);
    }

    updateBadge() {
      // 工具栏进度环（替代文字角标），见 core/ui-signals.js
      if (global.toolbarProgress) global.toolbarProgress.update(this.snapshot());
    }

    // Service Worker 保活：任务进行中定期调用扩展 API，避免空闲回收
    keepAlive() {
      const busy = [...this.jobs.values()].some((j) => ['queued', 'preparing', 'downloading', 'recording', 'saving'].includes(j.status));
      if (busy && !this.keepAliveTimer) {
        this.keepAliveTimer = setInterval(() => chrome.runtime.getPlatformInfo(() => {}), 20000);
      } else if (!busy && this.keepAliveTimer) {
        clearInterval(this.keepAliveTimer);
        this.keepAliveTimer = null;
      }
    }

    // ---------- 任务操作 ----------
    async add({ video, qualityId, format }) {
      await this.ready;
      const quality = video.qualities.find((q) => q.id === qualityId) || video.qualities[0];
      const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
      const job = {
        id,
        guid: video.guid,
        title: video.title,
        cover: video.cover,
        channel: video.channel,
        duration: video.duration,
        encrypted: video.encrypted,
        quality: { id: quality.id, name: quality.name, short: quality.short, url: quality.url },
        format: 'mp4',
        status: 'queued',
        done: 0, total: 0, bytes: 0, speed: 0, eta: 0,
        error: '', filename: '', downloadId: null, size: 0,
        createdAt: Date.now(), finishedAt: 0,
      };
      this.jobs.set(id, job);
      this.order.push(id);
      this.persist();
      this.emit(true);
      this.pump();
      // 封面主色：用于弹出页进度条与工具栏进度环
      if (global.CoverColor && job.cover) {
        global.CoverColor.fromUrl(job.cover).then((c) => { if (c) { job.tint = c; this.emit(); } });
      }
      return job;
    }

    /**
     * 直播录制 / 回看保存
     *   kind = 'live'   ：从现在开始录制，until（毫秒时间戳，可为 0）到点或手动「停止」后保存
     *   kind = 'replay' ：保存 start–end（毫秒）这一时段的回看
     */
    async addLive({ kind, channel, channelName, title, start = 0, end = 0, until = 0, cover = '', meta }) {
      await this.ready;
      const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
      const job = {
        id, kind, guid: '', title, cover, channel: channelName || channel, liveChannel: channel,
        duration: kind === 'replay' ? Math.max(0, (end - start) / 1000) : 0,
        encrypted: false,
        quality: { id: kind, name: kind === 'live' ? '直播录制' : '回看', short: '', url: '' },
        format: 'mp4', status: 'queued',
        done: 0, total: 0, bytes: 0, speed: 0, eta: 0, recorded: 0,
        start, end, until,
        error: '', filename: '', size: 0, createdAt: Date.now(), finishedAt: 0,
        series: (meta && meta.series) || '', pageUrl: (meta && meta.pageUrl) || '',
      };
      this.jobs.set(id, job);
      this.order.push(id);
      this.persist();
      this.emit(true);
      this.pump();
      return job;
    }

    /** 定时录制延长 minutes 分钟（从原结束时间或现在起，取较晚者），最长到开始后 12 小时 */
    extend(id, minutes) {
      const job = this.jobs.get(id);
      if (!job || job.kind !== 'live' || !job.until) throw new Error('该任务不是定时录制');
      const base = Math.max(job.until, Date.now());
      const cap = (job.recordStartedAt || job.createdAt || Date.now()) + 12 * 3600000;
      job.until = Math.min(cap, base + minutes * 60000);
      this.persist();
      this.emit && this.emit();
      return job.until;
    }

    /** 停止直播录制并保存已录内容 */
    stop(id) {
      const job = this.jobs.get(id);
      if (!job) return;
      if (job.status === 'queued') { this.cancel(id); return; }
      if (job.runtime) { job.runtime.stop = true; if (job.runtime.wake) job.runtime.wake(); }
    }

    /** 批量排队时无法解析的条目，以失败任务的形式展示 */
    addFailed({ title, cover, error }) {
      const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
      const job = {
        id, guid: '', title, cover: cover || '', channel: '', duration: 0, encrypted: true,
        quality: { id: '', name: '—', short: '' }, format: this.settings.format,
        status: 'failed', done: 0, total: 0, bytes: 0, speed: 0, eta: 0,
        error, filename: '', downloadId: null, size: 0, createdAt: Date.now(), finishedAt: Date.now(), unresolved: true,
      };
      this.jobs.set(id, job);
      this.order.push(id);
      this.persist();
      this.emit(true);
    }

    cancel(id) {
      const job = this.jobs.get(id);
      if (!job) return;
      if (job.runtime && job.runtime.ctrl) job.runtime.ctrl.abort(new DOMException('已取消', 'AbortError'));
      if (['queued', 'preparing', 'downloading', 'recording', 'saving'].includes(job.status)) {
        job.status = 'cancelled';
        job.finishedAt = Date.now();
      }
      this.persist();
      this.emit(true);
      this.pump();
    }

    retry(id) {
      const job = this.jobs.get(id);
      if (!job || job.unresolved || !['failed', 'cancelled'].includes(job.status)) return;
      Object.assign(job, { status: 'queued', done: 0, total: 0, bytes: 0, speed: 0, eta: 0, error: '', finishedAt: 0, check: null });
      this.order = this.order.filter((x) => x !== id).concat(id);
      this.persist();
      this.emit(true);
      this.pump();
    }

    remove(id) {
      const job = this.jobs.get(id);
      if (!job) return;
      if (['queued', 'preparing', 'downloading', 'recording', 'saving'].includes(job.status)) this.cancel(id);
      this.jobs.delete(id);
      this.order = this.order.filter((x) => x !== id);
      this.persist();
      this.emit(true);
    }

    clearFinished() {
      for (const id of this.order.slice()) {
        const j = this.jobs.get(id);
        if (['completed', 'failed', 'cancelled'].includes(j.status)) { this.jobs.delete(id); }
      }
      this.order = this.order.filter((id) => this.jobs.has(id));
      this.persist();
      this.emit(true);
    }

    pump() {
      const running = [...this.jobs.values()].filter((j) => ['preparing', 'downloading', 'recording', 'saving'].includes(j.status)).length;
      let slots = this.settings.maxJobs - running;
      for (const id of this.order) {
        if (slots <= 0) break;
        const job = this.jobs.get(id);
        if (job.status === 'queued') { slots--; this.run(job); }
      }
      this.keepAlive();
    }

    // ---------- 核心流程 ----------
    async run(job) {
      const ctrl = new AbortController();
      job.runtime = { ctrl };
      job.status = 'preparing';
      this.emit(true);
      let writer = null;
      try {
        let segments = [];
        let duration = 0;
        let session = null;
        let liveStream = null;
        if (job.kind === 'replay') {
          const s = await LiveApi.timeshiftStream(job.liveChannel, job.start, job.end);
          ({ segments, duration } = await LiveApi.getSegments(s.url));
          if (!segments.length) throw new Error('这一时段暂无回看内容（回看一般保留 7 天）');
          if (s.encrypted) session = await CdrmCrypto.createSession();
        } else if (job.kind === 'live') {
          liveStream = await LiveApi.liveStream(job.liveChannel);
          if (liveStream.encrypted) session = await CdrmCrypto.createSession();
        } else {
          ({ segments, duration } = await CctvApi.getSegments(job.quality, ctrl.signal));
        }
        job.runtime.session = session;
        job.total = segments.length;
        job.duration = duration || job.duration;
        job.status = job.kind === 'live' ? 'recording' : 'downloading';
        this.emit(true);

        const ext = job.format === 'ts' ? 'ts' : 'mp4';
        writer = await OutputStorage.createWriter(`${job.id}.${ext}`);
        job.runtime.writer = writer;

        const pipeline = new TsPipeline.TsH5ePipeline({ decrypt: job.encrypted || !!session, session });
        let remux = null;
        const pendingWrites = [];
        if (job.format === 'mp4') {
          remux = new Mp4Remux.Mp4Remuxer((chunk) => pendingWrites.push(chunk), job.duration);
        }
        const sink = async (tsChunk) => {
          if (!tsChunk || !tsChunk.length) return;
          if (remux) {
            remux.push(tsChunk);
            while (pendingWrites.length) await writer.write(pendingWrites.shift());
          } else {
            await writer.write(tsChunk);
          }
        };

        // 解码自检样本：首个、中间、最后一个切片
        const n = segments.length;
        const sampleIdx = new Set([1, Math.floor(n / 2), n - 1]); // 流水线会保留一个切片用于跨切片拼接，push(k) 的输出约为第 k-1 个切片
        const verifications = [];
        job.check = { level: 'pending', message: '' };
        const verify = (bytes, idx) => {
          if (!job.encrypted || !bytes || bytes.length < 188 * 50) return;
          const p = global.Offscreen.verifySample(bytes, 60, pipeline.videoPid).then((r) => {
            if (r && !r.ok && job.check.level !== 'format') {
              job.check = { level: 'decode', message: '解码自检未通过：画面很可能花屏，央视可能更新了加密方式', detail: { segment: idx, ...r } };
              this.emit(true);
            }
            return r;
          });
          verifications.push(p);
        };
        let idx = 0;
        let lastOut = null;
        const onSegment = async (buf) => {
          const out = pipeline.push(buf);
          if (sampleIdx.has(idx) && idx !== n - 1) verify(out.slice(), idx);
          if (idx === n - 1) lastOut = out.slice();
          // 结构自检：一旦发现加密格式变化，立即提示
          if (idx === 1 || idx % 10 === 0) {
            const c = pipeline.selfCheck();
            if (c.level === 'format' && job.check.level !== 'format') { job.check = c; this.emit(true); }
          }
          idx++;
          await sink(out);
        };
        if (job.kind === 'live') await this.recordLive(job, liveStream, ctrl.signal, onSegment);
        else await this.fetchInOrder(job, segments, ctrl.signal, onSegment);
        if (job.kind === 'live' && !job.done) throw new Error('没有录到任何内容');

        const tail = pipeline.end();
        if (lastOut) {
          const merged = new Uint8Array(lastOut.length + tail.length);
          merged.set(lastOut); merged.set(tail, lastOut.length);
          verify(merged, n - 1);
        }
        await sink(tail);
        if (remux) {
          remux.end();
          while (pendingWrites.length) await writer.write(pendingWrites.shift());
        }
        if ((job.encrypted || session) && pipeline.stats.nals === 0) throw new Error('解密失败：未处理任何视频数据');
        if (session) console.info('[downloader] cdrm', JSON.stringify(session.stats));
        if (session && session.stats.nals > 50 && session.stats.changed === 0) throw new Error('直播流解密失败：央视可能更新了加密方式');

        // 汇总自检结果
        const results = await Promise.race([Promise.all(verifications), sleep(20000).then(() => [])]);
        const structural = pipeline.selfCheck();
        if (job.check.level === 'pending' || job.check.level === 'ok') {
          if (structural.level !== 'ok') job.check = structural;
          else job.check = { level: 'ok', message: '', samples: results.filter((r) => r && !r.skipped).length };
        }
        console.info('[downloader] 自检', JSON.stringify(job.check), JSON.stringify(results));

        job.status = 'saving';
        job.size = writer.size;
        this.emit(true);
        await writer.close();

        const folder = (this.settings.folder || '').replace(/^[\\/]+|[\\/]+$/g, '').replace(/[:*?"<>|]/g, '');
        const base = sanitizeFilename(`${job.title}_${job.quality.name}`);
        job.filename = `${folder ? folder + '/' : ''}${base}.${ext}`;
        job.downloadId = await global.Offscreen.saveFile({
          key: writer.key,
          backend: writer.backend,
          filename: job.filename,
          mime: ext === 'mp4' ? 'video/mp4' : 'video/mp2t',
          saveAs: !!this.settings.saveAs,
        });
        job.status = 'completed';
        job.finishedAt = Date.now();
      } catch (err) {
        const aborted = ctrl.signal.aborted || (err && err.name === 'AbortError');
        if (writer) await writer.abort();
        if (aborted) {
          job.status = 'cancelled';
        } else {
          console.error('[downloader]', err);
          job.status = 'failed';
          job.error = (err && err.message) || String(err);
        }
        job.finishedAt = Date.now();
      } finally {
        if (job.runtime && job.runtime.session) job.runtime.session.dispose();
        delete job.runtime;
        this.persist();
        this.emit(true);
        this.pump();
      }
    }

    /**
     * 直播录制：反复拉取滚动的媒体列表，按序号取新切片，直到「停止」、到达 until 或直播结束。
     * 切片按顺序逐个下载（直播每 4 秒才产生一个切片，不需要并发），失败自动重试。
     */
    async recordLive(job, stream, signal, onSegment) {
      let lastSeq = -1;
      let failures = 0;
      let switched = false;
      const speedWin = [];
      const waitFor = (ms) => new Promise((resolve) => {
        const t = setTimeout(done, ms);
        function done() { clearTimeout(t); if (job.runtime) job.runtime.wake = null; resolve(); }
        if (job.runtime) job.runtime.wake = done;
        signal.addEventListener('abort', done, { once: true });
      });
      const fetchSeg = async (url) => {
        for (let attempt = 0; ; attempt++) {
          try {
            const res = await fetch(url, { signal, cache: 'no-store', credentials: 'omit' });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return new Uint8Array(await res.arrayBuffer());
          } catch (e) {
            if (signal.aborted || attempt >= 4) throw e;
            await sleep(800 * (attempt + 1));
          }
        }
      };
      const shouldStop = () => signal.aborted || (job.runtime && job.runtime.stop) || (job.until && Date.now() >= job.until);
      job.recordStartedAt = Date.now();
      while (!shouldStop()) {
        let list;
        try {
          const text = await CctvApi.fetchText(stream.url, { signal });
          list = LiveApi.parseSegments(text, stream.url);
          failures = 0;
        } catch (e) {
          if (signal.aborted) throw e;
          failures++;
          // 当前线路连续失败：重新向接口要地址并换到其它线路（CDN 域名会变化，不能写死）
          if (failures % 4 === 0) {
            try {
              stream = await LiveApi.liveStream(job.liveChannel, { avoid: stream.master });
              switched = true;
              console.warn('[live] 已切换线路', stream.master);
            } catch (_) {}
          }
          if (failures > 20) throw new Error(`直播流中断：${e.message || e}`);
          await waitFor(3000);
          continue;
        }
        // 换线后各线路的切片序号不同：从新线路的最新切片接着录
        if (switched && list.segments.length) {
          lastSeq = list.segments[list.segments.length - 1].seq - 1;
          job.gaps = (job.gaps || 0) + 1;
          switched = false;
        }
        for (const seg of list.segments) {
          if (seg.seq <= lastSeq) continue;
          if (shouldStop()) break;
          if (lastSeq >= 0 && seg.seq > lastSeq + 1) job.gaps = (job.gaps || 0) + (seg.seq - lastSeq - 1);
          let buf;
          try { buf = await fetchSeg(seg.url); } catch (e) {
            if (signal.aborted) throw e;
            job.gaps = (job.gaps || 0) + 1; lastSeq = seg.seq; continue;
          }
          const now = Date.now();
          speedWin.push([now, buf.length]);
          while (speedWin.length && now - speedWin[0][0] > 8000) speedWin.shift();
          job.speed = speedWin.reduce((a, x) => a + x[1], 0) / 8;
          job.bytes += buf.length;
          job.recorded += seg.duration;
          job.duration = job.recorded;
          job.done++;
          job.eta = job.until ? Math.max(0, (job.until - now) / 1000) : 0;
          await onSegment(buf);
          lastSeq = seg.seq;
          this.emit();
        }
        if (list.ended) break;
        await waitFor(Math.max(1500, (list.target || 4) * 500));
      }
      if (signal.aborted) throw new DOMException('已取消', 'AbortError');
    }

    /** 并发下载切片（自适应并发 + 尾延迟对冲 + 重试，见 core/net.js），按顺序回调 onSegment */
    async fetchInOrder(job, segments, signal, onSegment) {
      const started = Date.now();
      const speedWin = [];
      const track = (bytes) => {
        const now = Date.now();
        speedWin.push([now, bytes]);
        while (speedWin.length && now - speedWin[0][0] > 4000) speedWin.shift();
        const span = Math.max(1, now - (speedWin.length ? speedWin[0][0] : started)) / 1000;
        job.speed = speedWin.reduce((sum, x) => sum + x[1], 0) / Math.max(span, 1);
        const avgSeg = job.bytes / Math.max(1, job.fetched);
        job.eta = job.speed > 0 ? ((job.total - job.fetched) * avgSeg) / job.speed : 0;
      };
      job.fetched = 0;
      const stats = await fetcher.fetchInOrder(
        segments.map((x) => x.url),
        signal,
        async (buf) => {
          await onSegment(buf);
          job.done++;
          this.emit();
        },
        ({ bytes }) => { job.bytes += bytes; job.fetched++; track(bytes); },
      );
      console.info('[downloader] 网络统计', stats);
    }
  }

  global.DownloadManager = DownloadManager;
  global.sanitizeFilename = sanitizeFilename;
})(typeof self !== 'undefined' ? self : globalThis);
