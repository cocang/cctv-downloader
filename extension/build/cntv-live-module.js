#!/usr/bin/env node
/**
 * 生成 core/cntv-live-module.js：央视网直播播放器的解密模块（CNTVModule，wasm 内嵌）的「可移植」版本。
 *
 *   node build/cntv-live-module.js [live.worker.js 路径或 URL] [输出目录，默认 src/core]
 *
 * 输出两个文件：
 *   cntv-live-module.js   Emscripten 胶水代码（约 230 KB）
 *   cntv-live-module.bin  二进制数据：wasm 本体 + Emterpreter 字节码（央视用 Emterpreter 把解密逻辑编译成虚拟机字节码来混淆，
 *                         原文件把这约 720 KB 字节码写成十进制数字数组、wasm 写成 base64，所以原文件有 2.3 MB）
 *
 * 原文件来自 https://js.player.cntv.cn/creator/live.worker.js。改动只有环境适配：
 *   1. wasm 通过 eval / emval 读取 location 校验页面来源：替换为固定的央视网来源（扩展 / 应用里页面不在 cctv.com）
 *   2. 去掉 eval 与 new Function（扩展的 CSP 不允许），功能不变
 *   3. 删掉末尾与解密无关的音效代码（LiveAudio2，依赖 window / AudioContext）
 * 同一套逻辑也内置在安卓应用里，应用启动时会尝试下载最新版并用 patch() 现场转换。
 */
'use strict';

const FAKE_LOCATION = '{host:"tv.cctv.com",hostname:"tv.cctv.com",href:"https://tv.cctv.com/live/",origin:"https://tv.cctv.com",protocol:"https:",port:"",pathname:"/live/",search:"",hash:""}';

function patch(src) {
  let s = String(src);
  const rep = (from, to, required = true) => {
    if (!s.includes(from)) { if (required) throw new Error('补丁位置未找到：' + from.slice(0, 60)); return; }
    s = s.split(from).join(to);
  };
  // 1) eval(name)：wasm 只会询问 self.location.host / href
  rep('a=eval(name)', 'a=__cntvEnv(name)');
  // 2) emval 读取全局 location
  rep('__emval_register(emval_get_global()[A]))}', '__emval_register(A==="location"?' + FAKE_LOCATION + ':emval_get_global()[A]))}');
  // 3) embind 的 createNamedFunction 用了 new Function
  rep('new Function("body","return function "+A+\'() {\\n    "use strict";    return body.apply(this, arguments);\\n};\\n\')(e)',
      'function(){return e.apply(this,arguments)}');
  rep('Function("return this")()', 'self');
  // 4) wasm 运行约 30 秒后会请求 https://tv.cctv.com/Library/H5player.json 做校验，失败则停止解密。
  //    原实现用 XMLHttpRequest（Service Worker 里没有，且跨域会被拦）：换成基于 fetch 的最小实现，取不到时用内置副本。
  rep('d=new XMLHttpRequest;', 'd=new __CntvXHR;');
  // 5) 去掉 LiveAudio2（文件末尾）
  const i = s.indexOf(',LiveAudio2=(');
  if (i > 0) s = s.slice(0, i) + ';';
  const env = 'function __cntvEnv(n){var L=' + FAKE_LOCATION + ';n=String(n).replace(/^(self|window|globalThis)\\./,"");' +
    'if(n.indexOf("location.")===0){var k=n.slice(9);return k in L?L[k]:""}return ""}';
  // 6) 把内嵌数据拆到二进制文件：wasm（base64）→ Module.wasmBinary；Emterpreter 字节码（十进制数组）→ Module.__emtData
  const m64 = s.match(/var wasmBinaryFile="data:application\/octet-stream;base64,([A-Za-z0-9+\/=]+)"/);
  if (!m64) throw new Error('未找到内嵌 wasm');
  const wasm = Buffer.from(m64[1], 'base64');
  s = s.replace(m64[0], 'var wasmBinaryFile="cntv-live.wasm"');
  const chunks = [];
  let first = true;
  s = s.replace(/HEAPU8\.set\(\[([0-9,]+)\],eb\+(\d+)\)/g, (all, nums, off) => {
    chunks.push({ off: +off, bytes: Buffer.from(nums.split(',').map(Number)) });
    const r = first ? 'HEAPU8.set(Module.__emtData,eb+0)' : 'void 0';
    first = false;
    return r;
  });
  if (!chunks.length) throw new Error('未找到 Emterpreter 数据');
  const emtLen = Math.max(...chunks.map((c) => c.off + c.bytes.length));
  const emt = Buffer.alloc(emtLen);
  for (const c of chunks) c.bytes.copy(emt, c.off);
  const head = Buffer.alloc(12);
  head.write('CNTV', 0, 'latin1'); head.writeUInt32LE(wasm.length, 4); head.writeUInt32LE(emt.length, 8);
  const js = '/* 由 build/cntv-live-module.js 生成，请勿手改；数据在 cntv-live-module.bin */\n' + env + '\n' + XHR + '\n' + s +
    '\n;(typeof self!=="undefined"?self:globalThis).CNTVModule=CNTVModule;(typeof self!=="undefined"?self:globalThis).__CntvXHR=__CntvXHR;\n';
  return { js, bin: Buffer.concat([head, wasm, emt]) };
}

// 极简 XMLHttpRequest：只满足 emscripten fetch 的用法（GET + arraybuffer）
const XHR = `var __CNTV_H5PLAYER='{"h5player":{"ver":20190904,"md5":"c7ed5a71dbe4dee1a2ba171f660ee98d","BTime":"2019-09-04-20:25:10"}}';
function __CntvXHR(){this.readyState=0;this.status=0;this.statusText="";this.response=null;this.timeout=0;this.withCredentials=false;this.responseType="arraybuffer";}
__CntvXHR.prototype.open=function(m,u){this.method=m;this.url=u;this.readyState=1;};
__CntvXHR.prototype.setRequestHeader=function(){};
__CntvXHR.prototype.overrideMimeType=function(){};
__CntvXHR.prototype.abort=function(){};
__CntvXHR.prototype.send=function(){var x=this;var done=function(buf,status){x.response=buf;x.status=status;x.statusText=status===200?"OK":"";x.readyState=4;
  if(x.onreadystatechange)x.onreadystatechange({});if(x.onprogress)x.onprogress({loaded:buf?buf.byteLength:0,total:buf?buf.byteLength:0});if(x.onload)x.onload({});};
  // 校验只认 H5player.json 的版本号，这是固定值，直接用内置副本同步返回，不走网络（避免异步竞态与跨域）
  if(/H5player\\.json/i.test(x.url)){done(new TextEncoder().encode(__CNTV_H5PLAYER).buffer,200);__CntvXHR.checked=(__CntvXHR.checked||0)+1;return;}
  x.readyState=4;x.status=404;if(x.onerror)x.onerror({});};`;

module.exports = { patch };

if (require.main === module) {
  const arg = process.argv[2] || 'https://js.player.cntv.cn/creator/live.worker.js';
  const out = process.argv[3] || require('path').join(__dirname, '..', 'src', 'core');
  const done = (txt) => {
    const r = patch(txt);
    const fs = require('fs');
    fs.writeFileSync(require('path').join(out, 'cntv-live-module.js'), r.js);
    fs.writeFileSync(require('path').join(out, 'cntv-live-module.bin'), r.bin);
    console.log(`cntv-live-module.js ${r.js.length} 字节，cntv-live-module.bin ${r.bin.length} 字节`);
  };
  if (/^https?:/.test(arg)) fetch(arg, { headers: { Referer: 'https://tv.cctv.com/' } }).then((r) => r.text()).then(done);
  else done(require('fs').readFileSync(arg, 'utf8'));
}
