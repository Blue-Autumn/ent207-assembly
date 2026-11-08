'use strict';
const $=id=>document.getElementById(id),flow=WORKFLOW,tasks=new Map(flow.tasks.map(t=>[t.id,t]));
const storageNotice=document.createElement('div');storageNotice.id='storageNotice';storageNotice.className='prototype';storageNotice.hidden=true;document.querySelector('.phone .top').after(storageNotice);
const order=[...flow.preparations,...flow.main],key='ent207.guide.v2:'+demoProfile;
let done=new Set(),picked=new Set(),pickups={},active=order[0],screen='home',phase='find',candidate=null,observed=false,cameraExpanded=false;
let playing=false,anim=0,generation=0,checkStatus=null,speed=1,returnStack=[],persistenceError='';
let taskVisit=0,completionBlockedUntil=0,actionHistory=[];
function flowText(name,args,fallback){const value=window.AssemblyText?.t(name,args);return value&&value!==name?value:fallback}
function taskName(t){return window.AssemblyText?.taskName(t)||t.title}
function layerTask(t){return window.AssemblyText?.isLayer(t)??new Set(['层1板','层2板','层3板','层4板','层5板']).has(t.group)}
function allDone(ids=order){return ids.length>0&&ids.every(id=>done.has(id))}
// On the phone a finished component is picked up as one object, from either half's tag.
function groupedPickup(t=current()){
 return t.kind==='install';
}
function pickupTargets(t=current()){
 if(groupedPickup(t))return [t.members];
 return t.kind==='prepare'?t.context.map(id=>[id]):[];
}
function pendingPickup(t=current()){return pickupTargets(t).find(group=>!group.every(id=>picked.has(id)))||[]}
function pickupComplete(t=current()){return !pendingPickup(t).length}
function completionTicket(){return {taskId:active,visit:taskVisit,owner:key}}
function ticketCurrent(ticket){return ticket&&ticket.taskId===active&&ticket.visit===taskVisit&&ticket.owner===key}
let savedState=null,storageUsable=true;
function canWriteProgress(){
 if(!storageUsable)return true;
 try{
  if(localStorage.getItem(key)===savedState)return true;
  location.reload();
 }catch{storageUsable=false;return true}
 return false;
}
window.addEventListener('storage',event=>{
 if(event.key===key || event.key===null)location.reload();
});
function progressSnapshot(){return {storageKey:key,done:order.filter(id=>done.has(id)),active,total:order.length,preparations:{done:flow.preparations.filter(id=>done.has(id)).length,total:flow.preparations.length},assembly:{done:flow.main.filter(id=>done.has(id)).length,total:flow.main.length}}}
function publishProgressSummary(){if(parent!==window)parent.postMessage({type:'ent207-progress',done:done.size,total:order.length},location.origin)}
try{savedState=localStorage.getItem(key);const saved=JSON.parse(savedState||'null');if(saved?.kind==='WEB_REHEARSAL'){
 done=new Set((Array.isArray(saved.done)?saved.done:[]).filter(id=>tasks.has(id)));if(tasks.has(saved.active))active=saved.active;
 for(const [id,ids] of Object.entries(saved.pickups||{}))if(tasks.has(id)&&Array.isArray(ids))pickups[id]=[...new Set(ids.filter(part=>tasks.get(id).context.includes(part)))];
 const restored=pickups[active]||(Array.isArray(saved.picked)?saved.picked:[]);picked=new Set(restored.filter(id=>tasks.get(active).context.includes(id)));
 actionHistory=(Array.isArray(saved.actionHistory)?saved.actionHistory:[]).slice(-64).filter(record=>
  record && ['pickup','complete','visit'].includes(record.kind) && tasks.has(record.task) && tasks.has(record.destination) &&
  Array.isArray(record.parts) && record.parts.every(id=>tasks.get(record.task).context.includes(id)) &&
  Array.isArray(record.stack) && record.stack.every(id=>tasks.has(id)));
 returnStack=(Array.isArray(saved.returnStack)?saved.returnStack:[]).filter(id=>tasks.has(id));}}
catch{}
function ready(id){return !!tasks.get(id)&&tasks.get(id).requires.every(dep=>done.has(dep))}
function prune(){let repeat=true;while(repeat){repeat=false;for(const id of done)if(!ready(id)){done.delete(id);repeat=true}}}prune();
function save(){pickups[active]=[...picked];try{
 if(!canWriteProgress())throw Error('stale owner');
 const nextState=JSON.stringify({kind:'WEB_REHEARSAL',total:order.length,done:[...done],active,pickups,returnStack,actionHistory});
 if(storageUsable){
  try{localStorage.setItem(key,nextState)}catch{storageUsable=false}
 }
 savedState=nextState;
 persistenceError=storageUsable?'':AssemblyText.english?'Storage unavailable. Keep this page open to continue.':'浏览器无法存储进度，请保持本页打开。';
 publishProgressSummary();return true
 }catch{persistenceError=flowText('progress.saveFailed',{},'进度没能保存，请留在本步重试。');return false}
 finally{$('storageNotice').textContent=persistenceError;$('storageNotice').hidden=!persistenceError}}
function nextTask(){return order.find(id=>!done.has(id))||order.at(-1)}
function hasSavedProgress(){return done.size>0||picked.size>0||active!==order[0]||Object.values(pickups).some(ids=>Array.isArray(ids)&&ids.length>0)}
function resumeTask(){let id=!done.has(active)?active:([...returnStack].reverse().find(task=>!done.has(task))||nextTask());const visited=new Set();
 while(!ready(id)&&!visited.has(id)){visited.add(id);id=tasks.get(id).requires.find(dep=>!done.has(dep))||id}return id}
function current(){return tasks.get(active)}
function targetId(){return pendingPickup()[0]}
function label(id){if(window.AssemblyText?.partName)return AssemblyText.partName(id);return (id||'').replace('层1板','紫层半片').replace('层2板','蓝层半片').replace('层3板','肉色半片').replace('层4板','黄层半片').replace('层5板','绿层半片')}
function message(s){$('feedback').textContent=s}
function stop(){playing=false;cancelAnimationFrame(anim);$('play').textContent='重播动作'}
function closeOverlay(){generation++;$('overlay').classList.remove('open');$('overlay').replaceChildren();}
function model(single=false,end=false){stop();const t=structuredClone(current());window.uiTable=!single;
 if(single){const group=pendingPickup(t);t.context=group.length?group:[current().members[0]];t.members=[];t.motion.dist=0}
 configureScene(t);amount=end?1:0;paintScene();}
function openTask(id,dependency=false,persist=true){if(!tasks.has(id))return;taskVisit++;stop();closeOverlay();if(!dependency)returnStack=[];pickups[active]=[...picked];active=id;picked=new Set((pickups[id]||[]).filter(p=>current().context.includes(p)));candidate=null;observed=false;checkStatus=null;cameraExpanded=false;
 screen='work';phase=done.has(id)?'review':(!ready(id)&&current().kind==='install')||pickupComplete()?'guide':'find';
 model(phase==='find',phase==='review');if(persist)save();render();}
function home(){taskVisit++;stop();closeOverlay();screen='home';render()}
function showOverlay(title){closeOverlay();const box=$('overlay');box.classList.add('open');const close=document.createElement('button');close.className='close';close.textContent=(window.AssemblyText?.phrase('关闭')??'关闭');close.onclick=closeOverlay;const h=document.createElement('h2');h.textContent=title;box.append(close,h);return box}
function element(tag,text,cls){const e=document.createElement(tag);if(text)e.textContent=text;if(cls)e.className=cls;return e}
function button(text,fn,cls='full'){const b=element('button',text,cls);b.onclick=fn;return b}
function counts(ids){return ids.filter(id=>done.has(id)).length+'/'+ids.length}
function taskRows(box){box.replaceChildren();for(const id of order){const t=tasks.get(id),b=button(taskName(t),()=>openTask(id),'');b.dataset.task=id;if(done.has(id))b.classList.add('done');b.append(element('small',done.has(id)?'已完成':ready(id)?'可开始':'待准备'));box.append(b)}}
function render(){const t=current(),homeView=screen==='home';$('home').style.display=homeView?'block':'none';$('work').style.display=homeView?'none':'block';$('visual').style.display=homeView?'none':'block';$('below').style.display=homeView?'none':'block';$('footer').style.display=homeView?'none':'block';
 if(homeView){$('homeProgress').textContent='组件准备 '+counts(flow.preparations)+' · 总装 '+counts(flow.main);$('homeBar').style.width=done.size/order.length*100+'%';const n=resumeTask();$('nextTitle').textContent=allDone()?'整架已完成':taskName(tasks.get(n));$('nextText').textContent=allDone()?'可以回看任何一步，或查看装配总结。':'准备区放拼好的组件，零件区放还没用的半件。';$('continue').textContent=allDone()?'查看完成总结':done.size||picked.size?'继续装配':'准备开始';taskRows($('taskList'));return}
 $('workContext').textContent=t.kind==='prepare'?'准备组件 · '+counts(flow.preparations):'总装 · '+counts(flow.main);
 $('headline').textContent=phase==='find'?(candidate?'找到了，拿出这一件':flowText('finder.title',{part:taskName(t)},'找齐'+taskName(t))):phase==='guide'?taskName(t):done.has(active)?'本步已完成':'装好了吗？';
 $('count').textContent=phase==='find'?'已备齐 '+picked.size+'/'+t.context.length:phase==='guide'?'按图摆放 · 看动作':done.has(active)?(persistenceError?'本次已确认':'已保存'):'核对接缝与接口';
 $('targetName').textContent=phase==='find'?label(candidate||targetId()):t.kind==='install'?'取刚才拼好的组件':'两半已经备齐';
 ['Find','Guide','Review'].forEach((name,i)=>$('step'+name).classList.toggle('active',['find','guide','review'][i]===phase));
 $('finder').style.display=phase==='find'&&!cameraExpanded&&!candidate?'block':'none';$('camera').style.display=phase==='find'&&(cameraExpanded||candidate)?'block':'none';$('target').style.display=phase==='find'&&candidate&&observed?'block':'none';
 document.querySelector('.bottom-help').style.display=phase==='guide'?'none':'block';
 $('modelTools').style.display=phase==='find'?'none':'flex';$('inlineChecks').replaceChildren();message('');
 $('guideText').textContent=phase==='find'?(candidate?'把这件放到旁边的准备区。拿到后再找下一件。':'先看清外形，再把手机对准零件区。找到后会突出显示这一件。'):
 phase==='guide'?(t.kind==='prepare'?(layerTask(t)?'两半大面平放，A/B可混搭，缺口方向按图示，接头相对，从上方接合。':'两件大面平放，按接头形状对齐。从上方接合，已粘死的小板保持整体。'):
 layerTask(t)?'拿起刚拼好的层板并立起。扶住两侧，对准平放侧框上的槽，再直插。':t.placement):
 done.has(active)?'这一步已经保存。回看不会改动进度。':'对照装好后的样子，看看接缝是否齐平、接口是否都到位。';
 if(phase==='guide')$('guideText').textContent+=' 看清后放下手机，双手操作。';
 const deps=t.requires.filter(id=>!done.has(id));if(deps.length&&phase!=='find')$('guideText').textContent='先完成「'+tasks.get(deps[0]).title+'」，再回来装这一步。';
 $('primary').disabled=false;
 $('primary').textContent=phase==='find'?(candidate?'我已拿到这件':'对准桌面寻找'):phase==='guide'?(deps.length?'先去准备':flowText('action.confirm',{},'确认完成')):done.has(active)?'继续下一任务':'拍照帮我检查';
 if(phase==='find')$('primary').disabled=!candidate;
 $('secondary').textContent=phase==='find'?(candidate?'重新找这件':cameraExpanded?'看零件外形':'展开取景'):
 phase==='guide'?'摆放不确定？看看这里':done.has(active)?'撤销本步完成':'按图核对，直接确认';
 $('simulateFind').disabled=screen!=='work'||phase!=='find';$('simulateRepeat').disabled=$('simulateFind').disabled;
}
$('finder').onclick=()=>{cameraExpanded=true;render()};
function completionDestination(){
 while(returnStack.length){const id=returnStack.pop();if(tasks.has(id)&&!done.has(id))return id}
 return nextTask();
}
function showMilestone(kind,nextId){
 const proceed=()=>{closeOverlay();if(kind==='complete')home();else openTask(nextId,true)};
 const assemblyStarted=flow.main.some(id=>done.has(id));
 if(typeof window.onAssemblyMilestone==='function'&&window.onAssemblyMilestone(kind,nextId,proceed)===true)return;
 const box=showOverlay(kind==='complete'?flowText('milestone.complete.title',{},'整架装好了'):flowText('milestone.prepared.title',{},'组件准备齐了'));
 box.dataset.milestone=kind;
 box.append(element('p',kind==='complete'?flowText('milestone.complete.body',{},'再检查整架是否稳固，所有接头是否到位。'):assemblyStarted?flowText('milestone.prepared.continueBody',{},'所有组件已备齐，回到刚才的装配步骤。'):flowText('milestone.prepared.body',{},'接下来，把准备好的组件拼成整架。')),
 button(kind==='complete'?flowText('action.backProject',{},'回到作品'):assemblyStarted?flowText('action.continueAssembly',{},'继续总装'):flowText('action.startAssembly',{},'开始总装'),proceed,'primary full'));
}
// Store only the inverse of each accepted action. Returning to an earlier task must not
// replace unrelated pickup records (e.g. a separately prepared shelf).
function rememberAction(kind,task,destination,parts,stack){
 actionHistory=[...actionHistory.slice(-63),{kind,task,destination,parts:[...parts],stack:[...stack]}];
}
function previousAction(){
 const latest=actionHistory.at(-1);
 if(latest?.destination===active)return latest;
 // Progress from older releases contains pickup order and an insertion-ordered done set.
 // Only infer an undo at the current task or its natural resume point.
 if(actionHistory.length)return null;
 const latestCompleted=[...done].at(-1);
 const hasDependent=id=>[...done].some(other=>tasks.get(other).requires.includes(id));
 if(done.has(active)){
  if(hasDependent(active))return null;
  return {kind:'complete',task:active,destination:active,parts:[],stack:returnStack};
 }
 const group=pickupTargets().filter(ids=>ids.every(id=>picked.has(id))).at(-1);
 if(group)return {kind:'pickup',task:active,destination:active,parts:group,stack:returnStack};
 if(latestCompleted && nextTask()===active && !hasDependent(latestCompleted)){
  return {kind:'complete',task:latestCompleted,destination:active,parts:[],stack:returnStack};
 }
 return null;
}
function rewindAction(ticket){
 if(!ticketCurrent(ticket)||!canWriteProgress())return false;
 if(screen!=='work'&&!$('overlay').matches('.milestone-page.open'))return false;
 const entry=previousAction();
 if(!entry)return false;
 if(entry.kind==='complete'&&(!done.has(entry.task)||[...done].some(id=>tasks.get(id).requires.includes(entry.task))))return false;
 if(entry.kind==='pickup'&&(done.has(active)||!entry.parts.every(id=>picked.has(id))))return false;
 const before={active,picked,done,returnStack,actionHistory,pickups};
 actionHistory=actionHistory.at(-1)===entry?actionHistory.slice(0,-1):[];
 pickups={...pickups,[active]:[...picked]};
 done=new Set(done);active=entry.task;returnStack=[...entry.stack];
 picked=new Set(pickups[active]||[]);
 if(entry.kind==='complete')done.delete(active);
 if(entry.kind==='pickup')for(const id of entry.parts)picked.delete(id);
 if(!save()){
  ({active,picked,done,returnStack,actionHistory,pickups}=before);
  render();return false;
 }
 completionBlockedUntil=0;
 openTask(active,true,false);
 return true;
}
function finish(ticket=completionTicket()){
 if(!ticketCurrent(ticket)||!canWriteProgress())return {status:'stale'};
 if(screen!=='work'||!['guide','review'].includes(phase))return {status:'invalid_phase'};
 if(done.has(active))return {status:'already_done'};
 if(Date.now()<completionBlockedUntil)return {status:'busy'};
 const task=current(),missing=task.requires.find(id=>!done.has(id));
 if(missing)return {status:'missing_dependency',taskId:missing};
 if(!pickupComplete(task))return {status:'missing_pickup'};
 const old={done:new Set(done),active,picked:new Set(picked),pickups:structuredClone(pickups),returnStack:[...returnStack],phase,actionHistory};
 const preparedBefore=allDone(flow.preparations),completedId=active;
 pickups[active]=[...picked];done.add(active);
 const complete=allDone(),prepared=!preparedBefore&&allDone(flow.preparations),dest=complete?active:completionDestination();
 rememberAction('complete',completedId,dest,[],old.returnStack);
 active=dest;picked=new Set((pickups[dest]||[]).filter(id=>current().context.includes(id)));
 // Persist the completion and its actual resume destination in one write.
 if(!save()){
  actionHistory=old.actionHistory;done=old.done;active=old.active;picked=old.picked;pickups=old.pickups;returnStack=old.returnStack;phase=old.phase;
  render();message(persistenceError);return {status:'save_failed'};
 }
 completionBlockedUntil=Date.now()+650;
 openTask(dest,true,false);
 if(complete)showMilestone('complete',dest);else if(prepared)showMilestone('prepared',dest);
 try{if(typeof window.onAssemblyConfirmed==='function')window.onAssemblyConfirmed(completedId)}catch{/* Feedback cannot revert a saved confirmation. */}
 return {status:'completed',taskId:completedId,nextTaskId:dest,milestone:complete?'complete':prepared?'prepared':null};
}
function directConfirm(ticket=completionTicket()){return finish(ticket?.type==='click'?completionTicket():ticket)}
function help(){const box=showOverlay('这一步怎么做');const t=current();
 box.append(element('p',t.kind==='prepare'?'先把基准半件平放，另一半从上方对准接头。不要从侧面硬推。':'先拿已经拼好的组件，按图摆正，再对准底侧框的接头。'),button('接口对不上怎么办？',()=>{box.append(element('p','先停下，抬起一点，看两端是否都对准。侧框四个半件不同，先核对拿的是哪一件。','hint'))}),button('慢放给我看',()=>{closeOverlay();const finding=phase==='find';model();render();play(2,finding);if(finding)message('先预览怎么拼，随后继续找齐两件。')},'primary full'),button('回到本步',closeOverlay));}
function play(mult=1,helpPreview=false){stop();if(phase==='find'&&!helpPreview)return;speed=mult;amount=0;const start=performance.now();playing=true;$('play').textContent='暂停';const tick=now=>{if(!playing)return;amount=Math.min(1,(now-start)/(current().motion.seconds*1000*speed));paintScene();if(amount<1)anim=requestAnimationFrame(tick);else{stop();if(helpPreview&&phase==='find')model(true)}};anim=requestAnimationFrame(tick)}
$('play').onclick=()=>playing?stop():play();$('slow').onclick=()=>play(2);$('orient').onclick=()=>{yaw=.63;pitch=.58;zoom=1;paintScene()};
function handlePrimary(event,ticket=completionTicket()){
 if(!ticketCurrent(ticket)||!canWriteProgress()||screen!=='work')return {status:'stale'};
 if(event?.detail>1)return {status:'busy'};
 const t=current();
 if(phase==='find'){
  const group=pendingPickup(t);
  if(!candidate||candidate!==group[0])return {status:'no_target'};
  const previous={picked:new Set(picked),candidate,observed,cameraExpanded,pickups:structuredClone(pickups),actionHistory};
  rememberAction('pickup',active,active,group.filter(id=>!picked.has(id)),returnStack);
  for(const id of group)picked.add(id);
  if(!save()){actionHistory=previous.actionHistory;picked=previous.picked;candidate=previous.candidate;observed=previous.observed;cameraExpanded=previous.cameraExpanded;pickups=previous.pickups;render();message(persistenceError);return {status:'save_failed'}};
  candidate=null;observed=false;cameraExpanded=false;phase=pickupComplete(t)?'guide':'find';taskVisit++;model(phase==='find');render();return {status:'picked'};
 }
 if(done.has(active)){if(allDone())summary();else openTask(completionDestination(),true);return {status:'continued'}};
 if(!['guide','review'].includes(phase))return {status:'invalid_phase'};
 if(Date.now()<completionBlockedUntil)return {status:'busy'};
 const missing=t.requires.find(id=>!done.has(id));
 if(missing){rememberAction('visit',active,missing,[],returnStack);returnStack.push(active);openTask(missing,true);return {status:'dependency',taskId:missing}};
 if(!pickupComplete(t)){phase='find';taskVisit++;model(true);render();return {status:'missing_pickup'}};
 return finish(ticket);
}
$('primary').onclick=event=>handlePrimary(event);
function undoTask(id=active){
 if(id!==active||!done.has(id)||!canWriteProgress())return false;
 const historyBefore=actionHistory;actionHistory=[];
 const before=new Set(done);done.delete(id);prune();
 if(!save()){actionHistory=historyBefore;done=before;render();message(persistenceError);return false}
 openTask(id,false,false);return true;
}
$('secondary').onclick=()=>{if(phase==='find'){candidate=null;observed=false;cameraExpanded=!cameraExpanded;render()}else if(phase==='guide')help();else if(done.has(active)){
 const box=showOverlay((window.AssemblyText?.phrase('撤销这一步？')??'撤销这一步？'));box.append(element('p',(window.AssemblyText?.phrase('依赖它的后续总装完成记录也会撤销，其他组件的准备记录保留。')??'依赖它的后续总装完成记录也会撤销，其他组件的准备记录保留。')),button((window.AssemblyText?.phrase('确认撤销')??'确认撤销'),()=>undoTask(active),'primary full'),button((window.AssemblyText?.phrase('保留记录')??'保留记录'),closeOverlay));
 }else directConfirm()};
$('homeButton').onclick=home;$('continue').onclick=()=>allDone()?summary():openTask(resumeTask(),true);
$('progressButton').onclick=()=>{const box=showOverlay('装配进度');box.append(element('p','准备 '+counts(flow.preparations)+' · 总装 '+counts(flow.main)));const list=element('div',null,'task-list');taskRows(list);box.append(list)};
$('helpButton').onclick=help;
function summary(){showMilestone('complete',active)}
home();
