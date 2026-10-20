// 离线回归：固定样本切片解密后，视频 NAL 序列必须与央视同源明文流逐字节一致（以 SHA-256 比对）
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const load = require('./lib/load'); load('core/h5e-decrypt.js', 'core/ts-pipeline.js');
const nalsOf = require('./lib/nals');
const dir = path.join(__dirname, 'fixtures');
const cases = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
let fail = 0;
for (const c of cases) {
  const p = new TsPipeline.TsH5ePipeline({ decrypt: true });
  const out = Buffer.concat([Buffer.from(p.push(new Uint8Array(fs.readFileSync(path.join(dir, c.file))))), Buffer.from(p.end())]);
  const nals = nalsOf(out).filter((n) => (n[0] & 0x1f) !== 25);
  const h = crypto.createHash('sha256');
  for (const n of nals) { h.update(Buffer.from([0, 0, 1])); h.update(n); }
  const sc = p.selfCheck();
  const ok = h.digest('hex') === c.clearSha256 && nals.length === c.nals && sc.ok;
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${c.file.padEnd(22)} ${String(nals.length).padStart(4)}/${c.nals} NAL  自检=${sc.level}  ${c.note}`);
}
// 自检灵敏度：篡改加密标记后必须报 format
{
  const c = cases[0];
  const buf = new Uint8Array(fs.readFileSync(path.join(dir, c.file)));
  let tampered = 0;
  for (let i = 0; i + 8 < buf.length; i++) {
    if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1 && buf[i + 3] === 0x79 && buf[i + 4] === 1) { buf[i + 7] = 0x0a; tampered++; }
  }
  const p = new TsPipeline.TsH5ePipeline({ decrypt: true }); p.push(buf); p.end();
  const ok = tampered > 0 && p.selfCheck().level === 'format';
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'} 自检：篡改 ${tampered} 个加密标记后判定为 ${p.selfCheck().level}`);
}
process.exit(fail ? 1 : 0);
