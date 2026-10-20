// 离线回归测试入口：npm test
// 1) 语法检查扩展全部脚本  2) 固定样本解密逐字节比对 + 自检灵敏度  3) 网络层故障注入
const { execFileSync, spawnSync } = require('child_process');
const fs = require('fs'), path = require('path');
const { EXT } = require('./lib/load');
let failed = 0;
const step = (name, fn) => { process.stdout.write(`\n▶ ${name}\n`); try { fn(); } catch (e) { failed++; console.log(`FAIL ${e.message}`); } };

step('语法检查', () => {
  const files = [];
  (function walk(d) { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); if (fs.statSync(p).isDirectory()) walk(p); else if (p.endsWith('.js')) files.push(p); } })(EXT);
  for (const f of files) execFileSync(process.execPath, ['--check', f]);
  JSON.parse(fs.readFileSync(path.join(EXT, 'manifest.json'), 'utf8'));
  console.log(`PASS ${files.length} 个脚本 + manifest.json`);
});
for (const [name, file, env] of [['解密回归（固定样本）', 'decrypt-fixtures.test.js', {}], ['网络层故障注入', 'net-fault.test.js', { N: '80' }]]) {
  step(name, () => {
    const r = spawnSync(process.execPath, [path.join(__dirname, file)], { stdio: 'inherit', env: { ...process.env, ...env } });
    if (r.status !== 0) throw new Error(`${file} 未通过`);
  });
}
console.log(failed ? `\n✗ ${failed} 组测试未通过` : '\n✓ 全部通过');
process.exit(failed ? 1 : 0);
