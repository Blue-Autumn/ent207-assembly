'use strict';
(() => {
 const $=id=>document.getElementById(id), video=$('preview'), api=window.ShelfCamera;
 const params=new URLSearchParams(location.search), insideGuide=params.get('from')==='guide';
 let zh=params.get('lang')?.startsWith('zh')||false;
 const tr=(en,cn)=>zh?cn:en;
 let epoch=0,busy=false,live=null,decoder=null,views=[],selected=0,report=null,notice=null,finished=false,promptPending=0;
 let selectedByVisitor=false;
 const stop=s=>s?.getTracks().forEach(t=>t.stop());
 const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
 const round=v=>Number.isFinite(v)?Math.round(v*1000)/1000:null;
 function release(){stop(live);live=null;video.srcObject=null;video.hidden=true;}
 function stopCheck(){epoch++;busy=false;release();decoder?.terminate();decoder=null;}
 function current(n){return n===epoch && !document.hidden;}
 function check(n){if(!current(n))throw Error('cancelled');}
 function status(en,cn){notice=[en,cn];$('status').textContent=tr(en,cn);}
 function label(view){
  if(view.kind==='base')return tr('Starting view','起始取景');
  if(view.kind==='zoom')return tr('Minimum zoom','最低变焦')+' · '+view.zoom;
  return view.namedWide?tr('Rear ultrawide','后置超广角'):tr('Rear input ','后置输入 ')+view.ordinal;
 }
 function draw(){
  document.documentElement.lang=zh?'zh-CN':'en';document.title=$('heading').textContent=tr('Rear camera check','后置广角检查');
  $('back').setAttribute('aria-label',tr('Back','返回'));
  const widest=views.find(v=>v.namedWide),zoomed=views.find(v=>v.kind==='zoom');
  $('title').textContent=!finished?tr('Compare the tabletop view','比一比桌面能拍多宽'):
    widest?tr('A rear ultrawide opened','已打开标注为超广角的后摄'):
    zoomed?tr('A lower zoom setting worked','已验证更低的变焦倍率'):
    views.length>1?tr('Compare these rear views','可比较以下后置取景'):
    views.length?tr('One rear view confirmed','本次只确认了一个后置取景'):tr('Camera check incomplete','本次未能完成镜头检查');
  $('intro').textContent=tr('Rest the phone in one place and keep the same table edges in view. The browser may ask to control zoom. The full-frame samples stay on this screen.',
    '把手机靠稳，检测期间保持同一位置，用相同的桌边对比视野。浏览器可能会询问变焦权限。全幅对照图只在本页显示。');
  $('intro').hidden=finished;
  $('status').textContent=notice?tr(...notice):'';
  $('start').textContent=busy?tr('Cancel check','取消检测'):finished?tr('Check again','重新检测'):tr('Check rear cameras and zoom','检测后摄和变焦');
  $('start').style.background=finished?'#e7ecdf':'';$('start').style.color=finished?'#3c503c':'';
  $('samples').hidden=!finished||!views.length;$('samples').replaceChildren();
  if(finished)for(const [i,v] of views.entries()){
   const button=document.createElement('button');button.type='button';button.setAttribute('aria-pressed',String(i===selected));
   button.append(v.thumb,document.createTextNode(label(v)));
   if(v.coverage)button.append(document.createElement('br'),document.createTextNode(tr('Table span ≈ ','同机位视野约 ')+v.coverage.toFixed(2)+'×'));
   button.onclick=()=>{selectedByVisitor=true;selected=i;draw();};$('samples').append(button);
  }
  $('facts').textContent='';
  if(report&&finished){
   const base=views[0], z=base?.range;
   const bits=[tr('Video inputs ','视频输入 ')+report.inputs,tr('Verified rear inputs ','确认后摄 ')+report.verifiedRear];
   if(report.unverified)bits.push(tr('Unresolved ','未能确认 ')+report.unverified);
   if(report.notTested)bits.push(tr('Not tested ','未试 ')+report.notTested);
   if(z && z.min!==null)bits.push(tr('Initial zoom range ','起始倍率范围 ')+z.min+'–'+z.max);
   if(report.zoomAccess==='denied')bits.push(tr('Zoom permission denied','变焦权限未获准'));
   if(report.zoomAccess==='timeout')bits.push(tr('Zoom permission timed out','变焦请求未完成'));
   if(report.comparison==='pending')bits.push(tr('Measuring table span…','正在估算桌面视野…'));
   $('facts').textContent=bits.join(' · ');
  }
  $('use').hidden=!finished||views[selected]?.side!=='rear';
  $('use').textContent=tr('Use this view for assembly','装配时使用此取景');
  $('leave').hidden=!finished;$('leave').textContent=insideGuide?tr('Return to this step','返回当前步骤'):tr('Return without selecting','返回');
  $('export').hidden=!report||!finished;$('export').textContent=tr('Save camera report','保存镜头检测记录');
 }
 function leave(use){
  if(use && views[selected]){
   const v=views[selected];
   if(!api.remember(v.id,v.zoom)){status('Browser storage is unavailable.','此浏览器无法保存镜头选择。');return;}
  }
  stopCheck();
  if(parent===window){location.assign('../#guide');return;}
  parent.postMessage({type:insideGuide?'ent207-lens-close':use?'ent207-tools-open':'ent207-close',
    target:'guide',useCamera:!!use},location.origin);
 }
 function acquire(constraints,n,ms){
  check(n);let expired=false,timer;promptPending=n;
  const opened=navigator.mediaDevices.getUserMedia({audio:false,video:constraints}).then(s=>{
   if(expired||!current(n)){stop(s);throw Error('cancelled');}return s;
  });
  return Promise.race([opened,new Promise((_,reject)=>{timer=setTimeout(()=>{expired=true;reject(Error('timeout'));},ms);})])
    .finally(()=>{clearTimeout(timer);if(promptPending===n)promptPending=0;});
 }
 async function inventory(){
  try{return (await navigator.mediaDevices.enumerateDevices()).filter(d=>d.kind==='videoinput');}catch{return [];}
 }
 function trackInfo(track,devices,requested){
  const settings=track?.getSettings?.()||{},caps=track?api.capabilities(track):{};
  const sameLabel=track?.label?devices.filter(d=>d.label===track.label):[];
  const id=[settings.deviceId,caps.deviceId,sameLabel.length===1?sameLabel[0].deviceId:null].find(v=>typeof v==='string'&&v)||'';
  const entry=devices.find(d=>d.deviceId===(id||requested));
  const declared=entry?api.facing(entry,api.capabilities(entry)):'unknown',actual=track?api.facing(track,caps):'unknown';
  const side=actual==='front'||declared==='front'?'front':actual==='rear'||declared==='rear'?'rear':'unknown';
  return {id:typeof id==='string'?id:'',actualId:settings.deviceId||'',side,namedWide:api.wideName(track?.label)||api.wideName(entry?.label),
    zoom:round(settings.zoom),range:{min:round(caps.zoom?.min),max:round(caps.zoom?.max)}};
 }
 async function startPreview(stream,n){
  check(n);live=stream;video.hidden=false;video.srcObject=stream;
  await video.play();check(n);
  for(let i=0;i<35 && (video.readyState<2 || !video.videoWidth);i++){await wait(60);check(n);}
  if(video.readyState<2||!video.videoWidth)throw Error('no-frame');
  await wait(220);check(n);
 }
 function shot(info,kind,ordinal,n){
  check(n);
  const thumb=document.createElement('canvas'),w=video.videoWidth,h=video.videoHeight;
  const size=Math.min(1,980/Math.max(w,h));thumb.width=Math.max(1,Math.round(w*size));thumb.height=Math.max(1,Math.round(h*size));
  const ctx=thumb.getContext('2d',{willReadFrequently:true});ctx.drawImage(video,0,0,thumb.width,thumb.height);
  return {...info,kind,ordinal,thumb,centers:null,width:w,height:h,readbackZoom:info.zoom,
    zoom:kind==='zoom'||(info.range.min!==null&&info.zoom!==null&&Math.abs(info.zoom-1)>.015)?info.zoom:null};
 }
 // Fixed phone and fixed board only. Pair distances are normalized to image width;
 // reject rotation, inconsistent ratios, sparse or tightly clustered tags.
 function relativeSpan(a,b){
  if((a.width>a.height)!==(b.width>b.height))return null;
  const ids=[...a.centers.keys()].filter(id=>b.centers.has(id)),ratios=[];
  if(ids.length<3)return null;
  for(let i=0;i<ids.length;i++)for(let j=0;j<i;j++){
   const p=a.centers.get(ids[i]),q=a.centers.get(ids[j]),r=b.centers.get(ids[i]),s=b.centers.get(ids[j]);
   const dx=q[0]-p[0],dy=q[1]-p[1],vx=s[0]-r[0],vy=s[1]-r[1],d=Math.hypot(dx,dy),v=Math.hypot(vx,vy);
   if(d<.13||v<.07)continue;
   if((dx*vx+dy*vy)/(d*v)<.982)return null;
   ratios.push(d/v);
  }
  if(ratios.length<3)return null;
  ratios.sort((a,b)=>a-b);const m=ratios[Math.floor(ratios.length/2)];
  return ratios.every(v=>Math.abs(v/m-1)<.09)?round(m):null;
 }
 // Run after every camera has stopped. Image comparison and the user's choice
 // stay available even if the optional marker reader takes time to download.
 async function measureViews(n,samples){
  let worker;
  try{
   worker=new Worker('../field/reader.js?v=6');decoder=worker;
   for(const [i,v] of samples.entries()){
    check(n);
    const {thumb}=v, image=thumb.getContext('2d').getImageData(0,0,thumb.width,thumb.height);
    const marks=await new Promise((resolve,reject)=>{
     const timer=setTimeout(()=>reject(Error('reader-timeout')),i===0?9000:1800);
     worker.onmessage=e=>{clearTimeout(timer);resolve(e.data.markers||[]);};
     worker.onerror=()=>{clearTimeout(timer);reject(Error('reader'));};
     worker.postMessage({image},[image.data.buffer]);
    });
    check(n);v.centers=new Map();
    for(const m of marks)v.centers.set(m.id,m.corners.reduce((c,p)=>[c[0]+p.x/(4*thumb.width),c[1]+p.y/(4*thumb.width)],[0,0]));
    if(i)v.coverage=relativeSpan(samples[0],v);
   }
   const measured=samples.map((v,i)=>({i,span:v.coverage||0})).filter(v=>v.span>1.10).sort((a,b)=>b.span-a.span)[0];
   if(measured&&!selectedByVisitor)selected=measured.i;
   report.comparison=samples.some(v=>v.coverage)?'measured':'insufficient-matching-tags';
  }catch{if(current(n))report.comparison='unavailable';}
  finally{
   worker?.terminate();if(decoder===worker)decoder=null;
   if(current(n)&&finished)draw();
  }
 }
 async function runCheck(){
  if(busy){stopCheck();views=[];report=null;finished=false;status('Check cancelled.','已取消检测。');draw();return;}
  const initial=live;live=null;stopCheck();busy=true;finished=false;selected=0;selectedByVisitor=false;views=[];
  const n=epoch;
  report={format:'ent207-rear-check-1',inputs:0,verifiedRear:0,unverified:0,notTested:0,zoomAccess:'not-requested',attempts:[]};
  status('Checking the starting camera…','正在检查起始后摄…');draw();
  const options={...api.rearOptions(),facingMode:{exact:'environment'}};
  let devices=[],baselineId='';
  const doneIds=new Set();
  function noteAttempt(ref,result){report.attempts.push({input:ref,result});}
  try{
   if(!navigator.mediaDevices?.getUserMedia)throw Error('no-api');
   const s=initial||await acquire(options,n,25000);live=s;check(n);
   devices=await inventory();check(n);report.inputs=devices.length;
   const t=s.getVideoTracks()[0],info=trackInfo(t,devices,'');
   if(!t||info.side==='front')throw Error('front');
   baselineId=info.id;if(baselineId)doneIds.add(baselineId);
   await startPreview(s,n);
   views.push(shot(info,'base',1,n));report.verifiedRear=info.side==='rear'?1:0;
   if(info.side==='unknown')report.unverified++;
   async function tryMinimum(track,baseInfo,ordinal){
    const min=api.capabilities(track).zoom?.min,old=track.getSettings?.().zoom;
    if(baseInfo.side!=='rear'||!Number.isFinite(min)||min<0||(Number.isFinite(old)&&min>=old-.02)||(!Number.isFinite(old)&&min>=1))return false;
    if(!await api.setZoom(track,min))return false;
    await wait(260);check(n);
    views.push(shot({...baseInfo,zoom:track.getSettings().zoom},'zoom',ordinal,n));return true;
   }
   const applied=await tryMinimum(t,info,1);check(n);
   if(applied)report.zoomAccess='verified';
   else if(!Number.isFinite(info.range.min)||info.zoom===null){
    status('Requesting the browser’s zoom control…','正在请求浏览器的变焦控制…');
    release();
    try{
     const enhanced=await acquire({...options,...(baselineId?{deviceId:{exact:baselineId}}:{}),zoom:true},n,22000);
     live=enhanced;const et=enhanced.getVideoTracks()[0],more=await inventory();check(n);
     if(more.length)devices=more;report.inputs=Math.max(report.inputs,devices.length);
     const ei=trackInfo(et,devices,baselineId);
     if(!et||ei.side==='front'||(baselineId&&ei.actualId&&ei.actualId!==baselineId))throw Error('different-camera');
     await startPreview(enhanced,n);
     report.zoomAccess=await tryMinimum(et,ei,1)?'verified':Number.isFinite(ei.range.min)?'range-read':'no-range-returned';
     report.requestedZoomRange=ei.range;
    }catch(err){check(n);report.zoomAccess=err.name==='NotAllowedError'?'denied':err.message==='timeout'?'timeout':'unavailable';}
   }else report.zoomAccess='range-read';
   release();
   const candidates=devices.filter(d=>d.deviceId && !doneIds.has(d.deviceId) && api.facing(d,api.capabilities(d))!=='front');
   candidates.sort((a,b)=>Number(api.wideName(b.label))-Number(api.wideName(a.label)));
   report.notTested=Math.max(0,candidates.length-8)+devices.filter(d=>!d.deviceId).length;
   for(const [i,d] of candidates.slice(0,8).entries()){
    check(n);if(doneIds.has(d.deviceId))continue;doneIds.add(d.deviceId);
    status('Checking another camera '+(i+1)+' / '+Math.min(8,candidates.length)+'…','正在核对其他镜头 '+(i+1)+' / '+Math.min(8,candidates.length)+'…');
    try{
     const alt=await acquire({...options,deviceId:{exact:d.deviceId}},n,4500);live=alt;
     const at=alt.getVideoTracks()[0],ai=trackInfo(at,devices,d.deviceId);
     if(!at||ai.side!=='rear'||(ai.actualId&&ai.actualId!==d.deviceId)){report.unverified++;noteAttempt(i+1,'unverified-facing-or-id');continue;}
     await startPreview(alt,n);
     const v=shot({...ai,id:ai.id||d.deviceId},'rear',i+2,n);
     views.push(v);report.verifiedRear++;noteAttempt(i+1,ai.namedWide?'rear-ultrawide':'rear');
     if(await tryMinimum(at,{...ai,id:ai.id||d.deviceId},i+2))report.zoomAccess='verified';
    }catch(err){check(n);noteAttempt(i+1,err.name==='OverconstrainedError'?'rear-constraint-rejected':'not-opened');if(err.name!=='OverconstrainedError')report.unverified++;}
    finally{if(current(n))release();}
   }
   check(n);
   const named=views.findIndex(v=>v.namedWide),lower=views.findIndex(v=>v.kind==='zoom');
   selected=named>=0?named:lower>=0?lower:0;
   status(views.length>1?'Compare the table edges. Select the view that works at your seated distance.':
    'Try holding the phone sideways and selecting Full frame in the guide. For a still photo, System camera may offer a wider lens.',
    views.length>1?'比较两张图的桌边，选择坐着也能拍全板件的取景。':'可试着横拿手机，在装配页点「看全幅」，让两块板沿长边并排。拍照时也可以从 AI 页打开「系统相机」查看可选镜头。');
  }catch(err){if(n===epoch)status(err.name==='NotAllowedError'?'Allow the camera in browser settings and try again.':'Could not finish the rear check. Return to this page to retry.',
    err.name==='NotAllowedError'?'请在浏览器设置中允许相机后重试。':'这次未能完成后摄验证，回到此页后可以重试。');}
  finally{
   if(n===epoch){
    release();busy=false;finished=true;
    report.comparison=views.length>1?'pending':'single-view';draw();
    if(views.length>1)void measureViews(n,views);
   }
  }
 }
 $('start').onclick=runCheck;$('back').onclick=() => leave(false);$('leave').onclick=()=>leave(false);$('use').onclick=()=>leave(true);
 $('export').onclick=()=>{
  if(!report)return;
  const result={...report,views:views.map(v=>({kind:v.kind,rear:v.side,namedUltrawide:v.namedWide,pixels:[v.width,v.height],range:v.range,
    verifiedZoom:v.zoom,zoomReadback:v.readbackZoom,relativeTableSpan:v.coverage||null,decodedTagCount:v.centers?.size??null})),selected};
  const url=URL.createObjectURL(new Blob([JSON.stringify(result,null,2)],{type:'application/json'})),a=document.createElement('a');
  a.href=url;a.download='ENT207-rear-camera-check.json';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
 };
 addEventListener('pagehide',stopCheck);document.addEventListener('visibilitychange',()=>{
  if(document.hidden && !(busy && promptPending===epoch && !live)){
   stopCheck();views=[];report=null;finished=false;status('Camera closed. Tap to check again.','相机已关闭，可重新检测。');draw();
  }
 });
 addEventListener('message',e=>{if(e.origin===location.origin && e.source===parent && e.data?.type==='ent207-lens-dispose')stopCheck();});
 window.ent207SetLanguage=value=>{zh=String(value).startsWith('zh');draw();};
 if(!insideGuide && window.frameElement){
  const handed=parent.ent207TakeGuideCamera?.(window);
  if(handed){live=handed;video.hidden=false;video.srcObject=handed;video.play().catch(()=>{});}
 }
 draw();
})();
