/**
 * 构建：node build/build.js [srcDir] [outDir]
 *   dist/chrome  — Chrome / Edge（同一个包，MV3 Service Worker + 离屏文档）
 *   dist/firefox — Firefox 140+（MV3 事件页，进程内处理 blob）
 */
const fs = require('fs');
const path = require('path');
const src = path.resolve(process.argv[2] || 'src');
const out = path.resolve(process.argv[3] || 'dist');

function copy(from, to, skip = () => false) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const a = path.join(from, e.name), b = path.join(to, e.name);
    if (skip(path.relative(src, a))) continue;
    if (e.isDirectory()) copy(a, b, skip); else fs.copyFileSync(a, b);
  }
}

const base = JSON.parse(fs.readFileSync(path.join(src, 'manifest.json'), 'utf8'));
const bg = fs.readFileSync(path.join(src, 'background.js'), 'utf8');
const scripts = bg.match(/importScripts\(([\s\S]*?)\);/)[1].match(/'([^']+)'/g).map((s) => s.slice(1, -1));

fs.rmSync(out, { recursive: true, force: true });

// Chrome / Edge
const chromeDir = path.join(out, 'chrome');
copy(src, chromeDir, (r) => r.startsWith('platform') || r === 'README.md');

// Firefox
const ffDir = path.join(out, 'firefox');
copy(src, ffDir, (r) => r === 'offscreen/offscreen.html' || r === 'README.md');
const ff = JSON.parse(JSON.stringify(base));
delete ff.minimum_chrome_version;
ff.background = { scripts: ['platform/firefox-env.js', ...scripts, 'offscreen/verify.js', 'offscreen/offscreen.js', 'background.js'] };
ff.permissions = ff.permissions.filter((p) => !['offscreen', 'downloads.ui'].includes(p));
ff.browser_specific_settings = {
  gecko: {
    id: 'cctv-video-downloader@chouchang',
    strict_min_version: '140.0',
    data_collection_permissions: { required: ['none'] },
  },
};
fs.writeFileSync(path.join(ffDir, 'manifest.json'), JSON.stringify(ff, null, 2));
console.log('built', path.relative(process.cwd(), chromeDir), path.relative(process.cwd(), ffDir));
