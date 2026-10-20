// 在 Node 中加载扩展核心模块（与 Service Worker 共用同一份源码）
const path = require('path');
const EXT = process.env.EXT_DIR || path.resolve(__dirname, '../../src');
module.exports = (...mods) => { for (const m of mods) require(path.join(EXT, m)); return globalThis; };
module.exports.EXT = EXT;
