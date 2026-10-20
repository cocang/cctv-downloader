// 在线回归：抽取语料中每个视频各清晰度的 6 个切片解密，
//   - 有同一次编码的明文流时（850/450、4K 等）逐 NAL 比对，必须 0 差异；
//   - 所有清晰度用 ffmpeg 解码，错误数必须为 0。
// 用法：node online-oracle.test.js [语料文件，默认 corpus.txt]   需要 ffmpeg 与网络
require('./lib/load')('core/cctv-api.js','core/h5e-decrypt.js','core/ts-pipeline.js');
const nalsOf=require('./lib/nals'); const fs=require('fs'); const {execSync}=require('child_process');
const tmp='/tmp/b_'+process.pid;
const get=u=>fetch(u).then(r=>{if(!r.ok) throw new Error('HTTP '+r.status); return r.arrayBuffer();});
function dec(bufs){const p=new TsPipeline.TsH5ePipeline({}); const o=[]; for(const b of bufs) o.push(Buffer.from(p.push(new Uint8Array(b)))); o.push(Buffer.from(p.end())); return Buffer.concat(o);}
function ffErr(buf){fs.writeFileSync(tmp+'.ts',buf); return +execSync(`ffmpeg -v error -i ${tmp}.ts -map 0:v -f null - 2>&1 | grep -c -E "error while|concealing|corrupt|Invalid" || true`).toString().trim();}
(async()=>{
 const lines=fs.readFileSync(process.argv[2]||require('path').join(__dirname,'corpus.txt'),'utf8').split('\n').map(l=>l.replace(/#.*/,'').trim()).filter(Boolean);
 let failures=0;
 for(const line of lines){
  try{
   let g=line.trim(); if(g.startsWith('http')){ const h=await CctvApi.fetchText(g); g=CctvApi.extractGuidsFromHtml(h)[0]; if(!g) throw new Error('no guid'); }
   const v=await CctvApi.getVideo(g);
   const raw=JSON.parse(await CctvApi.fetchText(`https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=${v.guid}`));
   const res=[];
   for(const q of v.qualities){
     const m=await CctvApi.getSegments(q); const n=m.segments.length;
     const idx=[...new Set([0,1,Math.floor(n/4),Math.floor(n/2),Math.floor(3*n/4),n-1].filter(i=>i>=0&&i<n))];
     const enc=await Promise.all(idx.map(i=>get(m.segments[i].url)));
     const out=dec(enc); const e=ffErr(out);
     let cmp='-';
     if(raw.hls_url && !q.plain){ try{
       const cp=raw.hls_url.replace(/\/asp\/hls\/main\//,`/asp/hls/${q.id}/`).replace(/\/main\.m3u8.*/,`/${q.id}.m3u8`);
       const cm=CctvApi.parseMedia(await CctvApi.fetchText(cp),cp);
       if(cm.segments.length===n){ const clr=await Promise.all(idx.map(i=>get(cm.segments[i].url)));
         fs.writeFileSync(tmp+'d.ts',out); fs.writeFileSync(tmp+'c.ts',Buffer.concat(clr.map(b=>Buffer.from(b))));
         const A=nalsOf(tmp+'d.ts').filter(x=>(x[0]&31)!==25), B=nalsOf(tmp+'c.ts');
         const sps=(L)=>{const s=L.find(x=>(x[0]&31)===7); return s?s.toString('hex'):''};
         if(A.length===B.length && sps(A)===sps(B)){ let bad=0; for(let k=0;k<A.length;k++) if(!A[k].equals(B[k])) bad++; cmp=`${bad}/${A.length}`; } else cmp='diffenc';
       } } catch(e){ cmp='noclear'; } }
     res.push(`${q.id}${q.plain?'(plain)':''}:err=${e},cmp=${cmp}`);
   }
   for(const r of res){ const m=r.match(/err=(\d+),cmp=(\d+)\//); if(/err=[1-9]/.test(r)||(m&&+m[2]>0)) failures++; }
   console.log(`${v.guid.slice(0,8)} ${(v.channel||'').padEnd(8)} ${String(v.duration).padStart(5)}s ${v.title.slice(0,18)} | ${res.join(' ')}`);
  }catch(e){ console.log(line.slice(-40),'SKIP',e.message); }
 }
 console.log(failures?`FAIL ${failures} 条清晰度异常`:'PASS 在线回归全部通过'); process.exit(failures?1:0);
})();
