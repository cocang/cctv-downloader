/**
 * 引擎宿主（运行在无界面的 WebView 里）
 *
 * 原生侧：window.Engine.call(id, type, payloadJson) → Worker
 * Worker 的回复和事件：CctvEngine.post(json) → 原生侧
 */
(function () {
  'use strict';
  const N = window.CctvEngine;
  const post = (obj) => { try { N && N.post(JSON.stringify(obj)); } catch (e) { console.error('[host]', e); } };

  let worker = null;
  function start(state) {
    worker = new Worker(`js/worker.js?t=${AppEnv.token}&p=${AppEnv.ports.join(',')}`);
    worker.onmessage = (ev) => post(ev.data || {});
    worker.onerror = (e) => post({ event: 'error', message: e.message || '下载引擎出错' });
    worker.postMessage({ type: 'init', state: state || '{}' });
  }

  window.Engine = {
    call(id, type, payload) {
      let data = {};
      try { data = payload ? JSON.parse(payload) : {}; } catch (_) {}
      worker.postMessage({ id, type, payload: data });
    },
  };

  let state = '{}';
  try { state = (N && N.state()) || '{}'; } catch (_) {}
  start(state);
  post({ event: 'host' });
})();
