import {classification,guardCloud,photoCheck,unavailable,validCorners} from './policy.js';
const $=id=>document.getElementById(id);
const args=new URLSearchParams(location.search);
const taskId=args.get('task')||'';
const validTask=/^(prepare|install):(左侧板|右侧板|背板|层[1-5]板)$/.test(taskId);
let lang=args.get('lang')?.startsWith('zh')?'zh':'en';
const tr=(en,zh)=>lang==='zh'?zh:en;
const viewLimit=3;
const taskSamples={
  'prepare:左侧板':[
    ['../guide/gray/same-end.webp','Joint · 025 + 031','接头 · 025 + 031'],
    ['../guide/gray/flipped.webp','Joint · 024 + 031','接头 · 024 + 031']
  ],
  'install:层2板':[['../field/blue-row.webp','Blue shelf at row 1','蓝色层板位于第 1 排']],
  'install:层1板':[['../guide/joint-photo.webp','Purple shelf joint','紫色层板接头']]
};
const samples=taskSamples[taskId]||[];
let samplesOpen=false,parked=null;
let views=[],selected=null,replaceId=null,mode='browse',generation=0,acquiring=false,disposed=false;
let stream=null,worker=null,job=null,workerTimer=0,cloud=null,cloudReady=false,cloudJob=null,online=null;
let feedback=null;
const selectedView=()=>views.find(v=>v.captureId===selected);
const setText=(id,value)=>{$(id).textContent=value;};
const show=(id,on)=>$(id).toggleAttribute('hidden',!on);
const inform=(en,zh)=>{feedback={en,zh};render();};
const idForCapture=()=>crypto.randomUUID?.() || Array.from(crypto.getRandomValues(new Uint32Array(3))).join('-');
function taskName(){
  const [action,name]=taskId.split(':');
  const names={'左侧板':['left frame','左侧框'],'右侧板':['right frame','右侧框'],'背板':['back panel','背板'],
    '层1板':['purple shelf','紫色层板'],'层2板':['blue shelf','蓝色层板'],'层3板':['peach shelf','肉色层板'],
    '层4板':['yellow shelf','黄色层板'],'层5板':['green shelf','绿色层板']};
  if(!validTask)return tr('Open a step to review it','先打开要复核的步骤');
  if(taskId==='install:左侧板')return tr('Lay down the base frame','平放基准侧框');
  const item=names[name];
  return tr((action==='prepare'?'Join the ':'Fit the ')+item[0],(action==='prepare'?'拼好':'安装')+item[1]);
}
function control(label,css,click,disabled=false) {
  const b=document.createElement('button');b.type='button';b.textContent=label;b.className=css;
  b.onclick=click;b.disabled=disabled;$('acquire').append(b);return b;
}
function stopCamera(){
  if(stream)for(const t of stream.getTracks())t.stop();
  stream=null;$('live').srcObject=null;
}
function cancelCloud(){
  cloudJob?.abort();cloudJob=null;online=null;
  setText('cloud-status','');show('online-result',false);
}
function stopWorker(){
  clearTimeout(workerTimer);worker?.terminate();worker=null;job=null;
}
function leave(){
  dispose();
  if(parent!==window)parent.postMessage({type:'ent207-review-close'},location.origin);
  else location.replace('../#guide');
}
function dispose(){
  if(disposed)return;
  disposed=true;generation++;stopCamera();stopWorker();cancelCloud();views=[];parked=null;
  $('still').removeAttribute('src');$('thumbs').replaceChildren();$('question').value='';
}
function renderControls(){
  const row=$('acquire');row.replaceChildren();
  if(!validTask)return;
  if(parked){
    if(samples.length>1)control(tr('Choose another sample','选择另一张样片'),'',toggleSamples,acquiring);
    control(tr('Back to my photos','返回我的照片'),'quiet',restorePhotos,acquiring);
    return;
  }
  if(mode==='live'||mode==='opening'){
    control(tr('Capture photo','拍摄照片'),'primary',capture,mode==='opening'||!$('live').videoWidth||acquiring);
    control(tr('Cancel','取消'),'',browse);
    return;
  }
  const choose=()=>{const input=$('pick');input.multiple=replaceId===null && (viewLimit-views.length)>1;input.value='';input.click();};
  if(views.length<viewLimit || mode==='replace'){
    control(mode==='replace'?tr('Retake with camera','用相机重拍'):tr('Open camera','打开相机'),'primary',openCamera,acquiring);
    control(mode==='replace'?tr('Choose replacement','选择替换照片'):tr('Choose photos','选择照片'),'',choose,acquiring);
  }
  if(mode==='replace')control(tr('Cancel retake','取消替换'),'quiet',browse,acquiring);
  else if(views.length){
    control(tr('Retake','替换此张'),'quiet',()=>{mode='replace';replaceId=selected;feedback=null;render();},acquiring);
    control(tr('Remove','移除此张'),'quiet',removeView,acquiring);
  }
  if(samples.length && mode==='browse')control(tr('Try a sample photo','试用一张样片'),'',toggleSamples,acquiring);
}
function toggleSamples(){
  if(acquiring)return;
  if(samples.length===1){loadSample(samples[0]);return;}
  samplesOpen=!samplesOpen;render();
}
function renderSamples(){
  const showChoices=samplesOpen && mode==='browse';
  show('samples',showChoices);if(!showChoices)return;
  setText('samples-title',tr('Choose a course photo','选择课程样片'));
  const list=$('sample-list');list.replaceChildren();
  for(const entry of samples){
    const b=document.createElement('button');b.type='button';b.disabled=acquiring;
    const img=new Image();img.src=entry[0];img.alt='';
    b.append(img,document.createTextNode(tr(entry[1],entry[2])));
    b.onclick=()=>loadSample(entry);list.append(b);
  }
}
async function loadSample(entry){
  if(acquiring||disposed)return;
  const ticket=++generation;acquiring=true;feedback=null;render();
  try{
    const img=await loadImage(entry[0]);
    if(ticket!==generation||disposed)return;
    const sample=encode(img,img.naturalWidth,img.naturalHeight);
    sample.sample=entry;
    if(!parked)parked={views,selected,lang,question:$('question').value};
    stopWorker();cancelCloud();views=[sample];selected=sample.captureId;
    $('question').value='';samplesOpen=false;replaceId=null;mode='browse';
  }catch{
    feedback={en:'The sample could not open. Try again.',zh:'样片暂时无法打开，请重试。'};
  }finally{
    if(ticket===generation&&!disposed){acquiring=false;render();scheduleCheck();}
  }
}
function restorePhotos(){
  if(!parked)return;
  stopWorker();cancelCloud();views=parked.views;selected=parked.selected;
  if(parked.lang!==lang)for(const v of views)resetResults(v);
  $('question').value=parked.question;parked=null;samplesOpen=false;feedback=null;
  render();scheduleCheck();
}
function drawOverlay(v) {
  const svg=$('marks');svg.replaceChildren();
  const checked=v?.result;
  const visible=!!(v?.overlay && checked?.markers.length && mode!=='live'&&mode!=='opening');
  show('marks',visible);if(!visible)return;
  svg.setAttribute('viewBox','0 0 '+checked.width+' '+checked.height);
  const ns=svg.namespaceURI;
  for(const m of checked.markers.filter(validCorners)) {
    const p=document.createElementNS(ns,'polygon');
    p.setAttribute('points',m.corners.map(v=>v.x+','+v.y).join(' '));svg.append(p);
    const label=document.createElementNS(ns,'text');
    const center=m.corners.reduce((v,p)=>({x:v.x+p.x/4,y:v.y+p.y/4}),{x:0,y:0});
    label.setAttribute('x',center.x);label.setAttribute('y',center.y);label.setAttribute('text-anchor','middle');label.setAttribute('dominant-baseline','middle');
    label.style.fontSize=Math.max(14,checked.width/36)+'px';label.textContent=('00'+m.id).slice(-3);svg.append(label);
  }
}
function renderFinding(v) {
  const shown=!!v && mode!=='live'&&mode!=='opening';show('result',shown);if(!shown)return;
  const check=v.result?.check;
  const index=views.indexOf(v)+1;
  setText('local-label',v.sample?tr('On-device check · course sample','本机检查 · 课程样片'):
    tr('On-device check · photo ','本机检查 · 照片 ')+index);
  const category=classification(check);$('result').dataset.state=category;
  setText('finding',check?.headline||tr('Checking this photo…','正在检查这张照片…'));
  setText('advice',check?.instruction||'');show('retry',check?.status==='unavailable');
  const row=(n)=>Number.isInteger(n)&&n>=1&&n<=5;
  const measured=row(check?.observedRow)&&row(check?.targetRow);
  show('row-readout',measured);
  if(measured)setText('row-readout',tr('Observed row '+check.observedRow+' · target row '+check.targetRow,
    '当前第 '+check.observedRow+' 排 · 目标第 '+check.targetRow+' 排'));
  let detail='';
  if(category==='direction' && !/^prepare:(左侧板|右侧板)$/.test(taskId))detail=tr('Check the seam by eye before confirming this step.','确认步骤前，请目视检查接缝。');
  if(category==='direction' && /^install:层/.test(taskId) && check.status!=='install_row_ok')
    detail=tr('Check that both ends sit in the intended row before confirming.','确认前，请再核对层板两端是否插进目标排。');
  if(category==='unknown' && check && v===views.at(-1)){
    const past=views.slice(0,-1).filter(v=>classification(v.result?.check)!=='unknown').at(-1);
    if(past){
      const n=views.indexOf(past)+1;
      detail=tr('Photo '+n+' shows the earlier position. Include both reference tags again to check the current joint.',
        '照片 '+n+' 反映先前的摆位。请将两侧参照标签再拍进同一张照片，核对当前接头。');
    }
  }
  setText('limits',detail);show('limits',!!detail);
  const precise=v.result?.detector==='opencv-4.13';
  const waiting=job?.precision && job.id===v.captureId;
  show('precise-options',!!check && check.status!=='unavailable');
  $('precise').disabled=!!job || acquiring || !!cloudJob || views.some(item=>!item.result);
  setText('precise',waiting?tr('Running high-precision check…','高精度复查中…'):precise?tr('Show fast result','查看快速结果'):
    v.precise?tr('Show high-precision result','查看高精度结果'):tr('High-precision recheck','高精度复查'));
  let note=v.precisionError?tr('High-precision check unavailable. The fast result is still shown.','高精度复查暂不可用，已保留快速结果。'):
    tr('Runs on this device. First use loads about 8 MB.','在本机运行，首次使用约需加载 8 MB。');
  if(waiting)note=tr('Your fast result stays visible while OpenCV loads and reads this photo.','正在载入 OpenCV 并识别，快速检查结果仍可查看。');
  if(v.precise && v.fast) {
    const a=v.fast.markers.length,b=v.precise.markers.length;
    note=tr((precise?'Showing OpenCV 4.13. ':'Showing fast check. ')+'Tags found: fast '+a+', OpenCV '+b+'.',
      (precise?'当前显示 OpenCV 4.13 结果。':'当前显示快速结果。')+'快速识别 '+a+' 枚，OpenCV '+b+' 枚。');
    if(v.precise.check.status!==v.fast.check.status){
      const other=precise?v.fast:v.precise;
      note+=' '+tr('Other result: ','另一项结果：')+other.check.headline;
    }
  }
  setText('precision-note',note);
}
function renderOnline() {
  const has=cloudReady && views.length>0 && mode==='browse' && validTask;
  show('ask',has);if(!has)return;
  setText('online-title',tr('DeepSeek photo review','DeepSeek 照片复核'));
  const n=views.length;
  setText('sending-note',parked?tr('Send this course sample and the current step to DeepSeek for visual advice.','将这张课程样片和本步信息发给 DeepSeek，获取视觉建议。'):
    tr('Send all '+n+' selected photo'+(n===1?'':'s')+' and this step to DeepSeek for visual advice.','将选中的 '+n+' 张照片与本步信息发给 DeepSeek，获取视觉建议。'));
  setText('question-label',tr('Question (optional)','补充问题（可选）'));
  setText('send',parked?tr('Send sample to DeepSeek','发送样片给 DeepSeek'):tr('Send '+n+' photo'+(n===1?'':'s')+' to DeepSeek','发送 '+n+' 张照片给 DeepSeek'));
  $('send').disabled=!!cloudJob||!!job?.precision||acquiring||views.some(v=>!v.result);
  $('question').disabled=!!cloudJob;setText('cancel-cloud',tr('Cancel','取消'));show('cancel-cloud',!!cloudJob);
  if(cloudJob)setText('cloud-status',tr('Checking the selected photos…','正在复核选中的照片…'));
  show('online-result',!!online);
  if(online) {
    setText('cloud-source',online.source==='local'?tr('Current photo · local evidence','当前照片 · 本机证据'):tr('DeepSeek · visual advice','DeepSeek · 视觉建议'));
    setText('cloud-title',online.title||'');
    setText('cloud-copy',[online.instruction,online.viewRequest].filter((x,i,a)=>x&&a.indexOf(x)===i).join(' '));
  }
}
function render() {
  if(disposed)return;
  const v=selectedView(),camera=mode==='live'||mode==='opening';
  document.documentElement.lang=lang==='zh'?'zh-CN':'en';
  document.title=tr('Photo review','照片复核')+' · ENT207';
  setText('heading',tr('Photo review','照片复核'));setText('count',views.length+' / '+viewLimit);
  setText('task-name',taskName());
  const intro=!validTask?tr('Return to guided assembly and open photo review from the current task.','请返回装配指导，从当前步骤进入照片复核。'):
    replaceId?tr('Replace the selected photo. Your original stays until the new photo is ready.','替换选中的照片，新照片就绪后才移除原照。'):
    views.length?tr('Add a closer view if the seam is hard to see. Keep the parts in position.','接缝看不清时，可以保持零件位置，再补一张近照。'):
    /^install:层/.test(taskId)?tr('From slightly above, include the shelf notch and exposed tags on the gray frame and shelf.','从侧上方拍摄，将层板缺口、灰框和层板上可见的标签一起拍入。'):
    /^prepare:(左侧板|右侧板)$/.test(taskId)?tr('Show the joint with one tag on each side. One close-up can be enough to check the connector ends.','拍下当前接头，接缝两侧各带一枚标签。一张近照就可以核对连接端。'):
    tr('Include the part and its joint. Each photo is checked on this device.','把零件及其接头拍入画面，每张照片会在此设备上核对。');
  setText('intro',v?.sample?tr(v.sample[1],v.sample[2]):intro);
  $('viewer').classList.toggle('empty',!v&&!camera);
  show('live',camera);show('still',!!v&&!camera);show('camera-symbol',!v&&!camera);
  if(v && $('still').getAttribute('src')!==v.jpegDataUrl)$('still').src=v.jpegDataUrl;
  if(!v)$('still').removeAttribute('src');
  $('still').alt=tr('Selected assembly photo','当前选中的装配照片');
  show('source-badge',!!v?.sample && !camera);setText('source-badge',tr('Course sample','课程样片'));
  show('overlay',!!v?.result?.markers.length&&!camera);
  setText('overlay',v?.overlay?tr('Hide tags','隐藏标签'):tr('Show tags','显示标签'));
  $('overlay').setAttribute('aria-pressed',String(!!v?.overlay));
  drawOverlay(v);
  const thumbs=$('thumbs');thumbs.replaceChildren();show('thumbs',views.length>0&&!camera);thumbs.setAttribute('aria-label',tr('Selected photos','已选照片'));
  views.forEach((view,i)=>{
    const b=document.createElement('button');b.type='button';b.setAttribute('aria-pressed',String(view===v));b.setAttribute('aria-label',view.sample?tr('Sample photo','课程样片'):tr('Photo ','照片 ')+(i+1));b.disabled=acquiring;
    const img=new Image();img.src=view.jpegDataUrl;img.alt='';const num=document.createElement('span');num.textContent=view.sample?tr('Sample','样片'):i+1;b.append(img,num);
    b.onclick=()=>{selected=view.captureId;mode='browse';replaceId=null;feedback=null;render();};thumbs.append(b);
  });
  renderControls();renderSamples();renderFinding(v);renderOnline();
  setText('feedback',feedback?tr(feedback.en,feedback.zh):'');
  setText('retry',tr('Retry local check','重试本机检查'));
  setText('done',tr('Return to assembly','返回装配'));$('back').setAttribute('aria-label',tr('Back','返回'));
}
function browse(){
  generation++;stopCamera();mode='browse';replaceId=null;feedback=null;acquiring=false;render();
}
async function openCamera() {
  if(acquiring||disposed)return;
  const ticket=++generation;
  mode='opening';feedback={en:'Allow camera access to take a still photo.',zh:'允许使用相机后即可拍照。'};render();
  try {
    if(!navigator.mediaDevices?.getUserMedia)throw Error('unavailable');
    const fresh=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:'environment'},width:{ideal:1600},height:{ideal:1200}}});
    if(ticket!==generation||disposed){for(const t of fresh.getTracks())t.stop();return;}
    stream=fresh;$('live').srcObject=fresh;
    for(const t of fresh.getVideoTracks())t.onended=()=>{
      if(ticket!==generation||disposed)return;
      browse();inform('The camera stopped. Reopen it or choose a photo.','相机已停止，可重新打开或从相册选取。');
    };
    await $('live').play();
    if(ticket!==generation||disposed)return;
    mode='live';feedback=null;render();
  }catch(e){
    if(ticket!==generation||disposed)return;
    stopCamera();mode=replaceId?'replace':'browse';
    if(e.name==='NotAllowedError')inform('Camera access is off. Choose an existing photo or allow camera access in the browser.','未获得相机权限。可从相册选图，或在浏览器中允许使用相机。');
    else inform('The camera could not start. Choose a photo from this device.','相机暂时无法打开，可从设备中选择照片。');
  }
}
function encode(src,width,height) {
  if(!width||!height)throw Error('dimensions');
  const scale=Math.min(1,1600/Math.max(width,height));
  const cv=document.createElement('canvas');cv.width=Math.max(1,Math.round(width*scale));cv.height=Math.max(1,Math.round(height*scale));
  const ctx=cv.getContext('2d',{alpha:false,willReadFrequently:true});ctx.fillStyle='#faf9f3';ctx.fillRect(0,0,cv.width,cv.height);ctx.drawImage(src,0,0,cv.width,cv.height);
  for(const quality of [.88,.78,.65,.5]) {
    const url=cv.toDataURL('image/jpeg',quality);
    if(url.startsWith('data:image/jpeg;base64,') && url.length<1600000)return {captureId:idForCapture(),jpegDataUrl:url,width:cv.width,height:cv.height,sourceCanvas:cv,overlay:false,result:null};
  }
  throw Error('jpeg-size');
}
function acceptView(view,replaces) {
  if(replaces && job?.id===replaces)stopWorker();
  if(replaces)views=views.filter(v=>v.captureId!==replaces);
  if(views.length>=viewLimit)return false;
  cancelCloud();views.push(view);selected=view.captureId;return true;
}
function capture() {
  const live=$('live');
  if(mode!=='live'||acquiring||!live.videoWidth)return;
  try{const view=encode(live,live.videoWidth,live.videoHeight);acceptView(view,replaceId);browse();scheduleCheck();}
  catch{inform('Capture failed. Try the shutter again.','拍照失败，请再按一次快门。');}
}
async function fileView(file){
  if(file.size>45*1024*1024 || file.type==='image/svg+xml')throw Error('format');
  const temp=URL.createObjectURL(file);
  try{
    const img=await loadImage(temp);
    return encode(img,img.naturalWidth,img.naturalHeight);
  }finally{URL.revokeObjectURL(temp);}
}
function loadImage(src){return new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=()=>reject(Error('decode'));img.src=src;});}
async function importFiles(e) {
  const files=Array.from(e.target.files||[]);e.target.value='';
  if(!files.length||acquiring||disposed)return;
  const replacing=replaceId,limit=replacing?1:viewLimit-views.length,ticket=++generation;
  let failed=0;
  acquiring=true;feedback=null;render();
  try {
    for(const file of files.slice(0,limit)) {
      try {
        const v=await fileView(file);
        if(ticket!==generation||disposed)return;
        acceptView(v,replacing);replaceId=null;mode='browse';
      } catch {failed++;}
    }
  }finally{
    if(ticket===generation&&!disposed){
      acquiring=false;
      if(failed)feedback={en:'A photo could not be opened. Try a smaller JPEG or PNG.',zh:'有照片无法打开，请换用较小的 JPEG 或 PNG。'};
      else if(files.length>limit)feedback={en:'Up to three photos fit in one review.',zh:'一次复核最多可选三张照片。'};
      render();scheduleCheck();
    }
  }
}
function removeView(){
  if(acquiring)return;
  const index=views.findIndex(v=>v.captureId===selected);
  if(index<0)return;
  if(job?.id===selected)stopWorker();
  cancelCloud();views.splice(index,1);
  selected=(views[index]||views.at(-1))?.captureId||null;
  browse();scheduleCheck();
}
function failJob(ticket) {
  if(job!==ticket||disposed)return;
  const v=views.find(v=>v.captureId===ticket.id);stopWorker();
  if(v && ticket.precision)v.precisionError=true;
  else if(v)v.result={width:v.width,height:v.height,markers:[],check:unavailable(lang)};
  render();scheduleCheck();
}
function resetResults(v) {
  v.fast=null;v.precise=null;v.result=null;v.precisionError=false;
}
function scheduleCheck(requested) {
  if(job||disposed)return;
  const v=requested || views.find(v=>!v.result);
  if(!v)return;
  const ticket={id:v.captureId,lang,precision:!!requested};job=ticket;
  if(requested){v.precisionError=false;render();}
  try {
    if(!worker)worker=new Worker('../vision/worker.js');
    worker.onerror=()=>failJob(ticket);
    worker.onmessage=({data})=>{
      if(job!==ticket||disposed||data.captureId!==ticket.id)return;
      if(data.error || (ticket.precision && (data.detector!=='opencv-4.13'||data.fallback))){failJob(ticket);return;}
      clearTimeout(workerTimer);job=null;
      if(views.includes(v)){
        const markers=Array.isArray(data.markers)?data.markers:[];
        v.result={width:data.width || v.width,height:data.height||v.height,markers,detector:data.detector || 'fast-4x4-250',
          check:photoCheck(taskId,markers,data.check,lang)};
        if(ticket.precision){v.precise=v.result;cancelCloud();}
        else v.fast=v.result;
      }
      render();scheduleCheck();
    };
    // Preserve tag pixels before the lossy export used for thumbnails and optional AI.
    // The canvas and JPEG share the same orientation and coordinate system.
    const image=v.sourceCanvas.getContext('2d').getImageData(0,0,v.width,v.height);
    workerTimer=setTimeout(()=>failJob(ticket),ticket.precision?65000:25000);
    worker.postMessage({image,taskId,lang,mode:'photo',captureId:v.captureId,sessionId:v.captureId,
      precision:ticket.precision?'opencv':'fast'},[image.data.buffer]);
  }catch{failJob(ticket);}
}
async function sendPhotos(){
  if(!cloudReady||cloudJob||job?.precision||disposed||!views.length||views.some(v=>!v.result))return;
  const request=new AbortController();cloudJob=request;online=null;render();
  try{
    const shots=views.map(v=>({captureId:v.captureId,jpegDataUrl:v.jpegDataUrl,check:v.result.check,markers:v.result.markers,width:v.width,height:v.height}));
    const result=await cloud.reviewPhotos({taskId,lang,views:shots,question:$('question').value.trim(),signal:request.signal});
    if(cloudJob!==request||disposed)return;
    online=guardCloud(result,taskId,views.at(-1).result.check,lang);setText('cloud-status','');
  }catch{
    if(cloudJob!==request||disposed)return;
    setText('cloud-status',tr('Online review could not finish. The photos and local checks are still available.','在线复核暂未完成。照片与本机检查结果仍可查看。'));
  }finally{if(cloudJob===request&&!disposed){cloudJob=null;render();}}
}
async function setLanguage(next) {
  const choice=String(next).startsWith('zh')?'zh':'en';
  if(lang===choice||disposed)return;
  lang=choice;cancelCloud();stopWorker();for(const v of views)resetResults(v);
  render();scheduleCheck();
}
window.ent207SetLanguage=setLanguage;
$('done').onclick=$('back').onclick=leave;$('pick').onchange=importFiles;
$('retry').onclick=()=>{const v=selectedView();if(v){v.result=null;render();scheduleCheck();}};
$('precise').onclick=()=>{
  const v=selectedView();
  if(!v?.result||job||cloudJob||acquiring)return;
  if(v.precise){cancelCloud();v.result=v.result===v.precise?v.fast:v.precise;render();}
  else scheduleCheck(v);
};
$('overlay').onclick=()=>{const v=selectedView();if(v){v.overlay=!v.overlay;render();}};
$('send').onclick=sendPhotos;$('cancel-cloud').onclick=()=>{cancelCloud();render();};
$('question').oninput=()=>{if(online){online=null;renderOnline();}};
$('live').onloadedmetadata=()=>{if(!disposed)renderControls();};
addEventListener('message',e=>{if(e.origin===location.origin && e.source===parent && e.data?.type==='ent207-review-dispose')dispose();});
document.addEventListener('visibilitychange',()=>{if(document.hidden && (mode==='live'||mode==='opening'))browse();});
addEventListener('pagehide',dispose);
render();
import('../services/cloud.js').then(async api=>{
  const status=await api.cloudReady();if(disposed)return;
  cloud=api;cloudReady=status===true || !!(status?.configured && status.enabled!==false);render();
}).catch(()=>{});

