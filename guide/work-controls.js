/* Presentation only: this module never changes tasks, pickup records or completion. */
(function(global){
 'use strict';
 const byId=id=>document.getElementById(id);
 const footer=byId('footer'),toggle=byId('dockToggle'),actions=byId('dockActions');
 if(!footer||!toggle||!actions)return;
 const defaults={
  'work.expandComplete':'展开完成按钮','work.collapseComplete':'收起完成按钮',
  'work.progressCount':'{done} / {total}','work.stepCount':'已完成 {done} / {total}',
  'work.pickupCount':'{picked} / {required} 件已拿到','work.findContext':'准备零件',
  'work.overview':'作品预览','work.navigation':'装配导航','work.progress':'装配进度',
  'work.ai':'拍照让 AI 看这一步','work.back':'返回项目'
 };
 function text(key,args={}){
  const translated=global.AssemblyText?.t?.(key,args);
  const template=translated&&translated!==key?translated:defaults[key]||key;
  return template.replace(/\{(\w+)\}/g,(_,name)=>String(args[name]??''));
 }
 let expanded=true,collapsible=false,currentTask=null,currentPhase=null,gesture=null,suppressUntil=0;
 const clock=()=>performance.now();
 function setExpanded(value){
  // Inert may blur immediately; remember the source before changing accessibility state.
  const focusWasInActions=actions.contains(document.activeElement),focusWasToggle=document.activeElement===toggle;
  expanded=collapsible?!!value:true;
  footer.classList.toggle('dock-collapsed',!expanded);
  toggle.setAttribute('aria-expanded',String(expanded));
  toggle.setAttribute('aria-label',text(expanded?'work.collapseComplete':'work.expandComplete'));
  toggle.setAttribute('aria-hidden',String(expanded));
  toggle.tabIndex=expanded?-1:0;
  actions.setAttribute('aria-hidden',String(!expanded));
  actions.inert=!expanded;
  const primary=byId('primary');
  if(expanded)primary.removeAttribute('tabindex');else primary.setAttribute('tabindex','-1');
  if(!expanded&&focusWasInActions)toggle.focus({preventScroll:true});
  if(expanded&&focusWasToggle)primary.focus({preventScroll:true});
 }
 function update(state={}){
  const count=v=>Math.max(0,Number.isFinite(Number(v))?Math.floor(Number(v)):0);
  const total=count(state.total),done=Math.min(count(state.done),total),required=count(state.required),picked=Math.min(count(state.picked),required);
  const finding=state.screen==='work'&&state.phase==='find';
  const changed=currentTask!==state.taskId||currentPhase!==state.phase;
  if(changed&&gesture)endGesture(true);
  currentTask=state.taskId;currentPhase=state.phase;
  collapsible=false; // Browser rehearsal always has a visible Next action.
  footer.classList.toggle('is-finding',finding);
  footer.classList.toggle('has-ai',!byId('aiAssist').hidden);
  toggle.hidden=!collapsible;
  byId('pickupSummary').hidden=!finding;
  const slots=byId('pickupSlots');
  // Only observed pickup counts are rendered; nothing here simulates a detection.
  if(slots.dataset.count!==String(required)){
   slots.replaceChildren();
   for(let i=0;i<Math.min(required,16);i++){const slot=document.createElement('i');slots.append(slot);}
   slots.dataset.count=String(required);
  }
  [...slots.children].forEach((slot,index)=>slot.classList.toggle('filled',index<picked));
  byId('pickupCount').textContent=text('work.pickupCount',{picked,required});
  byId('dockProgress').style.width=(total?done/total*100:0)+'%';
  byId('dockCount').textContent=text('work.progressCount',{done,total});
  toggle.title=text('work.stepCount',{done,total});
  byId('topContext').textContent=state.screen==='home'?text('work.overview'):finding?text('work.findContext'):'';
  document.querySelector('.top').setAttribute('aria-label',text('work.navigation'));
  byId('homeButton').setAttribute('aria-label',text('work.back'));
  byId('progressButton').setAttribute('aria-label',text('work.progress'));
  byId('aiAssist').setAttribute('aria-label',text('work.ai'));
  const visual=byId('visual');
  visual.classList.toggle('model-mode',!['searching','tracking','guide-searching','guide-tracking'].some(name=>visual.classList.contains(name)));
  setExpanded(changed?true:expanded);
 }
 toggle.addEventListener('click',()=>setExpanded(!expanded));
 footer.addEventListener('keydown',event=>{
  if(!collapsible||byId('aiAssist').contains(event.target))return;
  if((expanded&&event.key==='ArrowDown')||(!expanded&&event.key==='ArrowUp')){
   event.preventDefault();event.stopPropagation();setExpanded(!expanded);
  }
 });
 // Capture on the document so a milestone's own bottom action can use the same gesture.
 // The AI button, menus and camera keep their normal gestures.
 function backSurface(target){
  const milestone=target.closest?.('.milestone-page.open button.primary');
  if(milestone)return milestone;
  if(target===toggle||toggle.contains(target))return toggle;
  if(actions.contains(target))return actions;
  return null;
 }
 function endGesture(suppress){
  const g=gesture;gesture=null;
  if(suppress)suppressUntil=clock()+450;
  if(!g)return;
  const surface=g.surface;
  if(surface){
   surface.classList.remove('back-drag','back-armed');
   surface.style.removeProperty('--back-shift');
   try{if(surface.hasPointerCapture(g.id))surface.releasePointerCapture(g.id)}catch{}
  }
 }
 document.addEventListener('pointerdown',event=>{
  if(gesture){
   if(event.pointerId!==gesture.id)endGesture(true);
   return;
  }
  if(event.isPrimary===false||event.button>0)return;
  // A new finger press is a deliberate action even straight after an undo.
  suppressUntil=0;
  const surface=backSurface(event.target);
  const vertical=collapsible&&footer.contains(event.target)&&!byId('aiAssist').contains(event.target);
  if(!surface&&!vertical)return;
  const ticket=surface?global.assemblyBackTicket?.():null;
  gesture={id:event.pointerId,x:event.clientX,y:event.clientY,surface,vertical,ticket,task:currentTask,
   mode:'pending',threshold:surface?Math.max(65,Math.min(132,surface.clientWidth*.37)):0};
 },true);
 function moveGesture(event){
  const g=gesture;if(!g||g.id!==event.pointerId)return;
  const dx=event.clientX-g.x,dy=event.clientY-g.y,ax=Math.abs(dx),ay=Math.abs(dy);
  if(g.mode==='pending'){
   if(Math.max(ax,ay)<10)return;
   if(ax>=ay*1.45)g.mode=dx>0&&g.ticket?'back':'cancel';
   else if(ay>=ax*1.35)g.mode=g.vertical?'vertical':'cancel';
   else if(Math.max(ax,ay)>18)g.mode='cancel';
   else return;
   try{(g.surface||footer).setPointerCapture(g.id)}catch{}
  }
  event.preventDefault();
  if(g.mode!=='back')return;
  if(ay>Math.max(26,ax*.62)){g.mode='cancel';g.surface.classList.remove('back-armed','back-drag');return}
  const progress=Math.max(0,Math.min(1,(dx-10)/(g.threshold-10)));
  g.surface.classList.add('back-drag');
  g.surface.classList.toggle('back-armed',progress>=1);
  g.surface.style.setProperty('--back-shift',(progress*progress*g.threshold*.65)+'px');
 }
 document.addEventListener('pointermove',moveGesture,{capture:true,passive:false});
 document.addEventListener('pointerup',event=>{
  const g=gesture;if(!g||g.id!==event.pointerId)return;
  moveGesture(event);
  const dx=event.clientX-g.x,dy=event.clientY-g.y;
  const dragged=g.mode!=='pending'||Math.max(Math.abs(dx),Math.abs(dy))>=7;
  endGesture(dragged);
  if(!dragged||g.task!==currentTask)return;
  event.preventDefault();
  if(g.mode==='back'&&dx>=g.threshold)global.assemblySwipeBack?.(g.ticket);
  if(g.mode==='vertical'&&Math.abs(dy)>=18)setExpanded(dy<0);
 },{capture:true,passive:false});
 document.addEventListener('pointercancel',event=>{if(gesture?.id===event.pointerId)endGesture(true)},true);
 document.addEventListener('lostpointercapture',event=>{
  if(gesture?.id===event.pointerId&&event.target===(gesture.surface||footer)&&gesture.mode!=='pending')endGesture(true);
 },true);
 document.addEventListener('click',event=>{
  if((clock()<suppressUntil&&event.detail!==0)||(!expanded&&actions.contains(event.target))){
   event.preventDefault();event.stopImmediatePropagation();
  }
 },true);
 document.addEventListener('keydown',event=>{
  if(event.key==='ArrowLeft'&&!event.repeat&&backSurface(event.target)){
   const ticket=global.assemblyBackTicket?.();
   if(ticket){event.preventDefault();global.assemblySwipeBack?.(ticket)}
  }
 });
 global.addEventListener('blur',()=>endGesture(false));
 global.AssemblyWorkControls=Object.freeze({update,setExpanded,isExpanded:()=>expanded});
})(window);
