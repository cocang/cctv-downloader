// 网络层故障注入测试：随机延迟、5xx/403、卡死、截断；验证按序、完整、并发不超限
require('./lib/load')('core/net.js');
const { SegmentFetcher, HostScheduler } = globalThis.CctvNet;
const N = +process.env.N || 150;
let active = 0, maxActive = 0, calls = 0;
const rnd = (a, b) => a + Math.random() * (b - a);
function mockFetch(url, { signal }) {
  calls++;
  const idx = +url.match(/(\d+)\.ts/)[1];
  const body = new Uint8Array(1000 + idx).fill(idx & 255);
  return new Promise((resolve, reject) => {
    active++; maxActive = Math.max(maxActive, active);
    let done = false; const end = () => { if (!done) { done = true; active--; } };
    signal.addEventListener('abort', () => { end(); reject(signal.reason); }, { once: true });
    const r = Math.random();
    if (r < 0.01) return; // 卡死，直到超时/对冲取消
    setTimeout(() => {
      if (signal.aborted) return;
      if (r < 0.09) { end(); return resolve({ ok: false, status: 503, headers: new Map() }); }
      if (r < 0.11) { end(); return resolve({ ok: false, status: 403, headers: new Map() }); }
      const truncated = r < 0.13;
      const out = truncated ? body.subarray(0, 500) : body;
      let sent = false;
      resolve({ ok: true, status: 200, headers: { get: () => String(body.length) },
        body: { getReader: () => ({ read: async () => { await new Promise(r => setTimeout(r, rnd(5, 40))); if (sent) { end(); return { done: true }; } sent = true; return { done: false, value: out }; } }) } });
    }, Math.random() < 0.05 ? rnd(2000, 5000) : rnd(30, 300));
  });
}
(async () => {
  const f = new SegmentFetcher({ fetchImpl: mockFetch, scheduler: new HostScheduler() });
  const urls = Array.from({ length: N }, (_, i) => `https://cdn.test/${i}.ts`);
  const t0 = Date.now(); let expect = 0, bad = 0;
  const stats = await f.fetchInOrder(urls, new AbortController().signal, async (buf, i) => {
    if (i !== expect++ || buf.length !== 1000 + i || buf[0] !== (i & 255)) bad++;
  });
  const ok = bad === 0 && expect === N && maxActive <= 12;
  console.log(`${ok ? 'PASS' : 'FAIL'} segments=${expect}/${N} bad=${bad} maxConcurrent=${maxActive} requests=${calls} retries=${stats.retries} hedges=${stats.hedges} hedgeWins=${stats.hedgeWins} time=${((Date.now() - t0) / 1000).toFixed(1)}s`);
  process.exit(ok ? 0 : 1);
})().catch(e => { console.log('FAIL', e.message); process.exit(1); });
