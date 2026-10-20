/**
 * 解码自检（离屏文档）：用 WebCodecs VideoDecoder 实际解码解密后的样本切片。
 * 解密规则一旦失效，解码器会直接报 "Decoding error"（实测：密钥或变换出错时 500 帧中仅能解出 0~6 帧）。
 * 只解码每个样本开头的一段（默认 60 帧），耗时很短。
 */
(function () {
  'use strict';
  const TS = 188;

  /** 从 TS 中提取视频 PID 的 ES 数据 */
  function demuxVideo(d, knownPid = -1) {
    let pmtPid = -1, vpid = knownPid >= 0 ? knownPid : -1;
    const pes = [];
    let cur = null;
    for (let o = 0; o + TS <= d.length; o += TS) {
      if (d[o] !== 0x47) continue;
      const pusi = (d[o + 1] & 0x40) !== 0;
      const pid = ((d[o + 1] & 0x1f) << 8) | d[o + 2];
      const afc = (d[o + 3] >> 4) & 3;
      let p = o + 4;
      if (afc & 2) p += 1 + d[o + 4];
      if (!(afc & 1) || p >= o + TS) continue;
      if (pid === 0 && pusi) {
        const q = p + 1 + d[p];
        pmtPid = ((d[q + 10] & 0x1f) << 8) | d[q + 11];
      } else if (pid === pmtPid && pusi && vpid < 0) {
        const q = p + 1 + d[p];
        const secLen = ((d[q + 1] & 0x0f) << 8) | d[q + 2];
        const infoLen = ((d[q + 10] & 0x0f) << 8) | d[q + 11];
        let r = q + 12 + infoLen;
        const end = q + 3 + secLen - 4;
        while (r + 5 <= end) {
          const st = d[r], epid = ((d[r + 1] & 0x1f) << 8) | d[r + 2], esl = ((d[r + 3] & 0x0f) << 8) | d[r + 4];
          if (st === 0x1b && vpid < 0) vpid = epid;
          r += 5 + esl;
        }
      } else if (pid === vpid) {
        if (pusi) { if (cur) pes.push(cur); cur = []; }
        if (cur) cur.push(d.subarray(p, o + TS));
      }
    }
    if (cur) pes.push(cur);
    return pes.map((parts) => {
      let n = 0; for (const x of parts) n += x.length;
      const b = new Uint8Array(n); let w = 0; for (const x of parts) { b.set(x, w); w += x.length; }
      const skip = b.length > 9 && b[0] === 0 && b[1] === 0 && b[2] === 1 ? 9 + b[8] : 0;
      return b.subarray(skip);
    });
  }

  function splitNals(es) {
    const st = [];
    for (let i = 0; i + 3 < es.length; i++) {
      if (es[i] === 0 && es[i + 1] === 0 && es[i + 2] === 1) { st.push(i + 3); i += 2; }
    }
    return st.map((s, k) => {
      let e = k + 1 < st.length ? st[k + 1] - 3 : es.length;
      while (e > s && es[e - 1] === 0) e--;
      return es.subarray(s, e);
    }).filter((n) => n.length);
  }

  async function verify(bytes, maxFrames = 60, videoPid = -1) {
    if (typeof VideoDecoder === 'undefined') return { ok: true, skipped: 'unsupported' };
    // 每个 PES 即一个访问单元
    const aus = [];
    let sps = null;
    for (const es of demuxVideo(bytes, videoPid)) {
      // 只保留 SPS/PPS/切片：部分节目在 SPS 之前放了缓冲期 SEI，Chromium 的解析器会因此拒绝整段码流
      const nals = splitNals(es).filter((n) => { const t = n[0] & 0x1f; return t === 1 || t === 5 || t === 7 || t === 8; });
      if (!nals.some((n) => { const t = n[0] & 0x1f; return t === 1 || t === 5; })) continue;
      const s = nals.find((n) => (n[0] & 0x1f) === 7);
      if (s && !sps) sps = s;
      const key = nals.some((n) => (n[0] & 0x1f) === 5);
      if (!aus.length && !key) continue; // 从第一个关键帧开始
      aus.push({ nals, key });
      if (aus.length >= maxFrames) break;
    }
    if (!aus.length || !sps) return { ok: true, skipped: 'no-video' };
    const hex = (v) => v.toString(16).padStart(2, '0');
    const codec = `avc1.${hex(sps[1])}${hex(sps[2])}${hex(sps[3])}`;
    try {
      const sup = await VideoDecoder.isConfigSupported({ codec });
      if (!sup.supported) return { ok: true, skipped: 'codec ' + codec };
    } catch (_) { return { ok: true, skipped: 'codec ' + codec }; }

    let frames = 0;
    const errors = [];
    const dec = new VideoDecoder({ output: (f) => { frames++; f.close(); }, error: (e) => errors.push(e.message || String(e)) });
    dec.configure({ codec, optimizeForLatency: true, hardwareAcceleration: 'prefer-software' });
    let ts = 0;
    for (const au of aus) {
      if (dec.state === 'closed') break;
      let len = 0; for (const n of au.nals) len += 4 + n.length;
      const buf = new Uint8Array(len); let o = 0;
      for (const n of au.nals) { buf.set([0, 0, 0, 1], o); o += 4; buf.set(n, o); o += n.length; }
      try { dec.decode(new EncodedVideoChunk({ type: au.key ? 'key' : 'delta', timestamp: ts, data: buf })); }
      catch (e) { errors.push(e.message); break; }
      ts += 40000;
    }
    try { await dec.flush(); } catch (e) { if (!errors.length) errors.push(e.message); }
    try { if (dec.state !== 'closed') dec.close(); } catch (_) {}
    const ok = !errors.length && frames >= Math.floor(aus.length * 0.9);
    return { ok, frames, aus: aus.length, codec, error: errors[0] || '' };
  }

  self.DecodeVerifier = { verify, demuxVideo, splitNals };
})();
