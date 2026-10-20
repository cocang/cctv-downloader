/**
 * 离屏文档桥接（Service Worker 端）
 *
 * Service Worker 中没有 URL.createObjectURL，无法直接把暂存文件交给 chrome.downloads。
 * 离屏文档只负责一件事：把 OPFS / Cache 中的暂存文件转换为 blob: URL。
 * 解密、合并、封装全部在 Service Worker 中完成。
 */
(function (global) {
  'use strict';

  const PATH = 'offscreen/offscreen.html';
  let creating = null;
  const pendingDownloads = new Map(); // downloadId -> { key, backend, url }

  async function ensureDocument() {
    if (!chrome.offscreen) return;
    if (chrome.runtime.getContexts) {
      const ctx = await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'], documentUrls: [chrome.runtime.getURL(PATH)] });
      if (ctx.length) return;
    }
    if (creating) return creating;
    creating = chrome.offscreen.createDocument({
      url: PATH,
      reasons: [chrome.offscreen.Reason.BLOBS],
      justification: '将 Service Worker 解密合并完成的视频文件转换为 blob URL 以便保存',
    }).catch((e) => { if (!/single offscreen/i.test(String(e))) throw e; })
      .finally(() => { creating = null; });
    return creating;
  }

  // Firefox：没有 chrome.offscreen，后台页本身带 DOM，直接进程内处理
  const inProcess = () => !chrome.offscreen && global.OffscreenImpl;

  let activeCalls = 0;
  async function call(type, payload) {
    if (inProcess()) {
      const res = await global.OffscreenImpl.handle({ target: 'offscreen', type, ...payload });
      if (!res || !res.ok) throw new Error((res && res.error) || '处理失败');
      return res;
    }
    activeCalls++;
    try { return await callInner(type, payload); } finally { activeCalls--; maybeClose(); }
  }
  function maybeClose() {
    setTimeout(() => {
      if (!pendingDownloads.size && !activeCalls && chrome.offscreen && chrome.offscreen.closeDocument) chrome.offscreen.closeDocument().catch(() => {});
    }, 3000);
  }
  async function callInner(type, payload) {
    await ensureDocument();
    const res = await chrome.runtime.sendMessage({ target: 'offscreen', type, ...payload });
    if (!res || !res.ok) throw new Error((res && res.error) || '离屏文档无响应');
    return res;
  }

  // 早期版本曾在保存时临时关闭浏览器下载界面，这会让工具栏的下载按钮消失。
  // 现在不再干预下载界面；启动时恢复一次，确保旧版本留下的状态被纠正。
  if (chrome.downloads.setUiOptions) chrome.downloads.setUiOptions({ enabled: true }).catch(() => {});

  async function saveFile({ key, backend, filename, mime, saveAs }) {
    const { url } = await call('createObjectUrl', { key, backend, mime });
    let downloadId;
    try {
      downloadId = await chrome.downloads.download({ url, filename, saveAs, conflictAction: 'uniquify' });
    } catch (e) {
      await call('revoke', { url, key, backend }).catch(() => {});
      throw new Error('无法保存文件：' + (e.message || e));
    }
    pendingDownloads.set(downloadId, { key, backend, url });
    return downloadId;
  }

  chrome.downloads.onChanged.addListener(async (delta) => {
    const entry = pendingDownloads.get(delta.id);
    if (!entry || !delta.state) return;
    if (delta.state.current === 'complete' || delta.state.current === 'interrupted') {
      pendingDownloads.delete(delta.id);
      await call('revoke', entry).catch(() => {});
      await global.OutputStorage.removeFile(entry.key, entry.backend);
      maybeClose();
    }
  });

  /**
   * 解码自检：把一段解密后的 TS 样本写入 OPFS，交给离屏文档用 WebCodecs 实际解码。
   * 返回 { ok, frames, aus, codec, error } 或 { ok: true, skipped }。
   */
  async function verifySample(bytes, maxFrames = 60, videoPid = -1) {
    try {
      const root = await navigator.storage.getDirectory();
      const dir = await root.getDirectoryHandle(global.OutputStorage.DIR, { create: true });
      const key = `check-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}.ts`;
      const w = await (await dir.getFileHandle(key, { create: true })).createWritable();
      await w.write(bytes);
      await w.close();
      const res = await call('verifySample', { key, maxFrames, videoPid });
      return res.result;
    } catch (e) {
      return { ok: true, skipped: 'error ' + (e.message || e) };
    }
  }

  global.Offscreen = { saveFile, ensureDocument, verifySample, hasPending: () => pendingDownloads.size > 0 };
})(typeof self !== 'undefined' ? self : globalThis);
