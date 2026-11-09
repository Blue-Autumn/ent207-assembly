'use strict';
(() => {
 const $=id=>document.getElementById(id),tr=(en,cn)=>zh?cn:en;
 let zh=new URLSearchParams(location.search).get('lang')==='zh', run=null, results=null, note=null;
 const names={fast:['Standard JS','标准 JS'],simd:['Handwritten SIMD','手写 SIMD'],opencv:['OpenCV WASM','OpenCV WASM']};
 const preference='ent207.camera.simd.v1';
 const useSIMD=()=>{try{return localStorage.getItem(preference)==='on';}catch{return false;}};
 const time=(values,p)=>{const s=[...values].sort((a,b)=>a-b);return p===.5?(s[Math.floor((s.length-1)/2)]+s[Math.floor(s.length/2)])/2:s[Math.ceil(p*s.length)-1];};
 function show(){
  document.documentElement.lang=zh?'zh-CN':'en';document.title=$('title').textContent=tr('Recognition speed test','本机识别测速');
  $('back').setAttribute('aria-label',tr('Back','返回'));
  $('description').textContent=tr('Compare readers on this device using the same two images. Times include copying pixels, reading tags and returning a result. The camera stays off.',
    '用两张固定图片在本机比较读码器，计时包含取图、识别和返回结果。测试期间相机保持关闭。');
  $('photo-label').textContent=tr('Assembly photo','实物装配样片');$('chart-label').textContent=tr('25-tag test image','25 枚标签测试图');
  $('opt-label').textContent=tr('Include OpenCV · first download about 8 MB','同时测 OpenCV · 首次加载约 8 MB');
  $('precision').disabled=!!run;$('run').textContent=run?tr('Cancel test','取消测试'):tr('Run on this device','开始本机测速');
  $('status').textContent=note?tr(...note):'';$('rows').replaceChildren();$('comparison').textContent='';
  if(results){
   for(const [engine,r] of Object.entries(results)){
    const row=document.createElement('div');row.className='measurement';
    const label=document.createElement('strong');label.textContent=tr(...names[engine]);
    const desc=document.createElement('small');row.append(label,desc);
    if(!r.times.length)desc.textContent=tr('Unavailable in this browser','此浏览器未能运行');
    else{
     label.textContent+=' · '+time(r.times,.5).toFixed(1)+' ms';
     desc.textContent=tr('Median · P95 ','中位耗时 · P95 ')+time(r.times,.95).toFixed(1)+' ms'+
       tr(' · Photo ',' · 样片 ')+r.counts[0]+tr(' tags · Chart ',' 枚 · 测试图 ')+r.counts[1]+'/25';
    }
    $('rows').append(row);
   }
   const a=results.fast,b=results.simd;
   if(a?.times.length && b?.times.length){
    $('comparison').textContent=sameOutput()?
      tr('Both readers returned the same tag IDs and corners on these images. Actual camera response also depends on exposure and the time needed to confirm a stable view.',
        '两种读码器在这两张图上返回了相同的编号和角点。实拍跟随速度还取决于曝光与稳定确认时间。'):
      tr('The readers disagreed on these images. Use the standard reader for assembly.','这两张图的读取结果有差异，装配时请使用标准读码。');
   }
  }
  $('apply').hidden=!!run || (!useSIMD() && !sameOutput());
  $('apply').textContent=useSIMD()?tr('Restore standard reader','恢复标准读码'):tr('Try SIMD in guided assembly','在装配指导中试用 SIMD');
  $('guide').hidden=!!run||!results;$('guide').textContent=tr('Open guided assembly','进入装配指导');
 }
 function sameOutput(){
  const a=results?.fast,b=results?.simd;
  return !!(a?.times.length && b?.times.length && a.counts.every(n=>n>0) && a.keys.every((v,i)=>v===b.keys[i]));
 }
 function ask(worker,message,transfer,signal,ms=12000){
  return new Promise((resolve,reject)=>{
   const finish=(fn,value)=>{clearTimeout(timer);signal.removeEventListener('abort',cancel);worker.onmessage=null;worker.onerror=null;fn(value);};
   const cancel=()=>finish(reject,Error('cancelled'));
   const timer=setTimeout(()=>finish(reject,Error('timeout')),ms);
   if(signal.aborted){cancel();return;}
   signal.addEventListener('abort',cancel,{once:true});
   worker.onmessage=e=>e.data.error?finish(reject,Error(e.data.error)):finish(resolve,e.data);
   worker.onerror=()=>finish(reject,Error('reader error'));
   worker.postMessage(message,transfer);
  });
 }
 async function start(){
  if(run){run.abort();return;}
  const owner=new AbortController();run=owner;results=null;note=[ 'Preparing readers…','正在准备读码器…'];show();
  const engines=$('precision').checked?['fast','simd','opencv']:['fast','simd'];
  const readers=new Map(),out={};
  try{
   const images=[$('photo'),$('chart')];
   await Promise.all(images.map(im=>im.decode()));
   const surfaces=images.map(im=>{
    const c=document.createElement('canvas'),scale=Math.min(1,1024/Math.max(im.naturalWidth,im.naturalHeight));
    c.width=Math.max(1,Math.round(im.naturalWidth*scale));c.height=Math.max(1,Math.round(im.naturalHeight*scale));
    return c.getContext('2d',{willReadFrequently:true});
   });
   for(const e of engines){
    if(owner.signal.aborted)throw Error('cancelled');
    out[e]={times:[],counts:[],keys:[]};
    const worker=new Worker('../vision/worker.js?v=5'+(e==='simd'?'&engine=simd':''));
    readers.set(e,worker);
    if(e==='fast')continue;
    try{
     const ready=await ask(worker,{type:e==='simd'?'prepare-simd':'prepare-precision'},[],owner.signal,45000);
     if(ready.type!==(e==='simd'?'simd-ready':'precision-ready'))throw Error('unavailable');
    }catch(err){worker.terminate();readers.delete(e);if(owner.signal.aborted)throw err;}
   }
   for(let round=0;round<=8;round++){
    note=[round?'Comparing images · '+round+' / 8':'Warming up…',round?'正在比较 · '+round+' / 8':'正在预热…'];show();
    for(let s=0;s<images.length;s++)for(let k=0;k<engines.length;k++){
     const e=engines[(k+round+s)%engines.length],worker=readers.get(e);if(!worker)continue;
     if(owner.signal.aborted)throw Error('cancelled');
     const ctx=surfaces[s],w=ctx.canvas.width,h=ctx.canvas.height,begin=performance.now();
     ctx.drawImage(images[s],0,0,w,h);const image=ctx.getImageData(0,0,w,h);
     const reply=await ask(worker,{image,precision:e==='opencv'?'opencv':'fast',taskId:'prepare:左侧板',lang:zh?'zh':'en',
       mode:'stream',atMs:begin,sessionId:e,captureId:e},[image.data.buffer],owner.signal);
     const elapsed=performance.now()-begin;
     const expected={fast:'fast-4x4-250',simd:'simd-4x4-250',opencv:'opencv-4.13'}[e];
     if(reply.detector!==expected){worker.terminate();readers.delete(e);out[e].times=[];continue;}
     if(round)out[e].times.push(elapsed);
     out[e].counts[s]=new Set(reply.markers.map(m=>m.id)).size;
     out[e].keys[s]=JSON.stringify(reply.markers.map(m=>[m.id,...m.corners.flatMap(p=>[p.x,p.y])]).sort((a,b)=>a[0]-b[0]));
    }
   }
   results=out;note=['16 timed reads per available engine; warm-up and downloads excluded.','每种可用引擎计时 16 次，预热和下载单独处理。'];
  }catch{
   note=owner.signal.aborted?['Test cancelled.','测试已取消。']:['Test could not finish. Reopen the page to retry.','测试未完成，可重新打开页面再试。'];
  }finally{
   for(const w of readers.values())w.terminate();run=null;show();
  }
 }
 $('run').onclick=start;
 $('apply').onclick=()=>{
  try{
   const enabled=!useSIMD();
   if(enabled && !sameOutput())return;
   localStorage.setItem(preference,enabled?'on':'off');
   note=enabled?['SIMD is selected for the next guided session in this browser.','此浏览器下次进入装配指导时会试用 SIMD。']:['Standard reader restored.','已恢复标准读码。'];
  }catch{note=['This browser could not save the choice.','当前浏览器未能保存选择。'];}
  show();
 };
 $('guide').onclick=()=>{if(parent!==window)parent.postMessage({type:'ent207-tools-open',target:'guide'},location.origin);else location.assign('../#guide');};
 $('back').onclick=()=>{run?.abort();if(parent!==window)parent.postMessage({type:'ent207-close'},location.origin);else location.assign('../');};
 document.addEventListener('visibilitychange',()=>{if(document.hidden)run?.abort();});
 addEventListener('pagehide',()=>run?.abort());
 window.ent207SetLanguage=value=>{zh=value==='zh';show();};
 show();
})();
