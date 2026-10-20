/**
 * 离屏文档：把暂存文件（OPFS / Cache Storage）转换为 blob: URL。
 * 读取到的是磁盘支持的 File/Blob，不会把整个视频载入内存。
 */
var OFF_DIR = 'cctv-downloads';
var OFF_CACHE = 'cctv-downloads';
var offUrls = new Map();

async function readStored(key, backend, mime) {
  if (backend === 'cache') {
    const cache = await caches.open(OFF_CACHE);
    const res = await cache.match(`https://cctv-downloader.local/${encodeURIComponent(key)}`);
    if (!res) throw new Error('暂存文件不存在');
    const blob = await res.blob();
    return new Blob([blob], { type: mime });
  }
  const root = await navigator.storage.getDirectory();
  const dir = await root.getDirectoryHandle(OFF_DIR);
  const handle = await dir.getFileHandle(key);
  const file = await handle.getFile();
  return file.type === mime ? file : file.slice(0, file.size, mime);
}

async function handleOffscreen(msg) {
  {
    if (msg.type === 'createObjectUrl') {
      const blob = await readStored(msg.key, msg.backend, msg.mime);
      const url = URL.createObjectURL(blob);
      offUrls.set(url, msg.key);
      return { ok: true, url, size: blob.size };
    }
    if (msg.type === 'verifySample') {
      // 读取 Service Worker 写入的样本切片并解码校验，完成后删除样本
      const root = await navigator.storage.getDirectory();
      const dir = await root.getDirectoryHandle(OFF_DIR);
      const handle = await dir.getFileHandle(msg.key);
      const bytes = new Uint8Array(await (await handle.getFile()).arrayBuffer());
      try { return { ok: true, result: await self.DecodeVerifier.verify(bytes, msg.maxFrames || 60, msg.videoPid) }; }
      finally { dir.removeEntry(msg.key).catch(() => {}); }
    }
    if (msg.type === 'revoke') {
      if (msg.url) { URL.revokeObjectURL(msg.url); offUrls.delete(msg.url); }
      return { ok: true };
    }
    return { ok: false, error: 'unknown message' };
  }
}
// Firefox 的后台页本身就有 DOM，直接在进程内调用，不需要离屏文档
self.OffscreenImpl = { handle: handleOffscreen };

if (!self.IN_BACKGROUND_PAGE) {
  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (!msg || msg.target !== 'offscreen') return false;
    handleOffscreen(msg).then(sendResponse, (e) => sendResponse({ ok: false, error: e.message || String(e) }));
    return true;
  });
}
