/**
 * TS → MP4 封装（基于 mux.js，运行于 Service Worker）
 * 输入为按切片对齐、已解密的 TS 数据块；输出为分片 MP4（fMP4）数据块。
 */
(function (global) {
  'use strict';

  const BOX_CONTAINERS = new Set(['moov', 'trak', 'mdia', 'mvex', 'minf', 'stbl', 'edts']);

  function u32(b, o) { return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0; }
  function w32(b, o, v) { b[o] = (v >>> 24) & 0xff; b[o + 1] = (v >>> 16) & 0xff; b[o + 2] = (v >>> 8) & 0xff; b[o + 3] = v & 0xff; }
  function type(b, o) { return String.fromCharCode(b[o + 4], b[o + 5], b[o + 6], b[o + 7]); }

  /**
   * mux.js 生成的 init 段时长字段为 0xFFFFFFFF（未知），部分播放器会显示错误的总时长。
   * 已知总时长时，回填 mvhd / tkhd / mdhd 的 duration。
   */
  function patchInitDuration(init, seconds) {
    // seconds = 0：时长未知（直播录制），写 0 而不是 0xFFFFFFFF，播放器会按分片计算时长
    if (seconds == null || !(seconds >= 0)) return init;
    const b = new Uint8Array(init);
    let movieScale = 1000;
    const walk = (start, end, mdhdScaleRef) => {
      let o = start;
      while (o + 8 <= end) {
        const size = u32(b, o);
        if (size < 8 || o + size > end) break;
        const t = type(b, o);
        if (t === 'mvhd') {
          const v = b[o + 8];
          if (v === 0) { movieScale = u32(b, o + 20); w32(b, o + 24, Math.round(seconds * movieScale)); }
        } else if (t === 'tkhd') {
          if (b[o + 8] === 0) w32(b, o + 28, Math.round(seconds * movieScale));
        } else if (t === 'mdhd') {
          if (b[o + 8] === 0) { const sc = u32(b, o + 20); w32(b, o + 24, Math.round(seconds * sc)); }
        } else if (BOX_CONTAINERS.has(t)) {
          walk(o + 8, o + size);
        }
        o += size;
      }
    };
    walk(0, b.length);
    return b;
  }

  class Mp4Remuxer {
    /** @param {(chunk: Uint8Array) => void} onChunk  @param {number} durationSec */
    constructor(onChunk, durationSec) {
      if (!global.muxjs || !global.muxjs.Transmuxer) throw new Error('mux.js 未加载');
      this.onChunk = onChunk;
      this.duration = durationSec;
      this.initWritten = false;
      this.tm = new global.muxjs.Transmuxer({ keepOriginalTimestamps: false, remux: true });
      this.tm.on('data', (seg) => {
        if (!this.initWritten && seg.initSegment && seg.initSegment.byteLength) {
          this.onChunk(patchInitDuration(seg.initSegment, this.duration || 0));
          this.initWritten = true;
        }
        if (seg.data && seg.data.byteLength) this.onChunk(new Uint8Array(seg.data));
      });
    }
    /** 推入一个从关键帧开始的 TS 数据块 */
    push(tsChunk) {
      if (!tsChunk || !tsChunk.length) return;
      this.tm.push(tsChunk);
      this.tm.flush();
    }
    end() { this.tm.flush(); }
  }

  global.Mp4Remux = { Mp4Remuxer, patchInitDuration };
})(typeof self !== 'undefined' ? self : globalThis);
