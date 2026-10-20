/**
 * 输出文件暂存（运行于 Service Worker）
 *
 * 解密后的数据边处理边写盘，不在内存中累积整部视频：
 *   - 首选 OPFS（源私有文件系统）FileSystemWritableFileStream
 *   - 回退 Cache Storage（以流式 Response 写入）
 * 最终由离屏文档读取为磁盘支持的 File/Blob 并交给 chrome.downloads。
 */
(function (global) {
  'use strict';

  const DIR = 'cctv-downloads';
  const CACHE = 'cctv-downloads';

  async function opfsDir() {
    const root = await navigator.storage.getDirectory();
    return root.getDirectoryHandle(DIR, { create: true });
  }

  class OpfsWriter {
    static async create(key) {
      const dir = await opfsDir();
      const handle = await dir.getFileHandle(key, { create: true });
      const writable = await handle.createWritable({ keepExistingData: false });
      return new OpfsWriter(key, writable);
    }
    constructor(key, writable) { this.key = key; this.backend = 'opfs'; this.w = writable; this.size = 0; }
    async write(chunk) { if (chunk && chunk.length) { await this.w.write(chunk); this.size += chunk.length; } }
    async close() { await this.w.close(); }
    async abort() { try { await this.w.abort(); } catch (_) {} await removeFile(this.key, 'opfs'); }
  }

  class CacheWriter {
    static async create(key) {
      const cache = await caches.open(CACHE);
      return new CacheWriter(key, cache);
    }
    constructor(key, cache) {
      this.key = key; this.backend = 'cache'; this.size = 0;
      const ts = new TransformStream();
      this.writer = ts.writable.getWriter();
      this.done = cache.put(cacheUrl(key), new Response(ts.readable, { headers: { 'Content-Type': 'application/octet-stream' } }));
      this.done.catch(() => {});
    }
    async write(chunk) { if (chunk && chunk.length) { await this.writer.write(chunk); this.size += chunk.length; } }
    async close() { await this.writer.close(); await this.done; }
    async abort() { try { await this.writer.abort(new Error('cancelled')); } catch (_) {} await removeFile(this.key, 'cache'); }
  }

  function cacheUrl(key) { return `https://cctv-downloader.local/${encodeURIComponent(key)}`; }

  async function createWriter(key) {
    try {
      if (navigator.storage && navigator.storage.getDirectory) return await OpfsWriter.create(key);
    } catch (e) {
      console.warn('[storage] OPFS 不可用，回退 Cache Storage：', e);
    }
    return CacheWriter.create(key);
  }

  async function removeFile(key, backend) {
    try {
      if (backend === 'cache') {
        const cache = await caches.open(CACHE);
        await cache.delete(cacheUrl(key));
      } else {
        const dir = await opfsDir();
        await dir.removeEntry(key);
      }
    } catch (_) { /* 已不存在 */ }
  }

  /** 清理遗留的暂存文件（例如浏览器在下载途中被关闭） */
  async function purge(keepKeys = []) {
    try {
      const dir = await opfsDir();
      for await (const [name] of dir.entries()) {
        if (!keepKeys.includes(name)) await dir.removeEntry(name).catch(() => {});
      }
    } catch (_) {}
    try {
      const cache = await caches.open(CACHE);
      for (const req of await cache.keys()) {
        const key = decodeURIComponent(new URL(req.url).pathname.slice(1));
        if (!keepKeys.includes(key)) await cache.delete(req);
      }
    } catch (_) {}
  }

  global.OutputStorage = { createWriter, removeFile, purge, cacheUrl, DIR, CACHE };
})(typeof self !== 'undefined' ? self : globalThis);
