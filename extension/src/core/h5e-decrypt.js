/**
 * CCTV hls_h5e 视频流解密（纯 JavaScript 实现）
 *
 * 移植自 letr007/CCTVVideoDownloader · src/core/src/crypto/cctv_h5e_decrypt.hpp
 * 算法要点：
 *   - NAL type 25 作为加密开关与模式选择（01 09 = 新模式，01 06 = 经典模式）
 *   - 新模式 IDR（type 5）：TEA-16，密钥位于 nal[5..21]，起点 64，步长 F5
 *   - 新模式非 IDR（type 1）：16 步非线性移位变换 G，步长 F1，起点 64
 *   - 经典模式：TEA-16，密钥位于 RBSP[16..32]，起点 32，步长 80
 *   - 全部单元格按 RBSP 下标寻址（跳过 00 00 03 防竞争字节），解密后丢弃仍完整的 0x03
 *   - PES 变短后，用 TS 适配域填充吸收差值，保持 TS 总长度不变
 *
 * 不依赖 WASM、不依赖页面播放器，可直接运行于扩展 Service Worker。
 */
(function (global) {
  'use strict';

  const DELTA = 0x9e3779b9;

  function rd32(b, o) {
    return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
  }
  function wr32(b, o, v) {
    b[o] = v & 0xff; b[o + 1] = (v >>> 8) & 0xff; b[o + 2] = (v >>> 16) & 0xff; b[o + 3] = (v >>> 24) & 0xff;
  }

  /** 标准 TEA-16 解密（小端 32 位字），blk 为 8 字节，原地改写 */
  function teaDecrypt(blk, key) {
    let v0 = rd32(blk, 0), v1 = rd32(blk, 4);
    const k0 = rd32(key, 0), k1 = rd32(key, 4), k2 = rd32(key, 8), k3 = rd32(key, 12);
    let sum = Math.imul(DELTA, 16) >>> 0;
    for (let i = 0; i < 16; i++) {
      v1 = (v1 - ((((v0 << 4) + k2) | 0) ^ ((v0 + sum) | 0) ^ (((v0 >>> 5) + k3) | 0))) >>> 0;
      v0 = (v0 - ((((v1 << 4) + k0) | 0) ^ ((v1 + sum) | 0) ^ (((v1 >>> 5) + k1) | 0))) >>> 0;
      sum = (sum - DELTA) >>> 0;
    }
    wr32(blk, 0, v0); wr32(blk, 4, v1);
  }

  /** RBSP 下标 → EBSP 下标映射，同时收集防竞争字节 (00 00 03) 位置 */
  function buildRbspMap(nal, len, fullTail) {
    const r2e = new Uint32Array(len);
    const epbs = [];
    let n = 0;
    if (fullTail) {
      // 与 decrypt_type1_new / decrypt_classic_grid 一致：遍历到末尾
      for (let i = 0; i < len;) {
        if (i + 2 < len && nal[i] === 0 && nal[i + 1] === 0 && nal[i + 2] === 3) {
          epbs.push(i); r2e[n++] = i; r2e[n++] = i + 1; i += 3;
        } else { r2e[n++] = i; i++; }
      }
    } else {
      // 与 decrypt_type5_new 一致：i + 2 < len（末尾 2 字节不计入）
      for (let i = 0; i + 2 < len; i++) {
        if (nal[i] === 0 && nal[i + 1] === 0 && nal[i + 2] === 3) {
          epbs.push(i); r2e[n++] = i; r2e[n++] = i + 1; i += 2;
        } else { r2e[n++] = i; }
      }
    }
    return { r2e, rbspLen: n, epbs };
  }

  /**
   * 解密后输出：去掉密文中的全部防竞争字节 0x03。
   *
   * 官方加密流程是：在明文 EBSP 上按"去掉 03 后的下标"加密单元格，再对密文做一次防竞争转义。
   * 所以密文中的每个 00 00 03 都是加密时新增的，解密后应全部去掉（明文自身的 03 在密文中表现为 00 00 03 03，
   * 去掉第一个后自然还原）。参考实现只删除"解密后仍完整"的 03，当单元格恰好覆盖 00 00 03 时会残留一个多余字节，
   * 这里与明文流逐字节对照后改为全部删除。
   */
  function stripEpb(nal, r2e, rbspLen) {
    const out = new Uint8Array(rbspLen);
    for (let i = 0; i < rbspLen; i++) out[i] = nal[r2e[i]];
    return out;
  }

  // ===== F5 / F1 步长（闭式） =====
  const F5_BASE = [160, 192, 224, 256, 288, 320];
  function strideF5(k, o) {
    const le = (k[o] | (k[o + 1] << 8) | (k[o + 2] << 16) | (k[o + 3] << 24)) >>> 0;
    const idx = le % 6;
    return F5_BASE[idx] | k[o + idx];
  }
  /**
   * 取 NAL 开头的 RBSP 字节（去掉防竞争 03）。
   * 步长与密钥必须从 RBSP 读取：当 03 出现在前 21 字节内时，直接读 EBSP 会让密钥错位、整帧解错
   * （参考实现即如此，明文流对照中发现于小尺寸 IDR 切片）。
   */
  function rbspHead(nal, len, n) {
    const out = new Uint8Array(n);
    let w = 0, zeros = 0;
    for (let i = 0; i < len && w < n; i++) {
      const b = nal[i];
      if (zeros >= 2 && b === 3) { zeros = 0; continue; }
      out[w++] = b;
      zeros = b === 0 ? zeros + 1 : 0;
    }
    return w === n ? out : out.subarray(0, w);
  }

  function type5Stride(nal, len) {
    if (len < 11) return 0;
    return strideF5(nal, 5);
  }
  function type1Stride(nal, len) {
    if (len < 7) return 0;
    return strideF5(nal, 1);
  }

  /** NAL 内部不允许出现 00 00 00 / 00 00 01 / 00 00 02 */
  function hasIllegalStartCode(b) {
    for (let i = 2, n = b.length; i < n; i++) {
      if (b[i] <= 2 && b[i - 1] === 0 && b[i - 2] === 0) return true;
    }
    return false;
  }

  // ===== Type1 G 变换 =====
  function fbit(W) {
    const w0 = W & 1, w8 = (W >>> 8) & 1, w15 = (W >>> 15) & 1, w19 = (W >>> 19) & 1;
    const w25 = (W >>> 25) & 1, w30 = (W >>> 30) & 1, w31 = (W >>> 31) & 1;
    const t = w0 | w8;
    return (w31 ^ w15 ^ t
      ^ (w8 & w19)
      ^ (w25 & (w0 ^ w19))
      ^ (w0 & (1 ^ w8) & w30)
      ^ ((1 ^ w0) & w19 & w30)
      ^ (w25 & w30 & (w8 ^ w19))) & 1;
  }
  /**
   * Type1 G 变换每一步注入的位。
   *
   * 参考实现把 B 步常量 {2,8,9,10} 与按 NAL 头"家族"拟合的翻转表叠加使用，未知家族直接置 0。
   * 用明文 HLS 流逐 NAL 对照（数千个切片，全部唯一可解）后可以确定：两者叠加后恰好就是
   * 切片头第 2、3 字节本身 —— 第 s 步 (0..7) 注入 b2 的第 (7-s) 位，第 s 步 (8..15) 注入 b1 的第 (15-s) 位。
   * 参考实现中 61 家族把 s0/s1 接到了 b2 的第 1/0 位，在 b2 第 7/6 位与第 1/0 位不同（如 61 e0 81）
   * 时整帧解错；闭式写法修正了这一问题，并覆盖所有 NAL 头。
   */
  function headerBits(b1, b2) {
    const w = (b2 << 8) | b1;
    let m = 0;
    for (let s = 0; s < 16; s++) m |= ((w >> (15 - s)) & 1) << s;
    return m;
  }

  function gTransform(X, Y, bits) {
    let W = (X | (Y << 16)) >>> 0;
    let P1 = 0;
    for (let s = 0; s < 16; s++) {
      const b = fbit(W) ^ ((bits >> s) & 1);
      P1 |= b << (15 - s);
      W = ((W << 1) | b) >>> 0;
    }
    return P1 & 0xffff;
  }

  // ===== 三种 NAL 解密 =====
  const tmp8 = new Uint8Array(8);

  function decryptType5New(nal, len, stride) {
    if (len < 21 || stride < 8) return nal.subarray(0, len);
    const key = rbspHead(nal, len, 21).slice(5, 21);
    // 与明文流对照确定：单元格需在 RBSP 中剩余至少 17 字节（与 type1 一致）。
    // 参考实现按"去掉末尾 2 字节 + 16 字节余量"判断，会漏解末尾恰好剩 17 字节的单元格。
    const { r2e, rbspLen, epbs } = buildRbspMap(nal, len, true);
    let cells = 0;
    for (let k = 0; ; k++, cells++) {
      const o = 64 + k * stride;
      if (o + 17 > rbspLen) break;
      for (let b = 0; b < 8; b++) tmp8[b] = nal[r2e[o + b]];
      teaDecrypt(tmp8, key);
      for (let b = 0; b < 8; b++) nal[r2e[o + b]] = tmp8[b];
    }
    // 没有任何单元格被加密的 NAL，编码端不会对其重新转义，原样返回（明文对照：末尾极小的 IDR 帧）
    if (!cells || !epbs.length) return nal.subarray(0, len);
    return stripEpb(nal, r2e, rbspLen);
  }

  function decryptType1New(nal, len, stride, start, guard) {
    if (stride < 4 || len < 3) return nal.subarray(0, len);
    const bits = headerBits(nal[1], nal[2]);
    const { r2e, rbspLen, epbs } = buildRbspMap(nal, len, true);
    let cells = 0;
    for (let k = 0; ; k++, cells++) {
      const o = start + k * stride;
      if (o + guard > rbspLen || o + 4 > rbspLen) break;
      const i0 = r2e[o], i1 = r2e[o + 1], i2 = r2e[o + 2], i3 = r2e[o + 3];
      const X = nal[i0] | (nal[i1] << 8);
      const Y = nal[i2] | (nal[i3] << 8);
      const P1 = gTransform(X, Y, bits);
      nal[i0] = P1 & 0xff; nal[i1] = (P1 >> 8) & 0xff;
      nal[i2] = X & 0xff; nal[i3] = (X >> 8) & 0xff;
    }
    // 没有任何单元格被加密的 NAL，编码端不会对其重新转义，原样返回（明文对照：末尾极小的 IDR 帧）
    if (!cells || !epbs.length) return nal.subarray(0, len);
    return stripEpb(nal, r2e, rbspLen);
  }

  function decryptClassicGrid(nal, len) {
    if (len < 48) return nal.subarray(0, len);
    const { r2e, rbspLen, epbs } = buildRbspMap(nal, len, true);
    if (rbspLen < 48) return nal.subarray(0, len);
    const key = new Uint8Array(16);
    for (let b = 0; b < 16; b++) key[b] = nal[r2e[16 + b]];
    let cells = 0;
    for (let k = 0; ; k++, cells++) {
      const o = 32 + k * 80;
      if (o + 80 > rbspLen) break;
      for (let b = 0; b < 8; b++) tmp8[b] = nal[r2e[o + b]];
      teaDecrypt(tmp8, key);
      for (let b = 0; b < 8; b++) nal[r2e[o + b]] = tmp8[b];
    }
    // 没有任何单元格被加密的 NAL，编码端不会对其重新转义，原样返回（明文对照：末尾极小的 IDR 帧）
    if (!cells || !epbs.length) return nal.subarray(0, len);
    return stripEpb(nal, r2e, rbspLen);
  }

  /** 解密会话：type25 状态跨 NAL、跨切片延续 */
  class H5eSession {
    constructor() {
      this.newMode = false;
      this.shouldDecrypt = false;
      this.type1Start = 64;
      this.type1Guard = 17;
      this.type1MinLen = 129;
    }
    reset() { this.newMode = false; this.shouldDecrypt = false; }

    /**
     * 解密自检计数器。目的不是发现单帧问题，而是第一时间发现"央视改了加密规则"这类系统性变化：
     *   - markers / unknownMarkers：加密标记 NAL（类型 25）。目前所有视频都是固定的 32 字节，
     *     以 79 01 01 09 01（新模式）或 79 01 01 06 01（经典模式）开头；出现其它格式即视为规则变化。
     *   - slices / slicesWithoutMarker：加密流中出现了视频切片却从未见过标记，说明加密方式变了。
     *   - anomalies：步长/密钥推导落在预期之外（例如 IDR 步长 < 8）。
     *   - invalidOut：解密结果中出现非法起始码序列（00 00 00/01/02），正确的解密结果中永远为 0。
     */
    get check() {
      if (!this._check) this._check = { markers: 0, unknownMarkers: 0, slices: 0, slicesWithoutMarker: 0, decrypted: 0, anomalies: 0, invalidOut: 0 };
      return this._check;
    }

    /** 处理单个 NAL（不含起始码），返回解密后的 NAL（可能与输入共用内存，长度可能变化） */
    onNal(nal, len) {
      const same = nal.subarray(0, len);
      if (len < 1) return same;
      const ntype = nal[0] & 0x1f;
      if (ntype === 25) {
        const c = this.check;
        c.markers++;
        // 已知格式：32 字节，79 0x 01 09 01（新模式）或 79 0x 01 06 01（经典模式）
        if (!(len === 32 && nal[1] <= 1 && nal[2] === 1 && (nal[3] === 9 || nal[3] === 6) && nal[4] === 1)) c.unknownMarkers++;
        this.shouldDecrypt = len >= 2 && nal[1] === 1;
        if (len >= 4 && nal[2] === 0x01) {
          if (nal[3] === 0x09) this.newMode = true;
          else if (nal[3] === 0x06) this.newMode = false;
        }
        return same;
      }
      if (ntype !== 1 && ntype !== 5) return same;
      const c = this.check;
      c.slices++;
      if (!c.markers) c.slicesWithoutMarker++;
      if (!this.shouldDecrypt) return same;
      let out;
      if (!this.newMode) {
        // 经典模式同样不处理长度 < 129 的 P 帧切片（与新模式一致）。参考实现会解它们，导致其中原有的防竞争字节被误删
        if (ntype === 1 && len < this.type1MinLen) return same;
        out = decryptClassicGrid(nal, len);
      }
      else if (ntype === 5) {
        const head = rbspHead(nal, len, 16);
        const S = type5Stride(head, head.length);
        if (S < 8) { c.anomalies++; return same; }
        out = decryptType5New(nal, len, S);
      } else {
        if (len < this.type1MinLen) return same;
        const head = rbspHead(nal, len, 16);
        const S = type1Stride(head, head.length) || 511;
        out = decryptType1New(nal, len, S, this.type1Start, this.type1Guard);
      }
      c.decrypted++;
      if (hasIllegalStartCode(out)) c.invalidOut++;
      return out;
    }
  }

  global.H5eCrypto = { H5eSession, teaDecrypt, headerBits, gTransform, hasIllegalStartCode };
})(typeof self !== 'undefined' ? self : globalThis);
