/**
 * 央视网直播 / 回看流（cdrm）解密
 *
 * 直播与时移回看的视频切片用 cdrm 方式加密（与点播的 h5e 不同），解密在央视网播放器自带的 wasm 模块里完成
 * （core/cntv-live-module.js，见 build/cntv-live-module.js）。这里按官方播放器完全相同的调用约定驱动它：
 *
 *   InitPlayer(ctx)                      ctx = "h5player_player"
 *   每个视频 NAL（类型 1 / 5 / 7）：
 *     token = UpdatePlayer(ctx)，转成 8 位十六进制
 *     对 token 第 i 位（i = 0..7）为 0–6 的位置，依次调用 jsdecLive(7 - i)   ← 模块内部状态依赖这些调用
 *     jsdecLive8(ctx, buf, nalLen, originLen)，buf = NAL 原始字节 + "https://tv.cctv.com"
 *
 * 会话对象实现 ts-pipeline 需要的 onNal(nal, len) 接口，可直接替换 h5e 会话。
 */
(function (global) {
  'use strict';

  const ORIGIN = 'https://tv.cctv.com';
  const TAG = 'h5player_player';
  const EXTEND = 2048;
  const BUF_EXTRA = 65536;

  /**
   * 模块数据（cntv-live-module.bin：wasm + Emterpreter 字节码）只下载 / 编译一次，各实例共用编译结果。
   * 数据文件与本脚本同在 core/ 目录；扩展后台页在根目录，应用的 Worker 在 js/ 目录，依次尝试。
   */
  let dataPromise = null;
  function loadData() {
    if (dataPromise) return dataPromise;
    dataPromise = (async () => {
      const bases = [global.CNTV_DATA_URL, 'core/cntv-live-module.bin', '../core/cntv-live-module.bin'].filter(Boolean);
      let buf = null, lastErr = null;
      for (const rel of bases) {
        try {
          const res = await fetch(new URL(rel, global.location.href).href);
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          buf = new Uint8Array(await res.arrayBuffer());
          if (String.fromCharCode(buf[0], buf[1], buf[2], buf[3]) === 'CNTV') break;
          buf = null;
        } catch (e) { lastErr = e; }
      }
      if (!buf) throw new Error('直播解密数据缺失' + (lastErr ? `：${lastErr.message}` : ''));
      const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
      const wl = dv.getUint32(4, true), el = dv.getUint32(8, true);
      const wasm = buf.subarray(12, 12 + wl);
      const emt = buf.slice(12 + wl, 12 + wl + el);
      const compiled = await WebAssembly.compile(wasm);
      return { compiled, emt };
    })();
    dataPromise.catch(() => { dataPromise = null; });
    return dataPromise;
  }

  /**
   * 每个会话一个独立的模块实例：wasm 内部的解密状态是全局的，
   * 两个任务（如同时录制直播和保存回看）共用一个实例会互相打乱状态，导致花屏。
   */
  async function loadModule() {
    const { compiled, emt } = await loadData();
    return new Promise((resolve, reject) => {
      if (typeof global.CNTVModule !== 'function') { reject(new Error('直播解密模块未加载')); return; }
      let settled = false;
      const timer = setTimeout(() => { if (!settled) { settled = true; reject(new Error('直播解密模块初始化超时')); } }, 20000);
      try {
        const M = global.CNTVModule({
          print() {}, printErr() {},
          __emtData: emt,
          instantiateWasm(imports, done) {
            WebAssembly.instantiate(compiled, imports).then((inst) => done(inst), (e) => { if (!settled) { settled = true; reject(e); } });
            return {};
          },
        });
        // 注意：Emscripten 的 Module 带 then 方法（thenable），直接 resolve(M) 会无限递归，必须包一层
        const ready = () => { if (settled) return; settled = true; clearTimeout(timer); resolve({ M }); };
        if (M.calledRun) ready(); else M.onRuntimeInitialized = ready;
      } catch (e) { settled = true; clearTimeout(timer); reject(e); }
    });
  }

  function writeStr(M, p, str, size) {
    M.HEAPU8.fill(0, p, p + size);
    for (let i = 0; i < str.length; i++) M.HEAPU8[p + i] = str.charCodeAt(i);
  }

  /**
   * 调用方式与官方播放器逐步一致：每次调用前 jsmalloc、调用后 jsfree（ctx 与数据缓冲区都是）。
   * 模块内部按分配记录状态，复用同一块内存会在几百个 NAL 之后逐渐解错（实测约 30 秒后开始花屏）。
   */
  class CdrmSession {
    constructor(M) {
      this.M = M;
      this.origin = new Uint8Array(ORIGIN.length);
      for (let i = 0; i < ORIGIN.length; i++) this.origin[i] = ORIGIN.charCodeAt(i);
      this.call('_CNTV_InitPlayer');
      this.check = null; // ts-pipeline 的 h5e 自检不适用
      this.stats = { nals: 0, changed: 0 };
    }

    /** InitPlayer / UpdatePlayer：ctx = jsmalloc(len + 2048)，写入标识，调用后释放 */
    call(fn) {
      const M = this.M;
      const p = M._jsmalloc(TAG.length + EXTEND);
      writeStr(M, p, TAG, TAG.length + EXTEND);
      let r = 0;
      try { r = M[fn](p); } finally { M._jsfree(p); }
      return r;
    }

    onNal(nal, len) {
      const t = nal[0] & 0x1f;
      if (t !== 1 && t !== 5 && t !== 7) return nal;
      const M = this.M;
      this.call('_CNTV_UpdatePlayer');
      const tok = this.call('_CNTV_UpdatePlayer');
      let hex = tok != null && tok !== 0 ? tok.toString(16) : '';
      while (hex && hex.length < 8) hex = '0' + hex;

      const olen = this.origin.length;
      // 数据区比官方播放器（+2048）留得更大：个别 NAL 解密时模块会写到 len + 2048 之外，导致越界崩溃（播放器里表现为丢帧）
      const buf = M._jsmalloc(len + BUF_EXTRA);
      // 模块会把来源地址等写回 ctx（约 64 字节），官方播放器只分配 16 字节、偶尔会踩坏堆；这里多分配，避免越界
      const ctx = M._jsmalloc(TAG.length + EXTEND);
      let out = nal;
      try {
        M.HEAPU8.fill(0, buf, buf + len + BUF_EXTRA);
        M.HEAPU8.set(nal.subarray(0, len), buf);
        M.HEAPU8.set(this.origin, buf + len);
        writeStr(M, ctx, TAG, TAG.length + EXTEND);
        for (let i = 0; i < 8 && i < hex.length; i++) {
          const c = hex.charCodeAt(i);
          if (c >= 48 && c <= 54) M['_CNTV_jsdecLive' + (7 - i)](ctx, buf, len, olen);
        }
        const r = M._CNTV_jsdecLive8(ctx, buf, len, olen);
        if (r > 0 && r <= len + 64) {
          out = M.HEAPU8.slice(buf, buf + r);
          if (t !== 7) this.stats.changed++;
        }
      } finally {
        M._jsfree(buf);
        M._jsfree(ctx);
      }
      this.stats.nals++;
      return out;
    }

    dispose() { this.M = null; }
  }

  async function freshSession() {
    const before = (global.__CntvXHR && global.__CntvXHR.checked) || 0;
    const s = new CdrmSession((await loadModule()).M);
    // 让出事件循环：模块在 InitPlayer 之后异步发起一次 H5player.json 校验，未完成就连续解密，约 760 个 NAL 后会停止解密
    for (let i = 0; i < 40; i++) {
      await new Promise((r) => setTimeout(r, 25));
      if (((global.__CntvXHR && global.__CntvXHR.checked) || 0) > before) break;
    }
    return s;
  }

  /**
   * 轮换会话：同一个模块实例连续解密数百个 NAL 后，wasm 内部偶发越界崩溃（官方播放器边播边解、节奏慢，不易触发）。
   * 每个实例用到 ROTATE 次之后，在下一个 SPS（GOP 开头）处换成后台准备好的新实例；
   * 解密状态从 SPS 开始建立，在 GOP 边界切换不影响结果（已与官方播放器逐字节比对）。
   * 新建一个实例约 50 毫秒。
   */
  const ROTATE = 600;
  class RotatingSession {
    constructor(first) {
      this.cur = first;
      this.next = null;
      this.nextPromise = null;
      this.check = null;
      this.stats = { nals: 0, changed: 0, rotations: 0, errors: 0 };
    }
    prepare() {
      if (this.nextPromise) return;
      this.nextPromise = freshSession().then((s) => { this.next = s; }, () => { this.nextPromise = null; });
    }
    swap() {
      if (!this.next) return false;
      this.cur.dispose();
      this.cur = this.next;
      this.next = null;
      this.nextPromise = null;
      this.stats.rotations++;
      return true;
    }
    onNal(nal, len) {
      const t = nal[0] & 0x1f;
      if (t !== 1 && t !== 5 && t !== 7) return nal;
      const used = this.cur.stats.nals;
      if (used >= ROTATE * 0.5) this.prepare();
      // 解密状态从 SPS 开始建立：只在 SPS（每个 GOP 开头）处切换实例
      if (used >= ROTATE && t === 7) this.swap();
      let out;
      try {
        out = this.cur.onNal(nal, len);
      } catch (e) {
        // 实例已损坏：本 GOP 余下部分原样输出，下一个 SPS 换新实例
        this.stats.errors++;
        this.cur.stats.nals = Infinity;
        this.prepare();
        out = nal;
      }
      this.stats.nals++;
      if (out !== nal && t !== 7) this.stats.changed++;
      return out;
    }
    dispose() { if (this.cur) this.cur.dispose(); if (this.next) this.next.dispose(); }
  }

  global.CdrmCrypto = {
    async createSession() {
      const s = new RotatingSession(await freshSession());
      s.prepare();
      await s.nextPromise;
      return s;
    },
  };
})(typeof self !== 'undefined' ? self : globalThis);
