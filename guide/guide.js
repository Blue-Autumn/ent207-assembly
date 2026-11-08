'use strict';
const tr=(en,zh)=>AssemblyText.english?en:zh;
const t=AssemblyText.t;
const phone=document.querySelector('.phone');
const visual=$('visual');
const sheet=$('overlay');
const cameraTable=element('div',null,'camera-table');
const photoMode=button('',()=>toggleReference(),'photo-mode');
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
visual.append(cameraTable,photoMode,fieldButton);
$('taskList').replaceChildren();
cv.setAttribute('aria-label',tr('Part model. Drag to rotate.','板件模型，可拖动旋转。'));
document.documentElement.lang=AssemblyText.locale;
document.title=tr('Guided assembly','引导装配');
document.querySelectorAll('[aria-label]').forEach(el=>el.setAttribute('aria-label',AssemblyText.phrase(el.getAttribute('aria-label'))));
document.querySelectorAll('[data-static-copy]').forEach(el=>el.textContent=AssemblyText.phrase(el.textContent));

function exitGuide(){
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
 if(document.hidden||sheet.classList.contains('open')||screen!=='work'||phase!=='guide'||!ready(active))return;
 if(motion.matches){amount=1;paintScene();return}
 const initial=animationStart;
 let previous=0;
 function tick(now){
  loop=requestAnimationFrame(tick);
  if(now-previous<33)return;
  previous=now;
  if(RowLesson.active()){
   if(RowLesson.draw(now-initial).done){RowLesson.stop();animationStart=now;render()}
   return;
  }
  const frame=AssemblyMotionCycle.sampleInset(now-initial);
  amount=frame.amount;cv.style.opacity=String(frame.opacity);paintScene();
 }
 loop=requestAnimationFrame(tick);
}
closeOverlay=function(){
 rawCloseOverlay();for(const control of [document.querySelector('.top'),document.querySelector('.body'),$('footer')])control.inert=false;sheet.classList.remove('milestone-page','progress-page','review-page');
 sheet.removeAttribute('data-milestone');
 if(typeof latestScene==='string')animate();
};
showOverlay=function(title){
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
 lastAction=0;reference=false;wrongChoice='';tableTarget='';animationStart=performance.now();RowLesson.stop();
 rawOpenTask(id,dependency,persist);
};

function openField(){
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
function stepList(){
 const box=showOverlay(t('work.progress'));box.classList.add('progress-page');
 box.append(element('p',t('progress.summary',{done:done.size,total:order.length}),'subtitle'));
 const tools=element('div',null,'group-rows');box.append(element('h3',t('menu.controls'),'group-heading'),tools);
 tools.append(button(tr('Explore field guidance','体验现场引导'),openField,'menu-action'));
 tools.append(button(tr('Review a field photo','查看实拍复核示例'),showFieldReview,'menu-action'));
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
 if(RowLesson.active()){RowLesson.stop();render();return}
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
 cameraTable.hidden=!finding||reference;
 visual.classList.toggle('photo-pick',finding&&!reference);
 if(finding)populateTable(targetId());
 photoMode.hidden=!finding;photoMode.textContent=reference?tr('Back to parts','返回找件'):tr('Inspect in 3D','查看模型');
 rowButton.hidden=phase!=='guide'||homeView||!ready(active)||task.kind!=='install'||!layerTask(task);
 rowButton.textContent=RowLesson.active()?tr('Back to the step','返回本步'):tr('Try a wrong row','试看插错排');
 $('aiAssist').hidden=homeView||finding;
 const groups=pickupTargets(task);
 AssemblyWorkControls.update({screen,phase,taskId:active,done:done.size,total:order.length,
  picked:groups.filter(group=>group.every(id=>picked.has(id))).length,required:groups.length});
 $('topContext').textContent=homeView?t('work.overview'):tr('Guided rehearsal','模拟装配')+' · '+(order.indexOf(active)+1)+' / '+order.length;
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
  }else if(review){
   title=completed?t('work.complete'):tr('Check the fit','装好了吗？');body=completed?t('work.saved'):checkCopy(task);action=t(completed?'work.next':'work.confirm');
  }else{
   title=taskName(task);body=statusCopy(task);action=ready(active)?tr('Check the fit','检查装配效果'):t('work.prepareFirst');
  }
  $('headline').textContent=title;$('workSubtitle').textContent=body;
  const mark=candidate||review?'ok':wrongChoice?'adjust':null;
  if(mark)$('headline').dataset.mark=mark;else delete $('headline').dataset.mark;
  $('primary').textContent=action;$('primary').disabled=false;window.finderModel=finding;window.uiTable=!finding;
  if(!sheet.classList.contains('open'))say(title+'. '+body);
 }
 window.sceneSurface='rgba(0,0,0,0)';
 if(phase!=='guide'||screen!=='work')cv.style.opacity='1';
 animate();paintScene();
};
$('primary').onclick=event=>{
 const now=Date.now();
 if(now-lastAction<170)return;lastAction=now;interacted=true;
 if(phase==='find'&&!candidate){choosePiece(targetId());return}
 if(phase==='guide'&&ready(active)&&!done.has(active)&&pickupComplete()){
  RowLesson.stop();phase='review';taskVisit++;model(false,true);render();return;
 }
 reference=false;wrongChoice='';
 handlePrimary(event);
};
$('homeButton').onclick=exitGuide;$('progressButton').onclick=stepList;
$('aiAssist').onclick=showFieldReview;$('startOver').onclick=askReset;
document.addEventListener('keydown',e=>{
 if(e.key==='Escape'){if(sheet.classList.contains('open'))closeOverlay();else exitGuide()}
});
document.addEventListener('pointerdown',()=>{interacted=true},{once:true});
document.addEventListener('visibilitychange',()=>{if(document.hidden)window.speechSynthesis?.cancel();animate()});
window.addEventListener('pagehide',()=>{cancelAnimationFrame(loop);window.speechSynthesis?.cancel()});
if(allDone())openTask(order.at(-1),true,false);else openTask(resumeTask(),true,false);
if(routeParams.get('page')==='progress')stepList();
else if(allDone())summary();
delete document.documentElement.dataset.starting;

