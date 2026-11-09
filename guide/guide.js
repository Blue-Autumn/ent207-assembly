'use strict';
const tr=(en,zh)=>AssemblyText.english?en:zh;
const t=AssemblyText.t;
const phone=document.querySelector('.phone');
const visual=$('visual');
const sheet=$('overlay');
const cameraTable=element('div',null,'camera-table');
const photoMode=button('',()=>toggleReference(),'photo-mode');
const realScan=button('',toggleRealScan,'live-scan-toggle');
let cameraError=false;
const livePick=createPickupScanner(visual,()=>pendingPickup(),hit=>{
 candidate=hit?.matches ? targetId() : null;
 observed=!!candidate;
 wrongChoice=hit && !hit.matches ? hit.part : '';
 render();
},()=>{
 cameraError=true;candidate=null;wrongChoice='';observed=false;render();
});
const testLog=makeTestLog(demoProfile);
let liveCheck=null, liveProblem='', correctionFromCamera=false, reviewFrame=null;
const alignButton=button('',toggleAlignment,'align-start');
const recordedRowButton=button('',showObservedRow,'align-row-demo');
const alignment=createAlignmentCamera(visual,{
 getTask:()=>current(),getLanguage:()=>AssemblyText.locale,
 onReading:check=>{
   if(phase!=='guide'||screen!=='work'||check.taskId!==active)return;
   liveCheck=check;paintLiveReading();testLog.add({...check,phase,source:'alignment'});
 },
 onClose:reason=>{
   liveCheck=null;liveProblem=['denied','reader-error','unavailable','ended'].includes(reason)?reason:'';
   render();
 }
});
function stopAlignment(){
 alignment.stop();liveCheck=null;liveProblem='';recordedRowButton.hidden=true;
}
function toggleAlignment(){
 if(alignment.active){stopAlignment();render();return;}
 leaveRealScan();RowLesson.stop();correctionFromCamera=false;liveCheck=null;liveProblem='';
 alignment.start();render();
}
function paintLiveReading(){
 if(!alignment.active)return;
 const check=liveCheck;
 const title=check?.headline||tr('Show the current parts','拍清当前板件');
 const body=check?.instruction||tr('Keep the target and reference tags in the same view.','让目标板件和参照标签一起入镜。');
 $('headline').textContent=title;$('workSubtitle').textContent=body;
 delete $('headline').dataset.mark;
 if(check?.stable){
   const aligned=['direction_ok','install_base_ready','install_row_ok','install_oriented'].includes(check.status)&&!check.placement;
   $('headline').dataset.mark=aligned?'ok':'adjust';
   say(title+'. '+body);
 }
 const row=check?.stable && check.status==='install_wrong_row' && check.observedRow && check.targetRow;
 recordedRowButton.hidden=!row;
 if(row)recordedRowButton.textContent=tr('Show row ','演示换排 ')+check.observedRow+' → '+check.targetRow;
}
function showObservedRow(){
 const saved=liveCheck;
 if(!saved?.stable||saved.status!=='install_wrong_row'||saved.taskId!==active)return;
 stopAlignment();
 if(RowLesson.start(current(),saved.observedRow,saved.targetRow)){
   correctionFromCamera=true;animationStart=performance.now();
   if(motion.matches)RowLesson.draw(0,true);
 }
 render();
}
const speechButton=button('',toggleVoice,'');
const fieldButton=button(tr('Field guidance','现场引导'),openField,'field-entry');
const motion=matchMedia('(prefers-reduced-motion: reduce)');
const routeParams=new URLSearchParams(location.search);
const rawOpenTask=openTask, rawCloseOverlay=closeOverlay, rawOpenOverlay=showOverlay;
let reference=false, tableTarget='', wrongChoice='', loop=0, lastAction=0, animationStart=0;
let voiceOn=false, spoken='', interacted=false, latestScene='';
try{voiceOn=localStorage.getItem('ent207.voice.v2')==='on'}catch{}
cameraTable.id='practiceTable';
cameraTable.setAttribute('role','group');
cameraTable.setAttribute('aria-label',tr('Practice scan with photographed parts','实物照片找件练习'));
speechButton.id='speakStep';
speechButton.innerHTML='<svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 8a6 6 0 0 1 0 8M18 5a10 10 0 0 1 0 14"/></svg>';
$('progressButton').before(speechButton);
visual.append(cameraTable,photoMode,fieldButton,realScan,alignButton,recordedRowButton);
recordedRowButton.hidden=true;
$('taskList').replaceChildren();
cv.setAttribute('aria-label',tr('Part model. Drag to rotate.','板件模型，可拖动旋转。'));
const sourceLabels=[...document.querySelectorAll('[aria-label]')].map(e=>[e,e.getAttribute('aria-label')]);
const sourceCopy=[...document.querySelectorAll('[data-static-copy]')].map(e=>[e,e.textContent]);
function localizeGuide(){
 document.documentElement.lang=AssemblyText.locale;document.title=tr('Guided assembly','引导装配');
 for(const [e,key] of sourceLabels)e.setAttribute('aria-label',AssemblyText.phrase(key));
 for(const [e,key] of sourceCopy)e.textContent=AssemblyText.phrase(key);
 cv.setAttribute('aria-label',tr('Part model. Drag to rotate.','板件模型，可拖动旋转。'));
 fieldButton.textContent=tr('Field guidance','现场引导');
 cameraTable.setAttribute('aria-label',tr('Practice scan with photographed parts','实物照片找件练习'));
 photoMode.textContent=tr('Inspect in 3D','查看模型');
}
localizeGuide();
function leaveRealScan(){
 if(livePick.active){
  livePick.stop();candidate=null;observed=false;wrongChoice='';
 }
 cameraError=false;
}
function toggleRealScan(){
 if(livePick.active)leaveRealScan();
 else{
  reference=false;candidate=null;observed=false;wrongChoice='';cameraError=false;
  livePick.start();
 }
 render();
}
function exitGuide(){
 stopAlignment();leaveRealScan();
 window.speechSynthesis?.cancel();
 cancelAnimationFrame(loop);
 if(parent!==window)parent.postMessage({type:'ent207-close'},location.origin);
 else location.href='../';
}
function say(text){
 const synth=window.speechSynthesis;
 if(!voiceOn||!synth||!interacted||document.hidden||spoken===text||!text)return;
 spoken=text;
 synth.cancel();
 const line=new SpeechSynthesisUtterance(text);
 line.lang=AssemblyText.english?'en-US':'zh-CN';
 try{
   const wanted=localStorage.getItem('ent207.tts.voice.v1.'+(AssemblyText.english?'en':'zh'));
   const selected=synth.getVoices().find(v=>v.localService && v.voiceURI===wanted && v.lang.toLowerCase().startsWith(line.lang.slice(0,2)));
   if(selected)line.voice=selected;
 }catch{}
 synth.speak(line);
}
function toggleVoice(){
 interacted=true;voiceOn=!voiceOn;spoken='';
 try{localStorage.setItem('ent207.voice.v2',voiceOn?'on':'off')}catch{}
 if(!voiceOn)window.speechSynthesis?.cancel();
 render();
}
function instructionType(task){
 if(task.kind==='prepare')return layerTask(task)?'prepareLayer':'prepareFrame';
 if(task.id===flow.main[0])return 'base';
 if(layerTask(task))return 'layer';
 return task.id===flow.main.at(-1)?'back':'cover';
}
function halfName(id){
 if(!id)return '';
 const name=AssemblyText.partName(id.replace(/-[12]$/,''));
 return name+tr(' · half ',' · 半件 ')+id.slice(-1);
}
function animate(){
 cancelAnimationFrame(loop);loop=0;
 if(document.hidden||alignment.active||sheet.classList.contains('open')||screen!=='work'||phase!=='guide'||!ready(active))return;
 if(motion.matches){amount=1;paintScene();return}
 const initial=animationStart;
 let previous=0;
 function tick(now){
  loop=requestAnimationFrame(tick);
  if(now-previous<33)return;
  previous=now;
  if(RowLesson.active()){
   if(RowLesson.draw(now-initial).done){
     RowLesson.stop();animationStart=now;
     if(correctionFromCamera){correctionFromCamera=false;toggleAlignment();}else render();
   }
   return;
  }
  const frame=AssemblyMotionCycle.sampleInset(now-initial);
  amount=frame.amount;cv.style.opacity=String(frame.opacity);paintScene();
 }
 loop=requestAnimationFrame(tick);
}
closeOverlay=function(){
 if(reviewFrame){reviewFrame.contentWindow?.postMessage({type:'ent207-review-dispose'},location.origin);reviewFrame=null;}
 rawCloseOverlay();for(const control of [document.querySelector('.top'),document.querySelector('.body'),$('footer')])control.inert=false;sheet.classList.remove('milestone-page','progress-page','review-page','frame-review','visitor-review');
 sheet.removeAttribute('data-milestone');
 if(typeof latestScene==='string')animate();
};
showOverlay=function(title){
 stopAlignment();correctionFromCamera=false;
 if(livePick.active){leaveRealScan();render()}
 const box=rawOpenOverlay(title);
 cancelAnimationFrame(loop);
 window.speechSynthesis?.cancel();
 box.scrollTop=0;
 for(const control of [document.querySelector('.top'),document.querySelector('.body'),$('footer')])control.inert=true;
 box.querySelector('.close').focus({preventScroll:true});
 box.querySelector('.close').setAttribute('aria-label',tr('Close','关闭'));
 return box;
};
openTask=function(id,dependency=false,persist=true){
 stopAlignment();correctionFromCamera=false;
 leaveRealScan();
 lastAction=0;reference=false;wrongChoice='';tableTarget='';animationStart=performance.now();RowLesson.stop();
 rawOpenTask(id,dependency,persist);
};

function openField(){
 stopAlignment();correctionFromCamera=false;
 if(livePick.active){leaveRealScan();render()}
 window.speechSynthesis?.cancel();
 if(parent!==window)parent.postMessage({type:'ent207-field'},location.origin);
 else location.href='../field/?lang='+encodeURIComponent(AssemblyText.locale);
}
function toggleReference(){
 reference=!reference;
 if(phase==='find'){render();return}
 showFieldReview();
}
function choosePiece(id){
 cameraError=false;
 if(phase!=='find'||candidate)return;
 if(pendingPickup().includes(id)){
  candidate=targetId();observed=true;wrongChoice='';reference=false;
 }else wrongChoice=id;
 render();
}
function populateTable(target){
 if(tableTarget!==target){
  tableTarget=target;cameraTable.replaceChildren();
  const all=Object.keys(pieceImages),index=all.indexOf(target);
  const group=target.slice(0,-2);
  const others=all.filter(id=>id.slice(0,-2)!==group);
  const first=others[(index*3+4)%others.length];
  const remaining=others.filter(id=>id.slice(0,-2)!==first.slice(0,-2));
  const choices=[first,remaining[(index*5)%remaining.length]];
  choices.splice(index%3,0,target);
  for(const [i,id] of choices.entries()){
   const choice=button('',()=>choosePiece(id),'scan-choice');
   choice.dataset.part=id;
   choice.setAttribute('aria-label',tr('Scan ','模拟扫描')+halfName(id));
   const img=new Image();img.src='pieces/'+pieceImages[id];img.alt='';
   const num=element('span',String(i+1),'piece-number');
   choice.append(img,num);
   cameraTable.append(choice);
  }
 }
 for(const choice of cameraTable.children){
  choice.classList.toggle('match',!!candidate&&choice.dataset.part===target);
  choice.classList.toggle('mismatch',wrongChoice===choice.dataset.part);
  choice.setAttribute('aria-pressed',String(!!candidate&&choice.dataset.part===target));
 }
}
function statusCopy(task){
 const missing=task.requires.find(id=>!done.has(id));
 if(missing)return t('instruction.missing',{task:taskName(tasks.get(missing))});
 if(task.kind==='install'&&layerTask(task)){
  const row=Number(task.group[1]);
  return tr('Match the notch to the model. Lower both tabs into slot row '+row+'.','缺口对准模型所示方向，将两端凸榫插入第 '+row+' 排孔。');
 }
 return t('short.'+instructionType(task));
}
function checkCopy(task){
 switch(instructionType(task)){
  case 'prepareLayer':return tr('Either face can be up on each half. Match the notch direction and check the seam.','两半可分别使用 A 或 B 面。缺口指向同一侧，再检查接缝。');
  case 'prepareFrame':return task.group==='背板'?tr('Check that the back panel ends match and the seam lies flat.','比对背板两半的两端，确认接缝齐平。'):tr('Keep the two halves of this frame together. Check the face and both ends, even if the seam looks flush.','左右框半件不能互换，核对配对、面向与两端方向，再确认接头。');
  case 'base':return tr('The left frame rests flat with its slots facing up.','左侧基准框平放在桌面，槽口朝上。');
  case 'layer':return tr('Check both ends in row '+task.group[1]+'. The shelf should stand upright.','检查两端均已插入第 '+task.group[1]+' 排，层板保持直立。');
  case 'cover':return tr('Check that all five shelf ends are seated and the top frame lies level.','五层板的自由端都已插入对应槽口，上方灰框应保持水平。');
  default:return tr('Check the back panel against both frames. The two ends should sit flush.','核对背板与上下灰框的接头，两端应装入到位。');
 }
}
function showFrameJoint(){
 const box=showOverlay(tr('Frame joint photos','灰框接头实拍'));
 box.classList.add('frame-review');
 const iframe=document.createElement('iframe');
 iframe.title=tr('Left frame joint examples','左侧框接头示例');
 iframe.src='gray/index.html?lang='+(AssemblyText.english?'en':'zh');
 box.append(iframe);
}
function showFieldReview(){
 reference=false;
 const box=showOverlay(tr('This joint is seated','这处接好了'));
 box.classList.add('review-page');
 const body=element('p',tr('The purple shelf stands upright in the frame slot. The seam is flush.','紫色层板已经立进侧框槽里，这处接缝齐平。'),'review-copy');
 const tag=element('small',tr('Saved AI review · Purple shelf','AI 复核样例 · 紫色层板'),'review-label');
 const img=new Image();img.src='joint-photo.webp';img.alt=tr('A purple shelf fitted to the grey frame','紫色层板插入灰色基准框');
 const figure=element('figure',null,'field-photo');figure.append(img);
 const replay=button(tr('Show slot guidance','查看孔位提示'),()=>{
  const rings=img.getAttribute('src')==='row-example.webp';
  img.src=rings?'joint-photo.webp':'row-example.webp';
  replay.textContent=rings?tr('Show slot guidance','查看孔位提示'):tr('Show review photo','查看复核照片');
  body.textContent=rings?tr('The purple shelf stands upright in the frame slot. The seam is flush.','紫色层板已经立进侧框槽里，这处接缝齐平。'):tr('On the phone, the two gold rings mark the target slots on the base frame.','现场引导中，两个金色圆圈标出基准框的目标孔。');
 },'review-evidence');
 box.prepend(tag);box.append(body,figure,replay,button(tr('Return to assembly','返回装配'),closeOverlay,'primary full'));
}
function visitorReview(restore=false){
 if(reviewFrame)return;
 if(restore!==true && history.state?.ent207Review!==active){
   history.pushState({ent207Review:active},'',location.href);
 }
 const box=showOverlay(tr('Photo review','照片复核'));box.classList.add('visitor-review');
 reviewFrame=document.createElement('iframe');reviewFrame.allow="camera 'self'";
 reviewFrame.title=tr('Review the current joint','复核当前接头');
 reviewFrame.src='../review/index.html?'+new URLSearchParams({task:active,lang:AssemblyText.english?'en':'zh'});
 box.append(reviewFrame);testLog.add({taskId:active,phase,source:'photos',status:'opened'});
}
addEventListener('message',event=>{
 if(event.origin===location.origin && reviewFrame && event.source===reviewFrame.contentWindow && event.data?.type==='ent207-review-close'){
   if(history.state?.ent207Review===active)history.back();else closeOverlay();
 }
});
addEventListener('popstate',()=>{
 if(history.state?.ent207Review===active){if(!reviewFrame)visitorReview(true);}
 else if(reviewFrame)closeOverlay();
});
function stepList(){
 const box=showOverlay(t('work.progress'));box.classList.add('progress-page');
 box.append(element('p',t('progress.summary',{done:done.size,total:order.length}),'subtitle'));
 const tools=element('div',null,'group-rows');box.append(element('h3',t('menu.controls'),'group-heading'),tools);
 tools.append(button(tr('Review my photos','复核我的照片'),visitorReview,'menu-action'));
 tools.append(button(testLog.active?tr('Save test log','保存调试记录'):tr('Record a device test','记录本机测试'),()=>{
  if(testLog.active){testLog.download();stepList();return;}
  const notice=showOverlay(tr('Record a device test','记录本机测试'));
  notice.append(element('p',tr('This tab can record steps, marker IDs and guidance results for troubleshooting. Photos and account names stay out of the log. Stop and save the JSON file here when finished.','记录本页的步骤、标签编号和引导结果，供排查问题。记录不含照片和账号名，结束后可以在这里保存 JSON 文件。')),
    button(tr('Start recording','开始记录'),()=>{testLog.start();stepList()},'primary full'));
 },'menu-action'));
 tools.append(button(tr('Explore field guidance','体验现场引导'),openField,'menu-action'));
 tools.append(button(tr('Review a field photo','查看实拍复核示例'),showFieldReview,'menu-action'));
 tools.append(button(tr('Compare frame joints','对比灰框接头'),showFrameJoint,'menu-action'));
 tools.append(button(tr('Reset this assembly','重置本次装配'),askReset,'menu-action restart-row'));
 for(const [group,ids] of [['menu.prepare',flow.preparations],['menu.install',flow.main]]){
  const heading=element('h3',t(group),'group-heading');heading.append(element('small',counts(ids)));
  const list=element('div',null,'group-rows');
  for(const id of ids){
   const task=tasks.get(id),status=done.has(id)?'done':ready(id)?'ready':'wait';
   const row=button('',()=>openTask(id),'step-row');
   row.dataset.task=id;row.dataset.status=status;
   row.append(element('span',String(order.indexOf(id)+1).padStart(2,'0'),'step-number'),
    element('span',id===flow.main[0]?t('menu.base'):AssemblyText.partName(task.group),'step-name'),
    element('small',status==='done'?'✓':status==='ready'?t('menu.next'):'○'));
   list.append(row);
  }
  box.append(heading,list);
 }
}
function askReset(){
 const box=showOverlay(tr('Restart this assembly?','重新开始这次装配？'));
 box.append(element('p',tr('Clear the parts and steps saved for this profile.','清除这个身份已拿取的零件和装配进度。'),'subtitle'),
  button(tr('Keep my progress','保留进度'),stepList,'primary full'),
  button(tr('Reset and restart','清除并重新开始'),()=>{
   done.clear();picked.clear();pickups={};returnStack=[];actionHistory=[];active=order[0];openTask(active);
  }));
}
window.onAssemblyMilestone=(kind,next,proceed)=>{
 const complete=kind==='complete';
 const box=showOverlay(t(complete?'milestone.complete.title':'milestone.prepared.title'));
 box.classList.add('milestone-page');box.dataset.milestone=kind;
 box.append(element('div',null,'milestone-mark'),element('p',t(complete?'milestone.complete.body':'milestone.prepared.body'),'subtitle'),
  element('p',t('progress.summary',{done:done.size,total:order.length}),'milestone-count'),
  button(t(complete?'action.backProject':'action.startAssembly'),complete?exitGuide:proceed,'primary full'));
 return true;
};
window.assemblyBackTicket=()=>previousAction()?completionTicket():null;
window.assemblySwipeBack=ticket=>{if(ticketCurrent(ticket))rewindAction(ticket)};
const rowButton=button(tr('Try a wrong row','试看插错排'),()=>{
 const row=Number(current().group[1]);
 if(RowLesson.active()){RowLesson.stop();if(correctionFromCamera){correctionFromCamera=false;toggleAlignment();}else render();return}
 if(RowLesson.start(current(),row===1?2:row-1,row)){
  animationStart=performance.now();
  if(motion.matches)RowLesson.draw(0,true);
  render();
 }
},'row-example');
visual.append(rowButton);
render=function(){
 const task=current(),homeView=screen==='home',finding=!homeView&&phase==='find';
 const review=phase==='review'&&!homeView,completed=done.has(active);
 const scene=[active,phase,screen].join('|');
 if(scene!==latestScene){animationStart=performance.now();latestScene=scene}
 phone.classList.toggle('work-page',!homeView);phone.classList.toggle('found',finding&&!!candidate);
 phone.classList.toggle('problem',finding&&!!wrongChoice);
 phone.classList.toggle('local-direction',review);
 $('home').style.display=homeView?'block':'none';$('work').style.display=homeView?'none':'block';
 $('homeSummary').hidden=!homeView;$('footer').style.display=homeView?'none':'flex';
 $('visual').style.display='block';
 const scanning=finding&&livePick.active;
 alignButton.hidden=homeView||phase!=='guide'||!ready(active)||alignment.active;
 alignButton.textContent=tr('Live guidance','实拍指导');
 if(!alignment.active)recordedRowButton.hidden=true;
 fieldButton.hidden=alignment.active;
 cameraTable.hidden=!finding||reference||scanning;
 realScan.hidden=!finding;
 realScan.textContent=scanning?tr('Close camera','关闭相机'):tr('Scan real tags','扫描实物');
 realScan.setAttribute('aria-pressed',String(scanning));
 visual.classList.toggle('live-pick',scanning);
 visual.classList.toggle('photo-pick',finding&&!reference);
 if(finding)populateTable(targetId());
 photoMode.hidden=!finding||scanning;photoMode.textContent=reference?tr('Back to parts','返回找件'):tr('Inspect in 3D','查看模型');
 rowButton.hidden=alignment.active||phase!=='guide'||homeView||!ready(active)||task.kind!=='install'||!layerTask(task);
 rowButton.textContent=RowLesson.active()?tr('Back to the step','返回本步'):tr('Try a wrong row','试看插错排');
 $('aiAssist').hidden=homeView||finding;
 const groups=pickupTargets(task);
 AssemblyWorkControls.update({screen,phase,taskId:active,done:done.size,total:order.length,
  picked:groups.filter(group=>group.every(id=>picked.has(id))).length,required:groups.length});
 $('topContext').textContent=homeView?t('work.overview'):(alignment.active?tr('Live guidance','实拍指导'):tr('Guided rehearsal','模拟装配'))+' · '+(order.indexOf(active)+1)+' / '+order.length;
 speechButton.hidden=!('speechSynthesis'in window);speechButton.classList.toggle('muted',!voiceOn);
 speechButton.setAttribute('aria-label',t(voiceOn?'voice.on':'voice.off'));speechButton.setAttribute('aria-pressed',String(voiceOn));
 if(homeView){
  $('home').querySelector('h1').textContent=tr('Five-tier shelf','五层隔板架');
  $('nextText').textContent=t(allDone()?'home.finished':'home.subtitle');
  $('continue').textContent=t(allDone()?'home.summary':hasSavedProgress()?'home.continue':'home.start');
  $('startOver').hidden=!hasSavedProgress();$('homeProgress').textContent=t('home.progress',{prepare:counts(flow.preparations),install:counts(flow.main)});
  $('homeBar').style.width=(100*done.size/order.length)+'%';
  window.finderModel=false;window.uiTable=false;configureOverview();
 }else{
  let title,body,action;
  if(finding){
   const name=groupedPickup()?AssemblyText.partName(task.group):halfName(targetId());
   title=candidate?t('work.found'):t('work.find',{part:name});
   body=candidate?tr('Matching tag. Collect this piece to continue.','标签匹配，确认拿取后继续。'):wrongChoice?tr('Different piece. Match the target and try another tag.','这不是目标件，试试另一块板的标签。'):reference?tr('Drag to inspect the target part.','拖动查看目标件形状。'):tr('Practice scan · Tap a photographed piece to check its tag.','模拟扫码 · 点选实物照片，核对板件标签。');
   action=candidate?t('work.pickup'):tr('Show the matching part','显示目标零件');
   if(scanning){
    const hit=livePick.hit;
    const scanned=hit?String(hit.id).padStart(3,'0')+' · '+halfName(hit.part)+' · '+hit.face:'';
    body=hit ? (hit.matches?tr('Matched: ','匹配：'):tr('Different piece: ','其他板件：'))+scanned :
     tr('Aim at a printed marker. The camera checks part identity on this device.','将相机对准板件上的标签，在本机核对身份。');
    if(!candidate)action=tr('Continue with photos','使用照片继续');
   }else if(cameraError){
    body=tr('Camera unavailable. Allow camera access to retry, or continue with the photos.','相机暂不可用。可允许相机权限后重试，或继续照片练习。');
   }
  }else if(review){
   title=completed?t('work.complete'):tr('Check the fit','装好了吗？');body=completed?t('work.saved'):checkCopy(task);action=t(completed?'work.next':'work.confirm');
  }else{
   title=taskName(task);body=liveProblem?tr('Camera unavailable. Continue with the model or select a photo for review.','相机暂不可用，可看模型继续或选照片复核。'):statusCopy(task);action=ready(active)?tr('Check the fit','检查装配效果'):t('work.prepareFirst');
  }
  $('headline').textContent=title;$('workSubtitle').textContent=body;
  const mark=candidate||review?'ok':wrongChoice?'adjust':null;
  if(mark)$('headline').dataset.mark=mark;else delete $('headline').dataset.mark;
  $('primary').textContent=action;$('primary').disabled=false;window.finderModel=finding;window.uiTable=!finding;
  if(alignment.active)paintLiveReading();
  else if(!sheet.classList.contains('open'))say(title+'. '+body);
  testLog.add({taskId:active,phase,source:scanning?'scan':alignment.active?'alignment-view':'guide',status:done.has(active)?'confirmed':phase,markerIds:livePick.hit?[livePick.hit.id]:[]});
 }
 window.sceneSurface='rgba(0,0,0,0)';
 if(phase!=='guide'||screen!=='work')cv.style.opacity='1';
 animate();paintScene();
};
$('primary').onclick=event=>{
 const now=Date.now();
 if(now-lastAction<170)return;lastAction=now;interacted=true;
 if(phase==='find'&&livePick.active){
  const hit=livePick.match();
  leaveRealScan();
  if(!hit){render();return}
  candidate=targetId();observed=true;
 }
 if(phase==='find'&&!candidate){choosePiece(targetId());return}
 if(phase==='guide'&&ready(active)&&!done.has(active)&&pickupComplete()){
  stopAlignment();correctionFromCamera=false;RowLesson.stop();phase='review';taskVisit++;model(false,true);render();return;
 }
 reference=false;wrongChoice='';
 handlePrimary(event);
};
$('homeButton').onclick=exitGuide;$('progressButton').onclick=stepList;
$('aiAssist').onclick=visitorReview;$('startOver').onclick=askReset;
document.addEventListener('keydown',e=>{
 if(e.key==='Escape'){if(reviewFrame && history.state?.ent207Review===active)history.back();else if(sheet.classList.contains('open'))closeOverlay();else exitGuide()}
});
document.addEventListener('pointerdown',()=>{interacted=true},{once:true});
document.addEventListener('visibilitychange',()=>{
 if(document.hidden){leaveRealScan();window.speechSynthesis?.cancel()}
 render();
});
window.addEventListener('pagehide',()=>{stopAlignment();leaveRealScan();cancelAnimationFrame(loop);window.speechSynthesis?.cancel()});
window.ent207SetLanguage=value=>{
 AssemblyText.setLanguage(value);alignment.setLanguage();localizeGuide();tableTarget='';
 const inner=sheet.querySelector('iframe');
 if(inner?.contentWindow?.ent207SetLanguage)inner.contentWindow.ent207SetLanguage(value);
 else if(sheet.classList.contains('progress-page'))stepList();
 else if(sheet.classList.contains('frame-review'))showFrameJoint();
 else if(sheet.classList.contains('review-page'))showFieldReview();
 else if(sheet.classList.contains('open'))closeOverlay();
 render();
};
if(allDone())openTask(order.at(-1),true,false);else openTask(resumeTask(),true,false);
if(routeParams.get('page')==='progress')stepList();
else if(allDone())summary();
delete document.documentElement.dataset.starting;

