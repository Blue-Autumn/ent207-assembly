'use strict';
const uiText=(key,args)=>AssemblyText.t(key,args);
function localizeMarkup() {
 if(!AssemblyText.english)return;
 document.title='Rack assembly';
 for(const node of document.querySelectorAll('[aria-label]'))node.setAttribute('aria-label',AssemblyText.phrase(node.getAttribute('aria-label')));
 for(const node of document.querySelectorAll('[data-static-copy]'))node.textContent=AssemblyText.phrase(node.textContent);
}
localizeMarkup();
const nativeEntry=typeof AssemblyNative.launchPage==='function'?AssemblyNative.launchPage():'home';
window.onNativeSafeInsets=event=>{
 for(const edge of ['top','bottom','left','right'])document.documentElement.style.setProperty('--safe-'+edge,Math.max(0,Number(event?.[edge])||0)+'px');
 if(typeof readyUI!=='undefined'&&readyUI)sendViewport();
};
// Android-only adapter: no simulated detector, historical photograph, or fake AI response.
const registeredParts=JSON.parse(AssemblyNative.registered());
let scanRequest=null,lastMarker=null;
let speechToken=null,speechState='idle',speechMessage='',speechContext='';
let voiceEnabled=typeof AssemblyNative.voiceEnabled==='function'?AssemblyNative.voiceEnabled():true;
let completionSpeech=null,completionNoticeTimer=null;
let manualConfirmUntil=0,manualConfirmTimer=0,remindedFor='';
const spokenReminderStates=new Set();
const speechButton=document.createElement('button');speechButton.id='speakStep';speechButton.setAttribute('aria-label',AssemblyText.phrase('听这一步'));
speechButton.innerHTML='<span class="sr-only"></span><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 8a6 6 0 0 1 0 8M18 5a10 10 0 0 1 0 14"/></svg>';
const speechStatus=document.createElement('small');speechStatus.id='speechStatus';speechStatus.setAttribute('role','status');
document.querySelector('.top').insertBefore(speechButton,$('progressButton'));$('targetName').after(speechStatus);
function instructionFor(t){
 return uiText('instruction.'+instructionKey(t));
}
function pickupName(spoken=false){
 if(groupedPickup())return AssemblyText.partName(current().group);
 return spoken&&!AssemblyText.english?AssemblyNarration.partName(targetId()):label(targetId());
}
function pickupMarkers(){return pendingPickup().flatMap(id=>registeredParts[id]||[])}
function readyAction(cue){
 switch(cue?.state){
  case 'install_base_ready':return {button:AssemblyText.phrase('底框已放稳，下一步'),hint:AssemblyText.phrase('放稳后点下一步，接下来找紫色层板。'),speech:AssemblyText.phrase('摆好了，点下一步。'),reminder:AssemblyText.phrase('底框放稳了吗？点下面，去找紫色层板。')};
  case 'install_row_ok':return {button:AssemblyText.phrase('已插好，下一步'),hint:AssemblyText.phrase('扶稳层板，向下插到接缝齐平，再点下一步。'),speech:AssemblyText.phrase('对上了。插到齐平，再点下一步。'),reminder:AssemblyText.phrase('两端都齐平了吗？点下面，继续装。')};
  case 'install_oriented':return {button:AssemblyText.phrase('已装好，下一步'),hint:cue.message+AssemblyText.phrase(' 贴齐后点下一步。'),speech:AssemblyText.phrase('对上了。贴齐后点下一步。'),reminder:AssemblyText.phrase('两端都接好了吗？点下面，继续装。')};
  case 'direction_ok':return cue.requiresPlacement?null:{button:AssemblyText.phrase('已拼好，下一步'),hint:cue.message,speech:AssemblyText.phrase('方向对了。接好后点下一步。'),reminder:AssemblyText.phrase('接缝齐平了吗？拼好就点下面。')};
  default:return null;
 }
}
function clearManualConfirm(){
 clearTimeout(manualConfirmTimer);manualConfirmUntil=0;
}
function instructionKey(t){if(t.kind==='prepare')return AssemblyText.isLayer(t)?'prepareLayer':'prepareFrame';
 if(t.id==='install:左侧板')return 'base';if(AssemblyText.isLayer(t))return 'layer';if(t.id==='install:右侧板')return 'cover';return 'back'}
function checksFor(t){
 if(t.authoredAccept)return [t.authoredAccept];
 if(t.id==='install:右侧板')return [AssemblyText.phrase('五层板的自由端均已进入对应槽位'),AssemblyText.phrase('逐个接口已到位，侧框没有明显翘起')];
 if(t.id==='install:背板')return [AssemblyText.phrase('背板各处接头均已进入对应位置'),AssemblyText.phrase('两端没有明显错位或翘起')];
 return t.suggestedChecks;
}
function stuckAdvice(t){
 if(t.id==='install:左侧板')return AssemblyText.phrase('先确认平放的是模型左侧框，槽口朝上，摆放方向对照动画。');
 if(t.kind==='prepare'&&AssemblyText.isLayer(t))return AssemblyText.phrase('先核对是同一层的两半，退回一点，同时对准两端接头，再垂直接合。');
 if(t.group.includes('侧板'))return AssemblyText.phrase('先停下并退回一点。侧框的四个半件不能互换，对照当前模型逐个检查接口。');
 return AssemblyText.phrase('先停下并退回一点，对照当前模型检查两端是否都对准，接头没有对齐时不要继续推进。');
}
function instructionNow(){const missing=current().requires.find(id=>!done.has(id));return missing?uiText('instruction.missing',{task:taskName(tasks.get(missing))}):instructionFor(current())}
const narration=AssemblyNarration.create({
 speak:(text,token)=>AssemblyNative.speak(text,token),stop:()=>AssemblyNative.stopSpeech(),
 changed:event=>{
  speechToken=event.token;speechState=event.state;speechMessage=event.state==='speaking'?AssemblyText.phrase('正在朗读'):event.message;
  const activeCue=event.state==='requested'?narration.snapshot().active:null;
  if(activeCue?.kind==='reminder'){spokenReminderStates.add(activeCue.key);remindedFor=activeCue.action}
  renderSpeech();
 }
});
function stopStepSpeech(){completionSpeech=null;narration.cancel(true)}
function renderSpeech(){const text=uiText(voiceEnabled?'voice.on':'voice.off');speechButton.firstElementChild.textContent=text;speechButton.setAttribute('aria-label',text);speechButton.setAttribute('aria-pressed',String(voiceEnabled));speechButton.classList.toggle('muted',!voiceEnabled);speechButton.classList.toggle('speaking',!!speechToken);speechStatus.textContent=AssemblyText.english&&/[\u3400-\u9fff]/.test(speechMessage)?AssemblyText.phrase('语音暂不可用，请看画面。'):speechMessage}
function automaticSpeech(){
 narration.setEnabled(voiceEnabled);
 if(completionSpeech&&completionSpeech.context!==speechContext)completionSpeech=null;
 if(voiceEnabled&&appActive&&completionSpeech){narration.observe(completionSpeech);return}
 if(!voiceEnabled||!appActive||aiOpening||jointOpening||$('overlay').classList.contains('open'))return;
 // The AI page already reads its result. Returning must not read it a second time.
 const now=performance.now(),presentation=guideWanted()?guidePresentation.read(now,current().kind==='install'):null;
 const verdict=presentation?.instruction;
 const next=presentation?.ready?readyAction(verdict):null;
 if(next){
  const key='reminder|'+verdict.state;
  const fresh=guideObservation?.visible&&guideObservation.stable&&guideObservation.state===verdict.state&&now-guideLastAt<650;
  if(remindedFor===verdict.state){narration.observe(null);return}
  if(!spokenReminderStates.has(key) && fresh && presentation.readySince!==null && now-presentation.readySince>=7500){
   narration.observe({key,text:next.reminder,action:verdict.state,kind:'reminder',stableMs:0});return;
  }
  narration.observe({key:'ready|'+verdict.state,text:next.speech,kind:'action',action:verdict.state,stableMs:350});return;
 }
 if(presentation?.locked)remindedFor='';
 const retained=presentation?.locked?{...verdict,visible:true,stable:true}:guideObservation;
 const cue=guideWanted()?AssemblyNarration.localCue(retained):null;
 if(cue){
  const text=AssemblyText.english?AssemblyText.guideText(retained,current()).title:cue.text;
  narration.observe({...cue,text});return;
 }
 if(screen==='work'&&phase==='find'&&candidate){narration.observe({key:'found|'+candidate,text:AssemblyText.phrase('找到了，拿起这件。'),kind:'action',action:'found',stableMs:250});return}
 const text=screen==='home'?AssemblyText.phrase('五层隔板架。点下面开始装配。'):
  done.has(active)?AssemblyText.phrase('这一步完成了。'):phase==='find'?uiText('speech.find',{part:pickupName(true)}):
  phase==='review'?AssemblyText.phrase('检查接头，确认到位再继续。'):
  guideWanted()&&current().kind==='prepare'?AssemblyText.phrase('两半平放，让标签入镜。'):shortInstruction(current()).replace('A/B面','正反面');
 // Searching / lost / need_view are silent camera transitions, not fresh narration.
 // The entry cue is read once; it cannot displace or repeat a meaningful action.
 narration.observe({key:'entry',text,kind:'entry'});
}
speechButton.onclick=()=>{voiceEnabled=!voiceEnabled;if(!voiceEnabled)completionSpeech=null;narration.setEnabled(voiceEnabled);if(typeof AssemblyNative.setVoiceEnabled==='function')AssemblyNative.setVoiceEnabled(voiceEnabled);renderSpeech();if(voiceEnabled)automaticSpeech()};
window.onNativeSpeechState=event=>{
 const confirmation=completionSpeech&&narration.snapshot().active?.key===completionSpeech.key&&event.token===speechToken;
 narration.nativeState(event);
 if(confirmation&&['finished','error','unavailable','stopped'].includes(event.state))completionSpeech=null;
};
// This is grounded step information for a future assistant; nothing is sent to a service.
window.assemblyStepContext=function(){const t=current();return {
 schema:'assembly-step-context-1',assembly:flow.assembly,actionsSource:flow.sourceSha256,bindingRevision:AssemblyNative.scope().split(':').at(-1),
 task:{id:t.id,title:t.title,kind:t.kind,phase,instruction:instructionFor(t),userNote:t.instruction,
  checks:checksFor(t),checksSource:t.authoredAccept?'user-authored':'suggested',
  members:[...t.members],stationary:[...t.stationary],illustrationWorkPose:{...t.workPose}},
 parts:t.context.map(id=>({id,markers:[...(registeredParts[id]||[])],pickupConfirmed:picked.has(id)})),
 prerequisites:t.requires.map(id=>({id,title:tasks.get(id).title,userConfirmed:done.has(id)})),
 userConfirmed:done.has(active),observation:candidate&&observed?{part:candidate,marker:lastMarker}:null,
 photo:null,markerPoseCalibrated:false,automaticAssemblyVerification:false
}};
const desktopRender=render,desktopPrimary=$('primary').onclick,desktopSecondary=$('secondary').onclick;
const baseClose=closeOverlay,baseShow=showOverlay,baseModel=model;
function hideCompletionNotice(){
 clearTimeout(completionNoticeTimer);
 if($('completionNotice'))$('completionNotice').hidden=true;
 phone.classList.remove('just-confirmed');
}
window.onAssemblyConfirmed=(completedId)=>{
 phone.classList.remove('just-confirmed');void phone.offsetWidth;phone.classList.add('just-confirmed');
 const finishedTask=tasks.get(completedId),words=allDone()?AssemblyText.phrase('整架拼好了。'):finishedTask?.kind==='prepare'?AssemblyText.phrase('拼好了。'):AssemblyText.phrase('这步好了。');
 let notice=$('completionNotice');if(!notice){notice=element('div',null,'completion-notice');notice.id='completionNotice';notice.setAttribute('role','status');phone.append(notice)}
 notice.textContent=finishedTask?.kind==='prepare'?uiText('notice.joined',{part:AssemblyText.partName(finishedTask.group)}):words;
 notice.hidden=false;
 clearTimeout(completionNoticeTimer);completionNoticeTimer=setTimeout(hideCompletionNotice,1400);
 // One semantic acknowledgement, through the same speech dispatcher. No second TTS queue.
 narration.cancel(true);
 completionSpeech={context:speechContext,key:'confirmed|'+completedId,text:words+(!$('overlay').classList.contains('open')&&phase==='find'?uiText('speech.next',{part:pickupName(true)}):''),kind:'entry',stableMs:0};
 automaticSpeech();
 if(typeof AssemblyNative.confirmationFeedback==='function')AssemblyNative.confirmationFeedback();
};
window.onAssemblyMilestone=(kind,nextId,proceed)=>{
 const started=flow.main.some(id=>done.has(id)),complete=kind==='complete';
 const box=showOverlay(uiText(complete?'milestone.complete.title':'milestone.prepared.title'));
 box.classList.add('milestone-page');box.dataset.milestone=kind;
 box.append(element('div',null,'milestone-mark'),element('p',uiText(complete?'milestone.complete.body':started?'milestone.prepared.continueBody':'milestone.prepared.body'),'subtitle'),
  element('p',uiText('progress.summary',{done:done.size,total:order.length}),'milestone-count'),
  button(uiText(complete?'action.backProject':started?'action.continueAssembly':'action.startAssembly'),complete&&nativeEntry!=='home'?()=>AssemblyNative.exitGuide():proceed,'primary full'));
 return true;
};
function menuGroup(box,title,afterTitle){
 const heading=element('h3',title,'group-heading');
 if(afterTitle)heading.append(element('small',afterTitle));
 const group=element('div',null,'group-rows');
 group.setAttribute('role','group');group.setAttribute('aria-label',title);
 box.append(heading,group);return group;
}
const menuPaths={
 settings:'M4 7h8m4 0h4M4 17h3m4 0h9M12 4v6M7 14v6',
 reset:'M3 10a9 9 0 1 1 2 8M3 4v6h6',
 help:'M9 9a3 3 0 1 1 5 2c-2 1-2 2-2 3m0 3h.01',
 done:'m4 12 5 5 11-11',ready:'m9 5 7 7-7 7',
 wait:'M6 10h12v10H6zM8 10V7a4 4 0 0 1 8 0v3'
};
function menuIcon(name){
 const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),path=document.createElementNS(ns,'path');
 svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');
 path.setAttribute('d',menuPaths[name]);svg.append(path);return svg;
}
function menuAction(group,title,run,icon,kind=''){
 const row=button('',run,'menu-action '+kind);
 if(icon)row.append(menuIcon(icon));
 row.append(element('span',title),menuIcon('ready'));group.append(row);
 return row;
}
function showProgress(){
 stopStepSpeech();
 const box=showOverlay(uiText('work.progress'));box.classList.add('progress-page');
 box.append(element('p',uiText('progress.summary',{done:done.size,total:order.length}),'subtitle'));
 const tools=menuGroup(box,uiText('menu.controls'));
 menuAction(tools,AssemblyText.phrase('项目设置'),showSettings,'settings');
 if(screen==='work')menuAction(tools,AssemblyText.phrase('这一步怎么做'),help,'help');
 menuAction(tools,AssemblyText.phrase('重置本次装配'),resetProgress,'reset','restart-row');
 for(const [key,ids] of [['menu.prepare',flow.preparations],['menu.install',flow.main]]){
  const list=menuGroup(box,uiText(key),ids.filter(id=>done.has(id)).length+' / '+ids.length);
  for(const id of ids){
   const task=tasks.get(id),status=done.has(id)?'done':ready(id)?'ready':'wait';
   const row=button('',()=>openTask(id),'step-row');
   row.dataset.task=id;row.dataset.status=status;
   const shortName=id===flow.main[0]?uiText('menu.base'):AssemblyText.partName(task.group);
   row.append(element('span',String(order.indexOf(id)+1).padStart(2,'0'),'step-number'),
      element('span',shortName,'step-name'));
   if(status==='ready')row.append(element('small',uiText('menu.next')));
   row.append(menuIcon(status));row.setAttribute('aria-label',taskName(task)+' · '+uiText('progress.'+status));
   list.append(row);
  }
 }
}
let liveRequest=null,liveState='searching',liveMessage='',appActive=true,previewing=false,readyUI=false,jointOpening=false,aiOpening=false;
let guideEnabled=true,guideObservation=null,guideLastAt=0;
let reviewReturnVisit=null;
const guidePresentation=createGuidePresentation();
let presentationExpiry=0,viewportSent='';
let rowPlayback=null,rowSeen='',suppressRowTask=null,rowThumbnailRequest=0;
const rowButton=document.createElement('button');
rowButton.id='rowLessonControl';rowButton.hidden=true;
const rowThumb=document.createElement('img'),rowButtonText=document.createElement('span');
rowThumb.alt='';rowButton.append(rowThumb,rowButtonText);$('visual').append(rowButton);
window.suppressNextRowTeaching=task=>{suppressRowTask=task;};
function resetRowPlayback(){
 if(rowPlayback){clearTimeout(rowPlayback.timer);RowLesson.stop();}
 rowPlayback=null;$('visual').classList.remove('row-teaching');
 rowThumb.removeAttribute('src');rowButton.hidden=true;rowButton.classList.remove('teaching');
}
function openRowPlayback(info){
 if(rowPlayback||!info||!RowLesson.start(current(),info.observedRow,info.targetRow))return;
 const request=++rowThumbnailRequest;
 const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
 rowPlayback={at:performance.now(),request,token:liveRequest.token,
  timer:reduced?0:setTimeout(()=>{if(rowPlayback?.request===request){resetRowPlayback();render();}},RowLesson.duration)};
 if(reduced)RowLesson.draw(0,true);
 $('visual').classList.add('row-teaching');
 rowButton.classList.add('teaching');rowButton.hidden=false;
 rowButtonText.textContent=AssemblyText.phrase('回到实拍');rowButton.setAttribute('aria-label',AssemblyText.phrase('结束移排示范，回到实拍'));
 AssemblyNative.rowLessonPreview?.(liveRequest.task,liveRequest.token,request);
}
window.onRowLessonPreview=data=>{
 if(rowPlayback&&data&&rowPlayback.request===data.request&&rowPlayback.token===data.token&&
    typeof data.image==='string'&&data.image.startsWith('data:image/jpeg;base64,'))rowThumb.src=data.image;
};
function updateRowPlayback(event){
 const credible=event?.visible&&event.stable;
 if(credible && ['install_row_ok','install_oriented'].includes(event.state)){
  rowSeen='';suppressRowTask=null;
 }
 if(suppressRowTask!==null&&suppressRowTask!==active)suppressRowTask=null;
 const valid=credible && event.state==='install_wrong_row' && Number.isInteger(event.observedRow) &&
   Number.isInteger(event.targetRow) && event.observedRow>=1 && event.observedRow<=5 &&
   event.observedRow!==event.targetRow && current().id==='install:层'+event.targetRow+'板';
 const key=valid?[liveRequest?.token,event.observedRow,event.targetRow].join('|'):'';
 const differentAction=credible && (['install_row_ok','install_turn','install_oriented','install_stand','install_base_flip','install_wrong_part'].includes(event.state) ||
    event.state==='install_notch_hint'&&event.installMotion==='turn_panel_upright');
 if(rowPlayback && (differentAction || key && key!==rowSeen))resetRowPlayback();
 if(key && key!==rowSeen){
  rowSeen=key;
  if(suppressRowTask===active)suppressRowTask=null;
  else if(!matchMedia('(prefers-reduced-motion: reduce)').matches)openRowPlayback(event);
 }
 if(rowPlayback)return;
 rowButton.hidden=!valid;
 if(valid){rowButtonText.textContent=AssemblyText.phrase('看移排示范');rowButton.setAttribute('aria-label',AssemblyText.phrase('看移排示范'));}
}
rowButton.onclick=()=>{
 if(rowPlayback)resetRowPlayback();
 else if(guideWanted()&&guideObservation?.state==='install_wrong_row'&&guideObservation.stable)openRowPlayback(guideObservation);
 render();
};
const cuePlayback=CuePlayback.create(1500);
let orbitFrame=0,orbitTime=0,lastTouch=-Infinity,viewportFrame=0,demoElapsed=0,demoContext='',modelContext='';
window.demoStage='idle';
const phone=document.querySelector('.phone');
function nextToken(){return active+'|'+Date.now()+'|'+Math.random().toString(16).slice(2)}
function cameraWanted(){return !aiOpening&&!jointOpening&&appActive&&screen==='work'&&phase==='find'&&!previewing&&!$('overlay').classList.contains('open')&&pickupMarkers().length>0}
function guideAvailable(){return typeof AssemblyNative.liveGuide==='function'&&screen==='work'&&['guide','review'].includes(phase)&&!done.has(active)&&ready(active)}
function guideWanted(){return guideAvailable()&&guideEnabled&&appActive&&!aiOpening&&!jointOpening&&!$('overlay').classList.contains('open')&&!(typeof orientationDemoActive==='function'&&orientationDemoActive())}
function clearGuidePresentation(){
 resetRowPlayback();rowSeen='';
 clearTimeout(presentationExpiry);presentationExpiry=0;clearManualConfirm();
 guidePresentation.suspend();setInstallMotion(null);
 updateInsetTitle(false);
 const visual=$('visual');visual.classList.remove('install-demo','guide-tracking','guide-searching');
 phone.classList.remove('local-direction','local-adjust');
}
function stopLive(){clearGuidePresentation();viewportSent='';cuePlayback.reset();window.renderSlotTargets?.(null);BoardCue.hide($('guideDirection'));if(liveRequest){if(liveRequest.mode==='find'){candidate=null;lastMarker=null}liveRequest=null;AssemblyNative.stopLive()}guideObservation=null;liveState='searching';observed=false}
function sendViewport(){if(viewportFrame)return;viewportFrame=requestAnimationFrame(()=>{
 viewportFrame=0;
 if(!liveRequest)return;const r=$('visual').getBoundingClientRect();
 const rect=JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height,viewportWidth:innerWidth});
 if(rect!==viewportSent && r.width>1 && r.height>1){viewportSent=rect;AssemblyNative.liveViewport(rect)}
})}
function syncLive(){const guiding=guideWanted();if(!cameraWanted()&&!guiding){stopLive();return}
 const part=guiding?null:targetId(),mode=guiding?'guide':'find';
 if(!liveRequest||liveRequest.mode!==mode||liveRequest.task!==active||liveRequest.part!==part){
  stopLive();liveRequest={part,task:active,token:nextToken(),mode};liveState='searching';liveMessage='';
  if(guiding)AssemblyNative.liveGuide(active,liveRequest.token);
  else if(groupedPickup())AssemblyNative.liveTargetGroup(part,active,liveRequest.token);
  else AssemblyNative.liveTarget(part,liveRequest.token);
 }sendViewport();
}
function toggleGuide(){stopStepSpeech();reviewReturnVisit=null;guideEnabled=!guideEnabled;guideObservation=null;stopLive();demoContext='';render()}
$('guideViewToggle').onclick=toggleGuide;
function setCameraFraming(label){
 $('cameraFraming').innerHTML='<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h4l2-3h4l2 3h4v13H4Z"/><circle cx="12" cy="13" r="4"/></svg>';
 $('cameraFraming').dataset.lens=label;$('cameraFraming').title=AssemblyText.phrase(label);$('cameraFraming').setAttribute('aria-label',uiText('camera.lens',{lens:AssemblyText.phrase(label)}));
}
setCameraFraming(typeof AssemblyNative.framingLabel==='function'?AssemblyNative.framingLabel():'主摄');
$('cameraFraming').onclick=()=>{if(!liveRequest)return;stopLive();render();AssemblyNative.switchLens()};
window.onNativeFraming=event=>{if(['主摄','广角·近','广角'].includes(event?.label))setCameraFraming(event.label)};
function markHeading(mark){
 const h=$('headline');
 if(!mark){delete h.dataset.mark;h.removeAttribute('aria-label');return}
 h.dataset.mark=mark;
 const meaning={ok:AssemblyText.phrase('已核对'),adjust:AssemblyText.phrase('需要调整'),check:AssemblyText.phrase('待核对')};
 h.setAttribute('aria-label',h.textContent+'，'+meaning[mark]);
}
function updateInsetTitle(visible,instruction){
 const title=$('insetActionTitle');
 if(!visible){
  if(!title.hidden){title.hidden=true;cv.removeAttribute('aria-labelledby')}
  return;
 }
 let action=instructionKey(current());
 if(installCorrection){
  switch(installCorrection.state){
   case 'install_base_flip':action='flip';break;
   case 'install_base_heading':action=Math.abs(installCorrection.turn[1])>=Math.PI-.08?'turn':'aim';break;
   case 'install_stand':action='stand';break;
   case 'install_turn':
    action=({turn_over_end:'flipEnds',turn_back_ends:'flipSides'})[instruction?.installMotion]||'turn';break;
   case 'install_notch_hint':action='turn';break;
  }
 }
 const label=uiText('inset.'+action);
 if(title.textContent!==label)title.textContent=label;
 if(title.hidden){title.hidden=false;cv.setAttribute('aria-labelledby',title.id)}
}
function renderGuide(){
 const watching=guideWanted();
 $('guideViewToggle').hidden=true;
 $('guideViewToggle').setAttribute('aria-pressed',String(watching));
 $('cameraFraming').hidden=!(guideWanted()||cameraWanted());
 const event=watching?guideObservation:null;
 window.renderSlotTargets?.(event);
 const now=performance.now(),install=current().kind==='install';
 const state=guidePresentation.read(now,install);
 clearTimeout(presentationExpiry);
 if(watching){
  const deadline=guidePresentation.nextChange(now,install),scope=liveRequest?.token;
  if(Number.isFinite(deadline))presentationExpiry=setTimeout(()=>{
   if(liveRequest?.token===scope && guideWanted())render();
  },Math.max(1,Math.ceil(deadline-now)));
 }
 const awaitingFreshReview=reviewReturnVisit===taskVisit&&!['permission','unavailable'].includes(event?.state);
 const showCamera=watching&&(state.live||awaitingFreshReview);
 const installDemo=showCamera&&current().kind==='install';
 $('visual').classList.toggle('install-demo',installDemo);
 if(installDemo)updateRowPlayback(event);else resetRowPlayback();
 setInstallMotion(installDemo?state.instruction:null);
 updateInsetTitle(installDemo&&!rowPlayback,state.instruction);
 $('visual').classList.toggle('guide-searching',watching&&!showCamera);
 $('visual').classList.toggle('guide-tracking',showCamera);
 phone.classList.toggle('local-adjust',watching&&state.tone==='adjust');
 phone.classList.toggle('local-direction',watching&&state.tone==='direction');
 $('guidePermission').hidden=!watching||!['permission','unavailable'].includes(event?.state);
 $('guidePermission').textContent=event?.state==='permission'?AssemblyText.phrase('开启相机'):AssemblyText.phrase('重试相机');
 let mark=null;
 if(watching){
  // A steady camera or a held CAD pose can survive a short dropout, while every overlay below
  // uses only fresh geometry. Text and colour follow the same instruction.
  const instruction=state.instruction;
  const actionable=instruction && instruction.title!=='正在看方向' &&
   !['lost','searching','paused'].includes(instruction.state);
  const displayed=actionable?AssemblyText.guideText(instruction,current()):null;
  $('headline').textContent=displayed?.title||taskName(current());
  const action=state.ready?readyAction({...instruction,message:displayed?.message||instruction.message}):null;
  $('workSubtitle').textContent=action?.hint||(displayed?displayed.message:shortInstruction(current()));
  if(awaitingFreshReview&&!actionable)$('workSubtitle').textContent=AssemblyText.phrase('对准这一步的零件，重新看一下现在的摆放。');
  const pendingCheck=state.manual&&manualConfirmUntil>now;
  if(!state.manual&&manualConfirmUntil)clearManualConfirm();
  if(pendingCheck)$('workSubtitle').textContent=AssemblyText.phrase('看一下刚才提示的位置，确认已经调整好后再点一次。');
  $('primary').disabled=state.locked;
  $('primary').textContent=state.locked?AssemblyText.phrase('调整后确认'):pendingCheck?AssemblyText.phrase('已核对，再点继续'):state.manual?
   (instruction?.state==='install_base_heading'?AssemblyText.phrase('按当前摆法继续'):AssemblyText.phrase('我来核对')):action?.button||uiText('work.confirm');
  mark=state.locked?'adjust':state.ready||!state.manual&&instruction?.state==='install_notch_hint'&&!instruction.installMotion?'ok':
   state.manual||actionable&&instruction.state!=='identity_only'?'check':null;
 }else if(screen==='work'&&phase==='find'){
  mark=candidate?'ok':phone.classList.contains('problem')?'check':null;
 }
 markHeading(mark);
 const rect=$('visual').getBoundingClientRect();
 const localEvent=watching?{...event,source:'local'}:null;
 let cue=BoardCue.command(localEvent,active,current()?.context||[],now,guideLastAt);
 const geometry=cue&&BoardCue.geometry(cue,rect.width,rect.height);
 if(geometry&&cue.kind==='rotate'){
  localEvent.angle=cuePlayback.turnAngle({scope:liveRequest?.token,part:cue.part,face:cue.projection.face,
   angle:cue.angle,now,observedAt:guideLastAt});
  cue=BoardCue.command(localEvent,active,current()?.context||[],now,guideLastAt);
 }
 const motionStartedAt=cuePlayback.observe({scope:liveRequest?.token,now,observedAt:guideLastAt,
  key:geometry?cue.key+'|'+(cue.projection.face||''):null,
  clear:!watching||event?.state==='direction_ok'&&cue?.kind!=='translate'||['wrong_part','permission','unavailable','identity_only'].includes(event?.state)});
 // Invalid frames still withdraw the overlay immediately. A fresh same-action projection
 // resumes its clock; it never reuses coordinates from the missing frame.
 BoardCue.render($('guideDirection'),localEvent,
  {task:active,parts:current()?.context||[],now,observedAt:guideLastAt,motionStartedAt,
   width:rect.width,height:rect.height,scene:SHELF_SCENE.parts});
}
$('guidePermission').onclick=()=>{if(guideObservation?.state==='permission')AssemblyNative.cameraPermission();else{stopLive();render()}};
window.onNativeGuidanceViewportChanged=function(event){
 if(!liveRequest||liveRequest.mode!=='guide'||event?.token!==liveRequest.token)return;
 cuePlayback.reset();guidePresentation.viewportChanged();clearTimeout(presentationExpiry);guideObservation=null;guideLastAt=0;BoardCue.hide($('guideDirection'));window.renderSlotTargets?.(null);
 // Do not render or change speech here; the next mapped frame supplies new spatial coordinates.
};
window.onNativeGuidance=function(event){const r=liveRequest;
 if(!r||r.mode!=='guide'||!appActive||event.token!==r.token||event.task!==r.task||active!==r.task||!guideWanted())return;
 const installState=current().kind==='install'&&AssemblyNarration.installStates.has(event.state);
 if(!installState&&!['searching','need_view','direction_ok','rotate','flip','wrong_part','face_check','identity_only','permission','unavailable','lost'].includes(event.state))return;
 const previous=guideObservation;
 guideObservation={...event,title:typeof event.title==='string'?event.title.slice(0,48):'',message:typeof event.message==='string'?event.message.slice(0,160):'',visible:event.visible===true};guideLastAt=performance.now();
 guidePresentation.observe(guideObservation,guideLastAt);
 if(event.visible===true)reviewReturnVisit=null;
 if(previous && !!previous.placement===!!guideObservation.placement && ['state','visible','stable','part','installMotion','title','message'].every(key=>previous[key]===guideObservation[key])){
  renderGuide();updateOrbit();automaticSpeech();
 }else render();
};
// Native loss events retract immediately; this is a second guard for a stalled stream.
setInterval(()=>{
 if(!guideWanted()||!liveRequest)return;
 const now=performance.now();
 if(guideObservation?.visible && now-guideLastAt>1100){
  guideObservation={state:'lost',visible:false,title:current().title,message:shortInstruction(current())};
  guidePresentation.observe(guideObservation,now);render();
 }
},250);

model=function(single=false,end=false){cuePlayback.reset();if(typeof clearOrientationDemo==='function')clearOrientationDemo();reviewReturnVisit=null;guideEnabled=true;guideObservation=null;const wasPreview=previewing;previewing=screen==='work'&&phase==='find'&&!single;window.finderModel=single;window.overviewModel=false;modelContext=active+'|'+single;baseModel(single,end);demoContext='';
 if(wasPreview&&!previewing&&readyUI)queueMicrotask(render);
};
closeOverlay=function(){baseClose();$('overlay').classList.remove('milestone-page','progress-page');delete $('overlay').dataset.milestone;if(readyUI)queueMicrotask(render)};
showOverlay=function(title){const box=baseShow(title);box.scrollTop=0;stopLive();updateOrbit();return box};
function updateOrbit(){const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
 const orienting=typeof orientationDemoActive==='function'&&orientationDemoActive();
 const looping=screen==='work'&&(phase==='guide'||previewing)&&!done.has(active)&&ready(active);
 const inset=looping&&!rowPlayback&&$('visual').classList.contains('install-demo');
 const rotating=orienting||screen==='home'||looping||(screen==='work'&&phase==='find'&&!candidate&&!previewing);
 const context=[screen,active,phase,previewing,done.has(active),orienting,inset,installCorrection?.key].join('|');
 if(context!==demoContext){cancelAnimationFrame(orbitFrame);orbitFrame=0;orbitTime=0;demoContext=context;demoElapsed=0;cv.style.opacity='1';window.demoStage='idle';if(looping)amount=0}
 const enabled=appActive&&rotating&&(!$('visual').classList.contains('guide-tracking')||$('visual').classList.contains('install-demo'))&&!aiOpening&&!jointOpening&&!$('overlay').classList.contains('open')&&!reduced;
 if(!enabled){cancelAnimationFrame(orbitFrame);orbitFrame=0;orbitTime=0;if(reduced&&orienting){advanceOrientationDemo(3500);paintScene()}else if(reduced&&looping){amount=1;cv.style.opacity='1';paintScene()}return}
 if(orbitFrame)return;
 orbitTime=performance.now();
 const tick=now=>{orbitFrame=requestAnimationFrame(tick);const elapsed=now-orbitTime;if(elapsed<32)return;orbitTime=now;
  if(rowPlayback&&RowLesson.active()){RowLesson.draw(now-rowPlayback.at);return;}
  if(inset){
   // The camera inset has no drag gesture. Its clock follows real elapsed time, even on a slow frame.
   demoElapsed+=elapsed;
   const frame=AssemblyMotionCycle.sampleInset(demoElapsed);
   amount=frame.amount;cv.style.opacity=String(frame.opacity);window.demoStage=frame.stage;paintScene();return;
  }
  if(!pointer&&now-lastTouch>2200){const dt=Math.min(elapsed,100);
   if(orienting){demoElapsed+=dt;advanceOrientationDemo(demoElapsed)}
   else{if(!looping||current().kind!=='install')yaw+=dt*2*Math.PI/15000;if(looping){demoElapsed+=dt;const frame=AssemblyMotionCycle.sample(demoElapsed);amount=frame.amount;cv.style.opacity=String(frame.opacity);window.demoStage=frame.stage}}
   paintScene();}
 };orbitFrame=requestAnimationFrame(tick);
}
for(const name of ['pointerdown','pointermove','pointerup','pointercancel','wheel'])cv.addEventListener(name,()=>{lastTouch=performance.now()},{passive:true});
function shortInstruction(t){
 const missing=t.requires.find(id=>!done.has(id));if(missing)return uiText('instruction.missing',{task:taskName(tasks.get(missing))});
 if(t.kind==='install'&&AssemblyText.isLayer(t))return uiText('work.shelfInsert',{part:AssemblyText.partName(t.group)});
 return uiText('short.'+instructionKey(t));
}
render=function(){desktopRender();
 $('footer').style.display=screen==='home'?'none':'flex';
 phone.classList.toggle('work-page',screen==='work');
 const nextContext=[screen,active,phase,targetId(),done.has(active)].join('|');
 if(nextContext!==speechContext){
  speechContext=nextContext;narration.setContext(nextContext);spokenReminderStates.clear();remindedFor='';
  guidePresentation.reset();clearManualConfirm();hideCompletionNotice();
 }
 renderSpeech();
 const finding=screen==='work'&&phase==='find';
 if(!finding){candidate=null;lastMarker=null;previewing=false}
 window.finderModel=finding&&!previewing;
 syncLive();
 const ids=pickupMarkers(),problem=finding&&!candidate&&(!ids.length||['permission','unavailable'].includes(liveState));
 phone.classList.toggle('found',finding&&!!candidate);phone.classList.toggle('problem',problem);
 const edgeOnly=finding&&liveState==='edge'&&!candidate;
 phone.toggleAttribute('data-finder-expired',finding&&!candidate&&['edge','lost'].includes(liveState));
 $('visual').classList.toggle('searching',finding&&!candidate&&!edgeOnly&&!previewing);
 $('visual').classList.toggle('tracking',finding&&(!!candidate||edgeOnly)&&!previewing);
 $('camera').style.display='none';$('finder').style.display='none';$('target').style.display='none';
 $('primary').classList.toggle('search-strip',finding&&!candidate&&!problem&&!previewing);
 $('primary').disabled=finding&&!candidate&&!problem&&!previewing;
 $('primary').removeAttribute('aria-busy');
 if(screen==='home'){
  const continuing=hasSavedProgress(),next=tasks.get(resumeTask());
  const prepared=(pickups[next.id]||[]).filter(id=>next.context.includes(id)).length===next.context.length;
  $('home').style.display='block';$('visual').style.display='block';
  $('homeProgress').textContent=uiText('home.progress',{prepare:counts(flow.preparations),install:counts(flow.main)});
  $('nextText').textContent=allDone()?uiText('home.finished'):continuing?uiText('home.resume',{task:taskName(next),ready:next.kind==='prepare'&&prepared?uiText('home.ready'):''}):uiText('home.subtitle');
  $('continue').textContent=uiText(allDone()?'home.summary':continuing?'home.continue':'home.start');$('startOver').hidden=!continuing;
  window.finderModel=false;window.uiTable=false;window.overviewModel=true;
  if(modelContext!=='home'){configureOverview();modelContext='home'}
 }else if(finding){
  $('headline').textContent=candidate?uiText('work.found'):problem?(!ids.length?AssemblyText.phrase('先登记这件'):AssemblyText.phrase('相机需要你帮一下')):uiText('work.find',{part:pickupName()});
  $('workSubtitle').textContent=candidate?uiText('work.foundHint'):edgeOnly?uiText('work.centerPart'):problem?(!ids.length?AssemblyText.phrase('从菜单导入电脑上标注好的教程工程。'):(AssemblyText.english?'':liveMessage)||AssemblyText.phrase('开启相机后，就能继续寻找。')):groupedPickup()?AssemblyText.phrase('扫拼好组件上的任意一枚标签。'):uiText('work.findHint');
  $('primary').textContent=candidate?AssemblyText.phrase('我已拿到这件'):previewing?AssemblyText.phrase('回去找零件'):problem?(!ids.length?AssemblyText.phrase('导入贴码登记'):liveState==='permission'?AssemblyText.phrase('开启相机'):AssemblyText.phrase('重试相机')):AssemblyText.phrase('正在寻找');
  if(!candidate&&!problem&&!previewing)$('primary').setAttribute('aria-busy','true');
 }else{
  $('headline').textContent=phase==='guide'?taskName(current()):done.has(active)?AssemblyText.phrase('这一步完成了'):AssemblyText.phrase('接口都到位了吗？');
  $('workSubtitle').textContent=phase==='guide'?shortInstruction(current()):done.has(active)?AssemblyText.phrase('进度已经保存，可以继续下一步。'):AssemblyText.phrase('对照模型，检查接缝与每个接口。');
  $('primary').textContent=uiText(done.has(active)?'work.next':!ready(active)?'work.prepareFirst':'work.confirm');
 }
 const aiHere=screen==='work'&&['guide','review'].includes(phase);
 $('aiAssist').hidden=!aiHere;$('aiAssist').disabled=aiOpening||jointOpening;
 window.sceneSurface='#f8f7f1';
 renderGuide();
 $('homeSummary').hidden=screen!=='home';
 const groups=pickupTargets();
 AssemblyWorkControls.update({screen,phase,taskId:active,done:done.size,total:order.length,picked:groups.filter(group=>group.every(id=>picked.has(id))).length,required:groups.length});
 updateOrbit();paintScene();automaticSpeech();
};
window.onNativeLive=function(event){const r=liveRequest;if(!r||r.mode!=='find'||!appActive||event.token!==r.token||event.part!==r.part||active!==r.task||phase!=='find'||targetId()!==r.part)return;
 if(event.visible){if(event.state!=='tracking'||!pickupMarkers().includes(event.marker))return;
  if(!candidate)hideCompletionNotice();candidate=r.part;lastMarker=event.marker;observed=true;}
 else {
  // Native finder has already allowed a short occlusion before emitting lost/edge.
  candidate=null;lastMarker=null;observed=false;
 }
 const changed=liveState!==event.state||liveMessage!==(event.message||'')||phone.classList.contains('found')!==!!candidate||$('workSubtitle').dataset.visible!==String(observed);
 liveState=event.state;liveMessage=event.message||'';$('workSubtitle').dataset.visible=String(observed);
 if(changed)render();
};
window.onNativePause=function(){appActive=false;stopStepSpeech();stop();save();stopLive();candidate=null;observed=false;lastMarker=null;render()};
window.onNativeResume=function(returned){
 jointOpening=false;aiOpening=false;$('visual').classList.remove('ai-opening');appActive=true;
 if(typeof AssemblyNative.voiceEnabled==='function')voiceEnabled=AssemblyNative.voiceEnabled();
 if(returned?.task===active && guideAvailable()){
  guideEnabled=true;reviewReturnVisit=taskVisit;
  window.suppressNextRowTeaching?.(active);
  if(orientationDemoActive())clearOrientationDemo();
  if(returned.needsCheck===true)guidePresentation.photoCorrection();
 }
 render();
};
// A recognized entity is retained through brief occlusion, but never persisted as a pickup.
function allowConfirm(){
 if(!guideWanted())return true;
 const now=performance.now(),state=guidePresentation.read(now,current().kind==='install');
 if(state.locked)return false;
 if(!state.manual)return true;
 if(manualConfirmUntil>now){
  if(manualConfirmUntil-now>5100)return false;
  clearManualConfirm();return true;
 }
 manualConfirmUntil=now+5500;
 manualConfirmTimer=setTimeout(()=>{clearManualConfirm();if(guideWanted())render()},5501);
 render();return false;
}
function directConfirm(ticket=completionTicket()){if(!allowConfirm())return;stopStepSpeech();return finish(ticket)}
function takeCheck(){directConfirm()}
function replay(){closeOverlay();model(false);render()}
function jointPhoto(mode){
 if(jointOpening||aiOpening||!appActive||screen!=='work'||!['guide','review'].includes(phase)||!['locate','register'].includes(mode))return;
 stopStepSpeech();const taskId=active,t=current(),parts=[...new Set([...t.stationary,...t.context])].filter(id=>t.context.includes(id));
 if(typeof AssemblyNative.jointPhoto!=='function'){const box=showOverlay(AssemblyText.phrase('实拍暂不可用'));box.append(element('p',AssemblyText.phrase('请先使用支持实拍接头的应用版本。')),button(AssemblyText.phrase('回到本步'),closeOverlay));return}
 const launch=part=>{
  if(jointOpening||!appActive||screen!=='work'||active!==taskId||!['guide','review'].includes(phase)||!current().context.includes(part))return;
  stopStepSpeech();stopLive();stop();updateOrbit();jointOpening=true;
  try{AssemblyNative.jointPhoto(taskId,part,mode)}catch{jointOpening=false;const box=showOverlay(AssemblyText.phrase('这次没有打开相机'));box.append(element('p',AssemblyText.phrase('装配进度都还在，可以稍后再试。')),button(AssemblyText.phrase('回到本步'),closeOverlay))}
 };
 // A reference belongs to one physical entity, never to the whole multi-plane assembly.
 const fixed=[...new Set(t.stationary)].filter(id=>t.context.includes(id));
 if(fixed.length===1){launch(fixed[0]);return}
 if(parts.length===1){launch(parts[0]);return}
 const box=showOverlay(mode==='register'?AssemblyText.phrase('登记哪件的接头？'):AssemblyText.phrase('看哪件的接头？'));
 box.append(element('p',AssemblyText.phrase('选择一件，接下来只拍它的标签和接头。')));
 parts.forEach(id=>box.append(button(label(id),()=>launch(id))));
}
function aiPhoto(){
 if(aiOpening||jointOpening||!appActive||screen!=='work'||!['guide','review'].includes(phase))return;
 if(typeof AssemblyNative.aiReview!=='function'){const box=showOverlay(AssemblyText.phrase('AI 拍照暂不可用'));box.append(element('p',AssemblyText.phrase('请使用支持 AI 看图的应用版本。')),button(AssemblyText.phrase('回到本步'),closeOverlay));return}
 stopStepSpeech();stopLive();stop();if(typeof orientationDemoActive==='function'&&orientationDemoActive())model(false,phase==='review');aiOpening=true;$('visual').classList.add('ai-opening');updateOrbit();
 try{AssemblyNative.aiReview(active)}catch{aiOpening=false;$('visual').classList.remove('ai-opening');const box=showOverlay(AssemblyText.phrase('这次没有打开 AI 拍照'));box.append(element('p',AssemblyText.phrase('装配进度都还在，可以稍后再试。')),button(AssemblyText.phrase('回到本步'),closeOverlay))}
}
$('aiAssist').onclick=aiPhoto;
function help(){stopStepSpeech();const box=showOverlay(AssemblyText.phrase('这一步怎么做'));box.append(element('p',instructionNow()),
 button(AssemblyText.phrase('接口对不上怎么办？'),()=>{box.append(element('p',stuckAdvice(current()),'hint'))}),
 ...(screen==='work'&&ready(active)&&!done.has(active)&&phase!=='find'?[button(groupedPickup()?AssemblyText.phrase('重新找这个组件'):AssemblyText.phrase('重新找齐这两件'),restartCurrentPickup)]:[]),
 ...(screen==='work'&&['guide','review'].includes(phase)?[button(AssemblyText.phrase('拍照请 AI 帮忙'),aiPhoto,'primary full'),button(AssemblyText.phrase('在实物上看接头'),()=>jointPhoto('locate'))]:[]),
 button(AssemblyText.phrase('回到本步'),closeOverlay));}
function restartCurrentPickup(){if(screen!=='work'||!ready(active)||done.has(active)||!pickupTargets().length)return;const taskId=active;
 const grouped=groupedPickup(),words=grouped?AssemblyText.phrase('重新找这个组件'):AssemblyText.phrase('重新找这两件');
 const box=showOverlay(grouped?AssemblyText.phrase('重新找这个组件？'):AssemblyText.phrase('重新找齐这两件？'));box.append(element('p',AssemblyText.phrase('只重新确认本步的拿取记录。')),button(AssemblyText.phrase('保留当前状态'),closeOverlay),button(words,()=>{
  if(active!==taskId||done.has(active))return;
  const previous=picked,historyBefore=actionHistory;actionHistory=[];picked=new Set();if(!save()){actionHistory=historyBefore;picked=previous;pickups[active]=[...picked];closeOverlay();render();return}
  stopStepSpeech();stopLive();stop();candidate=null;lastMarker=null;observed=false;previewing=false;openTask(active,true,false);
 },'primary full'));
}
function resetProgress(){const box=showOverlay(AssemblyText.phrase('重新开始这次装配？'));box.append(element('p',AssemblyText.phrase('清除这次的拿取和完成记录，从第一步找零件开始。已登记的标签、教程和模型都会保留。')),
 button(AssemblyText.phrase('保留进度'),closeOverlay),button(AssemblyText.phrase('清除进度，重新开始'),()=>{stopStepSpeech();stopLive();stop();done.clear();picked.clear();pickups={};actionHistory=[];active=order[0];returnStack=[];candidate=null;lastMarker=null;observed=false;previewing=false;phase='find';openTask(order[0])},'full'))}
$('startOver').onclick=resetProgress;
function showSettings(){
 const box=showOverlay(AssemblyText.phrase('项目设置'));box.classList.add('progress-page');
 const project=menuGroup(box,uiText('menu.project'));
 menuAction(project,AssemblyText.phrase('提示颜色'),feedbackPicker);
 if(typeof AssemblyNative.aiSettings==='function')menuAction(project,AssemblyText.phrase('AI 联网设置'),()=>AssemblyNative.aiSettings());
 menuAction(project,AssemblyText.phrase('导入贴码登记'),()=>AssemblyNative.importRegistration());
 if(screen!=='work')return;
 const step=menuGroup(box,uiText('menu.current'));
 if(['guide','review'].includes(phase))menuAction(step,AssemblyText.phrase('登记本步接头'),()=>jointPhoto('register'));
 if(phase==='find'){
  menuAction(step,AssemblyText.phrase('切换远近镜头'),()=>{closeOverlay();candidate=null;observed=false;lastMarker=null;render();AssemblyNative.switchLens()});
  menuAction(step,AssemblyText.phrase('查看当前件编号'),()=>{
   const detail=showOverlay(label(targetId()));
   detail.append(element('p',(registeredParts[targetId()]||[]).map(n=>String(n).padStart(3,'0')).join(' · ')),
    element('p',AssemblyText.phrase('看到任意一枚，都表示同一个零件。')));
  });
  if(candidate)menuAction(step,AssemblyText.phrase('重新找这件'),()=>{candidate=null;lastMarker=null;observed=false;closeOverlay();render()});
 }
 if(done.has(active))menuAction(step,AssemblyText.phrase('撤销本步完成'),desktopSecondary);
 if(!step.childElementCount){step.previousElementSibling.remove();step.remove()}
}
$('helpButton').onclick=help;$('progressButton').onclick=showProgress;
$('homeButton').onclick=()=>{stopStepSpeech();stopLive();if(screen==='home'||nativeEntry!=='home')AssemblyNative.exitGuide();else home()};
window.assemblyBackTicket=()=>appActive&&!aiOpening&&!jointOpening&&previousAction()?completionTicket():null;
window.assemblySwipeBack=ticket=>{
 if(!appActive||aiOpening||jointOpening||!ticketCurrent(ticket))return;
 stopStepSpeech();stopLive();lastMarker=null;previewing=false;
 clearTimeout(completionNoticeTimer);const notice=$('completionNotice');if(notice)notice.hidden=true;
 phone.classList.remove('just-confirmed');
 if(!rewindAction(ticket))render();
};
let activationTicket=null;
$('primary').addEventListener('pointerdown',()=>{activationTicket=completionTicket()});
$('primary').addEventListener('pointercancel',()=>{activationTicket=null});
let activationKey=null;
$('primary').addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){
 if(e.repeat||activationKey){e.preventDefault();return}
 activationKey=e.key;activationTicket=completionTicket();
}});
$('primary').addEventListener('keyup',()=>{activationKey=null});
$('primary').addEventListener('blur',()=>{activationKey=null;activationTicket=null});
$('primary').onclick=event=>{const ticket=activationTicket||completionTicket();activationTicket=null;
 if(event.detail>1||!ticketCurrent(ticket))return;
 stopStepSpeech();if(phase==='find'&&previewing){stop();model(true);render();return}
 if(!allowConfirm())return;
 if(phase==='find'&&!candidate){if(!pickupMarkers().length)AssemblyNative.importRegistration();else if(liveState==='permission')AssemblyNative.cameraPermission();else{stopLive();render()}return}
 if(typeof orientationDemoActive==='function'&&orientationDemoActive()){model(false,phase==='review');render();return}
 handlePrimary(event,ticket);
};
$('secondary').onclick=()=>phase==='find'?help():desktopSecondary();
window.addEventListener('pagehide',()=>{appActive=false;stopStepSpeech();stopLive();stop();save();updateOrbit()});
window.addEventListener('resize',sendViewport);new ResizeObserver(sendViewport).observe($('visual'));
// Navigation is persistent. Keep legacy callers harmless rather than swallowing the first tap.
function hideNavigation(){}
function revealNavigation(){}
readyUI=true;render();publishProgressSummary();
if(nativeEntry==='progress')showProgress();
else if(nativeEntry==='work'){if(allDone())showProgress();else openTask(resumeTask(),true)}

delete document.documentElement.dataset.starting;
