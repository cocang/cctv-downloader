// 从 TS（视频 PID 0x100）提取 NAL 列表（不含起始码）。参数可以是文件路径或 Buffer
const fs=require('fs');
module.exports=function(src){
 const d=Buffer.isBuffer(src)?src:(src instanceof Uint8Array?Buffer.from(src):fs.readFileSync(src)); let pes=[]; const out=[];
 function flush(){ if(!pes.length)return; const b=Buffer.concat(pes); pes=[]; if(b[0]||b[1]||b[2]!==1)return;
  const es=b.slice(9+b[8]); const st=[];
  for(let i=0;i+2<es.length;i++){ if(es[i]===0&&es[i+1]===0&&es[i+2]===1){st.push(i+3);i+=2;} }
  for(let k=0;k<st.length;k++){ let end=k+1<st.length?st[k+1]-3:es.length; while(end>st[k]&&es[end-1]===0&&k+1<st.length) end--; out.push(es.slice(st[k],end)); } }
 for(let o=0;o+188<=d.length;o+=188){const pid=((d[o+1]&31)<<8)|d[o+2]; if(pid!==0x100)continue; const afc=(d[o+3]>>4)&3; if(afc===0||afc===2)continue; const pi=afc===1?4:5+d[o+4]; if(pi>=188)continue; if(d[o+1]&0x40)flush(); pes.push(d.slice(o+pi,o+188));}
 flush(); return out;
};
