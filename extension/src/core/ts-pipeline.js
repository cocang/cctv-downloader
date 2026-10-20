/**
 * MPEG-TS 流水线：切片合并 + 流式 h5e 解密
 *
 * 处理顺序与参考实现一致：先合并（TSMerger：跳过后续切片的 PAT/PMT、规范化连续计数器），
 * 再对合并后的整条流做 PES 级解密（decrypt_ts_inplace）。
 *
 * 为了不把整部视频读进内存，这里做成"流式"：每推入一个切片，就把已经完整的 PES
 * 解密后立即输出；只保留最后一个尚未结束的视频 PES（通常不到一帧）到下一次推入时再处理。
 * 这样输出结果与"整条流一次性解密"逐字节等价。
 */
(function (global) {
  'use strict';

  const TS = 188;

  /** 解析 PAT/PMT，找出 H.264 视频 PID（默认 0x100） */
  function probeVideoPid(buf) {
    let pmtPid = -1;
    for (let off = 0; off + TS <= buf.length; off += TS) {
      if (buf[off] !== 0x47) continue;
      const pid = ((buf[off + 1] & 0x1f) << 8) | buf[off + 2];
      const pusi = (buf[off + 1] & 0x40) !== 0;
      const afc = (buf[off + 3] >> 4) & 3;
      if (!pusi || afc === 0 || afc === 2) continue;
      let p = off + (afc === 3 ? 5 + buf[off + 4] : 4);
      p += 1 + buf[p]; // pointer_field
      if (pid === 0 && pmtPid < 0) {
        const secLen = ((buf[p + 1] & 0x0f) << 8) | buf[p + 2];
        for (let q = p + 8; q + 4 <= p + 3 + secLen - 4; q += 4) {
          const prog = (buf[q] << 8) | buf[q + 1];
          if (prog !== 0) { pmtPid = ((buf[q + 2] & 0x1f) << 8) | buf[q + 3]; break; }
        }
      } else if (pid === pmtPid) {
        const secLen = ((buf[p + 1] & 0x0f) << 8) | buf[p + 2];
        const end = p + 3 + secLen - 4;
        const infoLen = ((buf[p + 10] & 0x0f) << 8) | buf[p + 11];
        for (let q = p + 12 + infoLen; q + 5 <= end;) {
          const type = buf[q];
          const epid = ((buf[q + 1] & 0x1f) << 8) | buf[q + 2];
          const esLen = ((buf[q + 3] & 0x0f) << 8) | buf[q + 4];
          if (type === 0x1b) return { videoPid: epid, pmtPid };
          q += 5 + esLen;
        }
        return { videoPid: 0x100, pmtPid };
      }
    }
    return { videoPid: 0x100, pmtPid };
  }

  /** 适配域扩展：从该包的负载中"偷"出 need 字节用填充字节替代，返回实际偷到的字节数 */
  function expandAfSteal(data, pktOff, need) {
    if (need === 0) return 0;
    const afc = (data[pktOff + 3] & 0x30) >> 4;
    if (afc === 1) {
      let afLen = need - 1;
      if (afLen > 182) afLen = 182;
      const steal = 1 + afLen;
      const oldPayload = data.slice(pktOff + 4, pktOff + 188);
      data[pktOff + 3] = (data[pktOff + 3] & 0xcf) | 0x30;
      data[pktOff + 4] = afLen;
      if (afLen > 0) {
        data[pktOff + 5] = 0x00;
        for (let i = 1; i < afLen; i++) data[pktOff + 5 + i] = 0xff;
      }
      data.set(oldPayload.subarray(0, 184 - steal), pktOff + 5 + afLen);
      return steal;
    }
    if (afc === 2 || afc === 3) {
      const afLen = data[pktOff + 4];
      const pi = 5 + afLen;
      if (pi >= 188) return 0;
      const oldPayloadLen = 188 - pi;
      let add = need < oldPayloadLen ? need : oldPayloadLen;
      if (add === 0) return 0;
      if (afLen + add > 182) {
        add = 182 - afLen;
        if (add <= 0) return 0;
      }
      const newAfLen = afLen + add;
      const oldPayload = data.slice(pktOff + pi, pktOff + 188);
      for (let i = 0; i < add; i++) data[pktOff + 5 + afLen + i] = 0xff;
      data[pktOff + 4] = newAfLen;
      const newPl = oldPayloadLen - add;
      data.set(oldPayload.subarray(0, newPl), pktOff + 5 + newAfLen);
      data[pktOff + 3] = (data[pktOff + 3] & 0xcf) | (newPl === 0 ? 0x20 : 0x30);
      return add;
    }
    return 0;
  }

  /** 解密一个完整 PES（spans 为其所在的所有 TS 包），原地改写 data */
  function flushPes(data, spans, session, stats) {
    let total = 0;
    for (const s of spans) total += s[2];
    if (!total) return;
    const pes = new Uint8Array(total);
    let w = 0;
    for (const s of spans) { pes.set(data.subarray(s[0] + s[1], s[0] + 188), w); w += s[2]; }

    let baseSkip = 0;
    if (pes.length >= 9 && pes[0] === 0 && pes[1] === 0 && pes[2] === 1) baseSkip = 9 + pes[8];
    if (baseSkip > pes.length) return;
    const es = pes.subarray(baseSkip);
    const n = es.length;

    // 查找 NAL 起始码
    const starts = [];
    let i = 0;
    while (i + 3 < n) {
      if (i + 4 <= n && es[i] === 0 && es[i + 1] === 0 && es[i + 2] === 0 && es[i + 3] === 1) {
        starts.push(i, 4); i += 4;
      } else if (es[i] === 0 && es[i + 1] === 0 && es[i + 2] === 1) {
        starts.push(i, 3); i += 3;
      } else i++;
    }

    let newPes = new Uint8Array(pes.length + 64);
    newPes.set(pes.subarray(0, baseSkip), 0);
    let o = baseSkip;
    let cursor = 0;
    for (let k = 0; k < starts.length; k += 2) {
      const pos = starts[k], sc = starts[k + 1];
      const end = k + 2 < starts.length ? starts[k + 2] : n;
      if (cursor < pos) { newPes.set(es.subarray(cursor, pos), o); o += pos - cursor; }
      newPes.set(es.subarray(pos, pos + sc), o); o += sc;
      if (pos + sc >= end) { cursor = end; continue; }
      const nal = es.slice(pos + sc, end);
      const dec = session.onNal(nal, nal.length);
      stats.nals++;
      if (o + dec.length + (n - end) > newPes.length) {
        const grown = new Uint8Array(newPes.length * 2 + dec.length);
        grown.set(newPes.subarray(0, o)); newPes = grown;
      }
      newPes.set(dec, o); o += dec.length;
      cursor = end;
    }
    if (cursor < n) { newPes.set(es.subarray(cursor), o); o += n - cursor; }
    let outLen = o;
    if (outLen > total) {
      // 解密后 NAL 变长（需要新增防竞争字节，极少见）：PES 末尾的 rbsp_trailing / cabac_zero 填充可吸收，
      // 否则只能截断末尾（统计后上报）。
      stats.grown = (stats.grown || 0) + 1;
      outLen = total;
    }

    if (total > outLen) {
      let remaining = total - outLen;
      for (let k = spans.length - 1; k >= 0 && remaining > 0; k--) {
        remaining -= expandAfSteal(data, spans[k][0], remaining);
      }
      const ns = [];
      for (const s of spans) {
        const pkt = s[0];
        const afc = (data[pkt + 3] & 0x30) >> 4;
        if (afc === 0 || afc === 2) continue;
        const pi = afc === 1 ? 4 : 5 + data[pkt + 4];
        if (pi >= 188) continue;
        ns.push([pkt, pi, 188 - pi]);
      }
      spans = ns;
    }

    let off = 0;
    for (const s of spans) {
      const [pkt, pi, pl] = s;
      let chunk = outLen - off;
      if (chunk > pl) chunk = pl;
      if (chunk > 0) data.set(newPes.subarray(off, off + chunk), pkt + pi);
      if (chunk < pl) data.fill(0xff, pkt + pi + Math.max(chunk, 0), pkt + 188);
      off += pl;
    }
  }

  /**
   * 扫描缓冲区中的视频 PES 并解密。
   * final=false 时，最后一个未结束的 PES 不处理，返回其起点偏移（调用方保留到下次）。
   */
  function decryptScan(data, session, vpid, final, stats) {
    let spans = [];
    for (let off = 0; off + TS <= data.length; off += TS) {
      if (data[off] !== 0x47) continue;
      const pid = ((data[off + 1] & 0x1f) << 8) | data[off + 2];
      if (pid !== vpid) continue;
      const pusi = (data[off + 1] & 0x40) !== 0;
      const afc = (data[off + 3] & 0x30) >> 4;
      if (afc === 0 || afc === 2) continue;
      const pi = afc === 1 ? 4 : 5 + data[off + 4];
      if (pi >= 188) continue;
      if (pusi) { if (spans.length) flushPes(data, spans, session, stats); spans = []; }
      spans.push([off, pi, 188 - pi]);
    }
    if (final) {
      if (spans.length) flushPes(data, spans, session, stats);
      return data.length;
    }
    return spans.length ? spans[0][0] : data.length;
  }

  /**
   * 切片合并 + 流式解密
   *   const p = new TsH5ePipeline({ decrypt: true });
   *   out = p.push(segmentBytes)   // 按顺序推入
   *   out = p.end()                // 结束时取出剩余数据
   */
  class TsH5ePipeline {
    /** session：解密会话，默认点播 h5e；直播 / 回看传入 CdrmCrypto 会话 */
    constructor({ decrypt = true, session = null } = {}) {
      this.decrypt = decrypt;
      this.session = session || (global.H5eCrypto ? new global.H5eCrypto.H5eSession() : null);
      this.first = true;
      this.pmtPid = -1;
      this.videoPid = 0x100;
      this.cc = new Int16Array(8192).fill(-1);
      this.held = null;
      this.prevDone = null;
      this.stats = { nals: 0, segments: 0, bytesIn: 0, bytesOut: 0 };
    }

    /**
     * 解密自检结论。返回 { ok, level, message, detail }：
     *   level = 'format'  加密格式与已知规则不符（很可能是央视更新了加密），画面大概率花屏
     *   level = 'suspect' 少量解密结果异常，个别画面可能有瑕疵
     */
    selfCheck() {
      const c = this.session ? this.session.check : null;
      if (!this.decrypt || !c) return { ok: true, level: 'ok', message: '', detail: null };
      const detail = { ...c, grown: this.stats.grown || 0 };
      if (c.unknownMarkers > 0 || (c.slices > 50 && c.markers === 0) || c.slicesWithoutMarker > 50) {
        return { ok: false, level: 'format', message: '加密格式与已知规则不一致，央视可能更新了加密方式，画面可能花屏', detail };
      }
      if (c.decrypted && (c.invalidOut > 0 || c.anomalies > 0 || detail.grown > 0)) {
        return { ok: false, level: 'suspect', message: '个别画面解密结果异常，可能出现短暂花屏', detail };
      }
      return { ok: true, level: 'ok', message: '', detail };
    }

    /** TSMerger：过滤无效包、跳过后续切片的 PAT/PMT、规范化连续计数器 */
    merge(seg) {
      const out = new Uint8Array(seg.length - (seg.length % TS));
      const ccOffset = new Int16Array(8192).fill(-1);
      let w = 0;
      if (this.first) {
        const p = probeVideoPid(seg);
        this.videoPid = p.videoPid;
        this.pmtPid = p.pmtPid;
      }
      for (let off = 0; off + TS <= seg.length; off += TS) {
        if (seg[off] !== 0x47) continue;
        const pid = ((seg[off + 1] & 0x1f) << 8) | seg[off + 2];
        if (!this.first && (pid === 0 || (this.pmtPid >= 0 && pid === this.pmtPid))) continue;
        const afc = (seg[off + 3] >> 4) & 3;
        if (afc === 0) throw new Error('TS 包 adaptation_field_control 无效');
        out.set(seg.subarray(off, off + TS), w);
        const hasPayload = afc === 1 || afc === 3;
        const hasAf = afc === 2 || afc === 3;
        const disc = hasAf && seg[off + 4] > 0 && (seg[off + 5] & 0x80) !== 0;
        const inCc = seg[off + 3] & 0x0f;
        if (pid === 0x1fff || disc) {
          this.cc[pid] = inCc; ccOffset[pid] = 0;
        } else {
          if (ccOffset[pid] < 0) {
            const last = this.cc[pid];
            const expected = last < 0 ? inCc : hasPayload ? (last + 1) & 0x0f : last;
            ccOffset[pid] = (expected - inCc + 16) & 0x0f;
          }
          const outCc = (inCc + ccOffset[pid]) & 0x0f;
          out[w + 3] = (out[w + 3] & 0xf0) | outCc;
          this.cc[pid] = outCc;
        }
        w += TS;
      }
      this.first = false;
      return out.subarray(0, w);
    }

    /**
     * 推入一个切片，返回可以输出的数据。
     * 输出按切片对齐（延迟一个切片）：第 k 个切片在其最后一个视频 PES 收尾（即第 k+1 个切片到达）时整体输出，
     * 这样每段输出都从关键帧开始，便于后续封装为 MP4。
     */
    push(segment) {
      const seg = segment instanceof Uint8Array ? segment : new Uint8Array(segment);
      this.stats.segments++;
      this.stats.bytesIn += seg.length;
      const merged = this.merge(seg);
      if (!this.decrypt) { this.stats.bytesOut += merged.length; return merged; }

      const held = this.held || EMPTY;
      const joined = concat(held, merged);
      const cut = decryptScan(joined, this.session, this.videoPid, false, this.stats);
      const doneFromHeld = Math.min(cut, held.length);
      const out = concat(this.prevDone || EMPTY, joined.subarray(0, doneFromHeld));
      this.prevDone = cut > held.length ? joined.slice(held.length, cut) : EMPTY;
      this.held = joined.slice(cut);
      this.stats.bytesOut += out.length;
      return out;
    }

    /** 结束：处理剩余数据并全部输出 */
    end() {
      if (!this.decrypt) return EMPTY;
      const held = this.held || EMPTY;
      if (held.length) decryptScan(held, this.session, this.videoPid, true, this.stats);
      const out = concat(this.prevDone || EMPTY, held);
      this.prevDone = null; this.held = null;
      this.stats.bytesOut += out.length;
      return out;
    }
  }

  const EMPTY = new Uint8Array(0);
  function concat(a, b) {
    if (!a.length) return b;
    if (!b.length) return a;
    const r = new Uint8Array(a.length + b.length);
    r.set(a, 0); r.set(b, a.length);
    return r;
  }

  global.TsPipeline = { TsH5ePipeline, probeVideoPid };
})(typeof self !== 'undefined' ? self : globalThis);
