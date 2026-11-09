import {classification,guardCloud,photoCheck,unavailable,validCorners} from './policy.js';
const $=id=>document.getElementById(id);
const photoFeedback=ShelfFeedback.attach(document.body),changePhoto=ShelfFeedback.navigation($('viewer'));
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
let feedback=null,moreOpen=false,roomy=false,questionOpen=false,cloudChecked=false,cloudError=false,rawAdvice=null;
const selectedView=()=>views.find(v=>v.captureId===selected);
const setText=(id,value)=>{const el=$(id);if(el.textContent!==value)el.textContent=value;};
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
const shapes={
  camera:'M3 7h4l2-3h6l2 3h4v13H3z M16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  shutter:'M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0 M17.5 12a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0',
  gallery:'M3 4h18v16H3z M3 16l5-5 4 4 3-3 6 6 M16 8h.01',
  system:'M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  sample:'M7 5h14v13H7z M4 8v13h14 M8 15l4-4 3 3 2-2 3 3',
  more:'M5 12h.1 M12 12h.1 M19 12h.1',close:'M6 6l12 12 M18 6 6 18',
  expand:'M8 4H4v4 M16 4h4v4 M4 16v4h4 M20 16v4h-4',
  tags:'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M19 14v4h-4v2h5'
};
function symbol(name){
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),path=document.createElementNS(ns,'path');
  svg.setAttribute('aria-hidden','true');svg.setAttribute('viewBox','0 0 24 24');path.setAttribute('d',shapes[name]);
  if(name==='more')path.setAttribute('stroke-width','4');
  svg.append(path);return svg;
}
function nameButton(b,label){b.setAttribute('aria-label',label);b.title=label;}
function control(label,icon,click,caption='',primary=false,disabled=false){
  const b=document.createElement('button');b.type='button';b.className='tool'+(caption?' with-label':'')+(primary?' primary':'');b.dataset.icon=icon;
  nameButton(b,label);b.append(symbol(icon));if(caption){const text=document.createElement('span');text.textContent=caption;b.append(text);}
  b.onclick=click;b.disabled=disabled;$('acquire').append(b);return b;
}
function stopCamera(){
  if(stream)for(const t of stream.getTracks())t.stop();
  stream=null;$('live').srcObject=null;
}
function cancelCloud(){
  cloudJob?.abort();cloudJob=null;online=null;rawAdvice=null;cloudError=false;
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
  const row=$('acquire'),menu=$('photo-menu');row.replaceChildren();menu.replaceChildren();menu.hidden=true;
  if(!validTask)return;
  function menuAction(label,action){const b=document.createElement('button');b.type='button';b.textContent=label;b.disabled=acquiring;b.onclick=()=>{moreOpen=false;action();};menu.append(b);}
  if(parked){
    if(samples.length>1)control(tr('Choose another sample','选择另一张样片'),'sample',toggleSamples,tr('Samples','换张样片'),false,acquiring);
    control(tr('Back to my photos','返回我的照片'),'gallery',restorePhotos,tr('My photos','我的照片'),false,acquiring);
    return;
  }
  const live=mode==='live'||mode==='opening',replace=!!replaceId;
  function choose(id){
    if(live){generation++;stopCamera();mode=replace?'replace':'browse';feedback=null;render();}
    const input=$(id);if(id==='pick')input.multiple=!replace && viewLimit-views.length>1;
    input.value='';input.click();
  }
  if(views.length<viewLimit||replace){
    if(live)control(tr('Capture photo','拍摄照片'),'shutter',capture,tr('Capture','拍照'),true,mode==='opening'||!$('live').videoWidth||acquiring);
    else control(replace?tr('Retake with camera','用相机重拍'):tr('Open camera','打开相机'),'camera',()=>openCamera(),views.length&&!replace?'':tr('Camera','相机'),!views.length||replace,acquiring);
    control(tr('Open system camera','打开系统相机'),'system',()=>choose('system-camera'),tr('System camera','系统相机'),false,acquiring);
    control(replace?tr('Choose replacement','选择替换照片'):tr('Choose photos','选择照片'),'gallery',()=>choose('pick'),'',false,acquiring);
  }
  if(replace){control(tr('Cancel retake','取消替换'),'close',browse);return;}
  if(!views.length&&samples.length)control(tr('Try a sample photo','试用一张样片'),'sample',()=>{if(live)browse();toggleSamples();},'',false,acquiring);
  if(views.length&&!live){
    const more=control(tr('Photo options','照片选项'),'more',()=>{moreOpen=!moreOpen;renderControls();});
    more.setAttribute('aria-expanded',String(moreOpen));more.setAttribute('aria-controls','photo-menu');
    if(moreOpen){
      menu.hidden=false;
      menuAction(tr('Retake','替换此张'),()=>{mode='replace';replaceId=selected;feedback=null;render();});
      menuAction(tr('Remove','移除此张'),removeView);
      if(samples.length)menuAction(tr('Try a sample photo','试用一张样片'),toggleSamples);
    }
  }
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
  ShelfFeedback.mark($('finding'),!check?'neutral':category==='adjust'?'adjust':category==='direction'?'ok':'check');
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
  $('result').dataset.reader=v.result?.detector||'pending';
  $('result').dataset.precision=v.precise?'checked':v.precisionError?'fallback':'pending';
}
function renderOnline(){
  const has=views.length>0 && mode==='browse' && validTask;
  show('ask',has);
  const local=has?classification(selectedView()?.result?.check):'unknown';
  const cloudTone=online?({ok:'ok',issue:'adjust',need_view:'check'}[online.assessment]||'neutral'):'neutral';
  const tone=feedback?'adjust':online&&has?cloudTone:
    has&&cloudError?'adjust':local==='adjust'?'adjust':local==='direction'?'ok':'neutral';
  photoFeedback.show(has?(online?'answer/'+views.at(-1).captureId:'photo/'+selected):'capture',tone);
  if(!has)return;
  const n=views.length;
  const submit=parked?tr('Send sample to DeepSeek','发送样片给 DeepSeek'):tr('Send '+n+' photo'+(n===1?'':'s')+' to DeepSeek','发送 '+n+' 张照片给 DeepSeek');
  setText('send',cloudJob?tr('Reviewing…','正在复核…'):submit);
  $('send').disabled=!cloudReady||!!cloudJob||acquiring||views.some(v=>!v.result);
  nameButton($('ask-question'),tr('Add a question','补充问题'));$('ask-question').setAttribute('aria-expanded',String(questionOpen));
  show('question-box',questionOpen);setText('question-label',tr('Your question (optional)','补充问题（选填）'));
  $('question').disabled=!!cloudJob;setText('cancel-cloud',tr('Cancel','取消'));show('cancel-cloud',!!cloudJob);
  setText('cloud-status',cloudJob?tr('DeepSeek is reading your photos.','DeepSeek 正在查看照片。'):
    !cloudReady?(cloudChecked?tr('Online review is unavailable. You can still check the tags below.','暂时连不上 DeepSeek，可查看下方本机结果。'):tr('Connecting to DeepSeek…','正在连接 DeepSeek…')):cloudError?tr('Could not finish. Tap Send to retry.','本次复核未完成，点发送可重试。'):'');
  show('online-result',!!online);
  if(online){
    setText('cloud-source',online.source==='local'?tr('Current photo · local evidence','当前照片 · 本机证据'):tr('DeepSeek · visual advice','DeepSeek · 视觉建议'));
    setText('cloud-title',online.title||'');setText('cloud-copy',[online.instruction,online.viewRequest].filter((x,i,a)=>x&&a.indexOf(x)===i).join(' '));
    ShelfFeedback.mark($('cloud-title'),cloudTone);
  }
  ShelfFeedback.mark($('cloud-status'),cloudError?'adjust':'neutral');
}
function render() {
  if(disposed)return;
  const v=selectedView(),camera=mode==='live'||mode==='opening';
  changePhoto(camera?'live':v?.captureId||'empty');
  document.documentElement.lang=lang==='zh'?'zh-CN':'en';
  document.title=tr('AI photo review','AI 照片复核')+' · ENT207';
  setText('heading',tr('AI photo review','AI 照片复核'));setText('count',views.length+' / '+viewLimit);
  setText('task-name',taskName());
  const intro=!validTask?tr('Return to guided assembly and open photo review from the current task.','请返回装配指导，从当前步骤进入照片复核。'):
    replaceId?tr('Replace the selected photo. Your original stays until the new photo is ready.','替换选中的照片，新照片就绪后才移除原照。'):
    views.length?tr('Add a closer view if the seam is hard to see. Keep the parts in position.','接缝看不清时，可以保持零件位置，再补一张近照。'):
    /^install:层/.test(taskId)?tr('From slightly above, include the shelf notch and exposed tags on the gray frame and shelf.','从侧上方拍摄，将层板缺口、灰框和层板上可见的标签一起拍入。'):
    /^prepare:(左侧板|右侧板)$/.test(taskId)?tr('Show the joint with one tag on each side. One close-up can be enough to check the connector ends.','拍下当前接头，接缝两侧各带一枚标签。一张近照就可以核对连接端。'):
    tr('Include the part and its joint. Each photo is checked on this device.','把零件及其接头拍入画面，每张照片会在此设备上核对。');
  setText('intro',intro);show('intro',!v||!!replaceId);
  document.body.classList.toggle('has-frame',!!v&&!camera);document.body.classList.toggle('has-multiple',views.length>1&&!camera);document.body.classList.toggle('roomy',roomy);
  $('marks').setAttribute('preserveAspectRatio',roomy?'xMidYMid meet':'xMidYMid slice');
  show('stop-live',camera);nameButton($('stop-live'),tr('Close camera','关闭相机'));
  show('expand',!!v||camera);nameButton($('expand'),roomy?tr('Fill view','铺满取景'):tr('Show full frame','看全幅'));$('expand').setAttribute('aria-pressed',String(roomy));
  $('viewer').classList.toggle('empty',!v&&!camera);
  show('live',camera);show('still',!!v&&!camera);show('camera-symbol',!v&&!camera);
  if(v && $('still').getAttribute('src')!==v.jpegDataUrl)$('still').src=v.jpegDataUrl;
  if(!v)$('still').removeAttribute('src');
  $('still').alt=tr('Selected assembly photo','当前选中的装配照片');
  show('source-badge',!!v?.sample && !camera);setText('source-badge',tr('Course sample','课程样片'));
  show('overlay',!!v?.result?.markers.length&&!camera);
  nameButton($('overlay'),v?.overlay?tr('Hide tags','隐藏标签'):tr('Show tags','显示标签'));
  $('overlay').setAttribute('aria-pressed',String(!!v?.overlay));
  drawOverlay(v);
  const thumbs=$('thumbs');thumbs.replaceChildren();show('thumbs',views.length>1&&!camera);thumbs.setAttribute('aria-label',tr('Selected photos','已选照片'));
  views.forEach((view,i)=>{
    const b=document.createElement('button');b.type='button';b.setAttribute('aria-pressed',String(view===v));b.setAttribute('aria-label',view.sample?tr('Sample photo','课程样片'):tr('Photo ','照片 ')+(i+1));b.disabled=acquiring;
    const img=new Image();img.src=view.jpegDataUrl;img.alt='';const num=document.createElement('span');num.textContent=view.sample?tr('Sample','样片'):i+1;b.append(img,num);
    b.onclick=()=>{selected=view.captureId;mode='browse';replaceId=null;feedback=null;render();};thumbs.append(b);
  });
  renderControls();renderSamples();renderFinding(v);renderOnline();
  setText('feedback',feedback?tr(feedback.en,feedback.zh):'');
  ShelfFeedback.mark($('feedback'),feedback?'adjust':'neutral');
  setText('retry',tr('Retry local check','重试本机检查'));
  nameButton($('back'),tr('Return to assembly','返回装配'));
}
function browse(){
  generation++;stopCamera();mode='browse';replaceId=null;moreOpen=false;feedback=null;acquiring=false;render();
}
async function openCamera(handed=null) {
  if(disposed||acquiring||mode==='live'||mode==='opening'){handed?.getTracks().forEach(t=>t.stop());return;}
  moreOpen=false;samplesOpen=false;
  const ticket=++generation;
  mode='opening';feedback=null;render();
  try {
    if(!navigator.mediaDevices?.getUserMedia)throw Error('unavailable');
    const fresh=handed||await ShelfCamera.openPreferred(()=>ticket===generation&&!disposed);
    if(ticket!==generation||disposed||document.hidden){for(const t of fresh.getTracks())t.stop();if(ticket===generation&&!disposed)browse();return;}
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
  if(job?.precision || (replaces && job?.id===replaces))stopWorker();
  if(replaces)views=views.filter(v=>v.captureId!==replaces);
  if(views.length>=viewLimit)return false;
  cancelCloud();moreOpen=false;samplesOpen=false;roomy=false;views.push(view);selected=view.captureId;return true;
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
function scheduleCheck() {
  if(job||disposed)return;
  const pending=views.find(v=>!v.result);
  const enrich=!pending&&views.find(v=>v.fast&&!v.precise&&!v.precisionError);
  const v=pending||enrich;
  if(!v)return;
  const ticket={id:v.captureId,lang,precision:!!enrich};job=ticket;
  try {
    if(!worker)worker=new Worker('../vision/worker.js?v=8');
    worker.onerror=()=>failJob(ticket);
    worker.onmessage=({data})=>{
      if(job!==ticket||disposed||data.captureId!==ticket.id)return;
      if(data.error || (ticket.precision && (data.detector!=='opencv-4.13'||data.fallback))){failJob(ticket);return;}
      clearTimeout(workerTimer);job=null;
      if(views.includes(v)){
        const markers=Array.isArray(data.markers)?data.markers:[];
        const result={width:data.width || v.width,height:data.height||v.height,markers,detector:data.detector || 'fast-4x4-250',
          check:photoCheck(taskId,markers,data.check,lang)};
        if(ticket.precision){
          v.precise=result;
          v.result=classification(result.check)==='unknown'&&classification(v.fast?.check)!=='unknown'?v.fast:result;
        }else{v.fast=result;v.result=result;}
        if(rawAdvice)online=guardCloud(rawAdvice,taskId,views.at(-1).result.check,lang);
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
  if(!cloudReady||cloudJob||disposed||!views.length||views.some(v=>!v.result))return;
  const request=new AbortController();cloudJob=request;online=null;rawAdvice=null;cloudError=false;questionOpen=false;render();
  try{
    const shots=views.map(v=>({captureId:v.captureId,jpegDataUrl:v.jpegDataUrl,check:v.result.check,markers:v.result.markers,width:v.width,height:v.height}));
    const result=await cloud.reviewPhotos({taskId,lang,views:shots,question:$('question').value.trim(),signal:request.signal});
    if(cloudJob!==request||disposed)return;
    rawAdvice=result;online=guardCloud(result,taskId,views.at(-1).result.check,lang);
  }catch{
    if(cloudJob!==request||disposed)return;
    cloudError=true;
  }finally{if(cloudJob===request&&!disposed){cloudJob=null;render();}}
}
async function setLanguage(next) {
  const choice=String(next).startsWith('zh')?'zh':'en';
  if(lang===choice||disposed)return;
  lang=choice;cancelCloud();stopWorker();for(const v of views)resetResults(v);
  render();scheduleCheck();
}
window.ent207SetLanguage=setLanguage;
$('back').onclick=leave;$('pick').onchange=$('system-camera').onchange=importFiles;
$('retry').onclick=()=>{const v=selectedView();if(v){resetResults(v);render();scheduleCheck();}};
$('overlay').append(symbol('tags'));$('expand').append(symbol('expand'));$('stop-live').append(symbol('close'));
$('overlay').onclick=()=>{const v=selectedView();if(v){v.overlay=!v.overlay;render();}};
$('expand').onclick=()=>{roomy=!roomy;render();};$('stop-live').onclick=browse;
$('ask-question').onclick=()=>{questionOpen=!questionOpen;renderOnline();if(questionOpen)$('question').focus();};
$('send').onclick=sendPhotos;$('cancel-cloud').onclick=()=>{cancelCloud();render();};
$('question').oninput=()=>{if(online){rawAdvice=null;online=null;renderOnline();}};
$('live').onloadedmetadata=()=>{if(!disposed)renderControls();};
addEventListener('message',e=>{if(e.origin===location.origin && e.source===parent && e.data?.type==='ent207-review-dispose')dispose();});
document.addEventListener('visibilitychange',()=>{if(document.hidden && mode==='live')browse();});
addEventListener('pagehide',dispose);
render();
async function startReviewCamera(){
  if(!validTask||document.hidden)return;
  const handed=window.frameElement && (parent.ent207TakeReviewCamera?.(window)||parent.ent207TakeGuideCamera?.(window));
  if(handed||args.get('camera')==='auto'){await openCamera(handed||null);return;}
  if(args.get('camera')!=='granted')return;
  const ticket=generation;
  try{
    const permission=await navigator.permissions.query({name:'camera'});
    if(permission.state==='granted'&&ticket===generation&&!disposed&&!document.hidden&&mode==='browse'&&!views.length)await openCamera();
  }catch{}
}
void startReviewCamera();
import('../services/cloud.js').then(async api=>{
  const status=await api.cloudReady();if(disposed)return;
  cloud=api;cloudChecked=true;cloudReady=status===true || !!(status?.configured && status.enabled!==false);render();
}).catch(()=>{if(!disposed){cloudChecked=true;render();}});
