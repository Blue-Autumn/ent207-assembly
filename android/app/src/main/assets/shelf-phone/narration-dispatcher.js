'use strict';
// One owner of narration. Brief camera noise is silent; a sustained correction may replace an obsolete action.
(function(root){
 const installActions={
  install_base_heading:'沿桌面转，把第一排孔放到远侧。',install_notch_hint:'看箭头，摆正缺口。',
  install_base_flip:'翻个面，把灰框放平。',install_base_ready:'这面朝上，放稳灰框。',
  install_turn:'方向反了。把手里的板转半圈。',install_stand:'把层板立起来，扶稳。',
  install_align:'扶稳板子，对准这对槽。',install_show_base:'把底框的标签也拍进来。',
  install_show_target:'把手里这块也拍进来。',install_row_ok:'位置对了，检查接缝。',
  install_wrong_row:'插错排了。先抬起，对准亮起的槽。',install_wrong_part:'换一件，找画面里的组件。',
  install_check:'看看示范，核对接口。',install_oriented:'方向对了，对准接头。'
 };
 const installStates=new Set(Object.keys(installActions));
 function localCue(observed){
  if(!observed)return null;
  if(installStates.has(observed.state)){
   const view=['install_show_base','install_show_target','install_check','install_align'].includes(observed.state);
   if(observed.visible!==true||!view&&observed.stable!==true)return null;
   const specific={flip_one:'先分开灰框，把提示的半块翻个面。',turn_one:'先分开灰框，把提示的半块平放转正。',
    turn_over_end:'背板上下倒了。把下边翻到上面。',turn_back_ends:'背板左右反了。沿竖轴翻一面。',turn_back_in_plane:'背板倒了，沿板面掉个头。'};
   const text=specific[observed.installMotion]||observed.title||installActions[observed.state];
   return {key:observed.state+'|'+(observed.installMotion||'')+'|'+text,text,kind:view?'view':'action',
    action:observed.state,stableMs:view?2000:650,requireFreshMs:900};
  }
  if(observed.state==='need_view'){
   // Native stabilization also emits need_view while deciding. Only explicit capture
   // problems qualify; never infer a request from generic '正在看方向' or its message.
   const views={
    '把另一半也拍进来':['missing-part','另一块也拍进来。'],
    '把当前要装的零件拍进来':['missing-part','把要装的那块拍进来。'],
    '再露出一个角标':['missing-marker','再露出一个角标。'],
    '再露出两边的标签':['missing-marker','露出两边的标签。'],
    '先把两半平放':['lay-flat','先把两块放平。'],
    '拍清两半的这一面':['lay-flat','先把两块放平。'],
    '把手机稍微拿稳':['hold-steady','手机拿稳一点。'],
    '移到两块中间看看':['change-view','移到两块中间看看。']
   };
   const view=views[observed.title];
   return view?{key:'view|'+view[0],text:view[1],kind:'view',stableMs:2200,requireFreshMs:900}:null;
  }
  if(observed.stable!==true)return null;
  const references=new Set(['左手边这块','右手边这块','左边这块','右边这块','上面这块','下面这块','中间这块','箭头指的这块']);
  const reference=references.has(observed.targetLabel)?observed.targetLabel.replace('左边','左手边').replace('右边','右手边'):'箭头指的这块';
  const placement=observed.state==='direction_ok'&&observed.placement?.method==='coplanar_measured_translation'&&
   Number.isFinite(observed.placement.deltaLocal?.x)&&Number.isFinite(observed.placement.deltaLocal?.y)&&
   Number.isFinite(observed.placement.distanceMm)&&observed.placement.distanceMm>0;
  const lines={flip:reference+'，翻一下面。',rotate:reference+'，转一下。',direction_ok:'方向对了，可以接。',
   wrong_part:'拿错了，先看示范。',face_check:'先别接，核对这块板的正反面。',
   identity_only:'找对了。对照动画摆好方向。'};
  const text=placement?'方向对了。拿起'+reference+'，移到虚影处。':lines[observed.state];
  if(!text||observed.visible!==true)return null;
  return {key:observed.state+'|'+(observed.part||'')+(placement?'|place':'')+(['flip','rotate'].includes(observed.state)?'|'+reference:''),text,kind:'action',action:observed.state,stableMs:650};
 }
 function partName(id){
  const prefix=String(id||'').replace(/-[12]$/,'');
  return ({'层1板':'这半块紫色层板','层2板':'这半块蓝色层板','层3板':'这半块肉色层板',
   '层4板':'这半块黄色层板','层5板':'这半块绿色层板','左侧板':'这半块灰色侧框','右侧板':'这半块灰色侧框','背板':'这半块背板'})[prefix]||'画面里这块板';
 }
 function create(options){
  const now=options.now||Date.now,delay=options.setTimer||setTimeout,clear=options.clearTimer||clearTimeout;
  const cooldown=options.cooldownMs??900,repeatAfter=options.repeatAfterMs??3000;
  let context='',enabled=true,serial=0,active=null,pending=null,timer=null,watchdog=null;
  let lastFinish=-Infinity,lastCorrection=-Infinity,lastKey='',actionSpoken=false,entrySpoken=false,fault=false;
  const recent=new Map();
  function emit(state,message=''){options.changed?.({state,token:active?.token||null,message})}
  function clearSchedule(){if(timer!==null)clear(timer);timer=null}
  function clearWatchdog(){if(watchdog!==null)clear(watchdog);watchdog=null}
  function snapshot(){return {context,enabled,active:active?{...active}:null,pending:pending?{...pending}:null,fault}}
  function eligible(cue){
   if(cue.kind==='entry')return !entrySpoken&&!actionSpoken;
   return cue.key!==lastKey;
  }
  function fail(message,stop=true){
   clearSchedule();clearWatchdog();pending=null;active=null;fault=true;
   // A missing engine callback is a fault, not permission to start overlapping speech.
   if(stop)options.stop();emit('unavailable',message);
  }
  function armWatchdog(ms){clearWatchdog();watchdog=delay(()=>fail('语音暂时没有响应，请看画面。'),ms)}
  function pump(){
   clearSchedule();
   if(!enabled||fault||!pending||!eligible(pending))return;
   if(pending.kind==='view'&&now()-pending.since>=pending.stableMs&&
      (pending.samples<3||now()-pending.lastObserved>pending.requireFreshMs)){
    pending=null;return;
   }
   if(active){
    // A confirmed new instruction supersedes the obsolete one. Intermediate searching,
    // need_view, angle jitter, and duplicate callbacks never reach this branch.
    const correction=pending.kind==='action'&&pending.key!==active.key&&
     (installStates.has(pending.action)||['flip','rotate','direction_ok','wrong_part','face_check','found'].includes(pending.action));
    if(!correction)return;
    const correctionAt=Math.max(pending.since+(options.correctionStableMs??1100),
     active.requestedAt+(options.minimumReadMs??450),lastCorrection+(options.correctionGapMs??2200));
    if(now()<correctionAt){timer=delay(pump,correctionAt-now());return}
    clearWatchdog();active=null;lastKey='';lastCorrection=now();options.stop();emit('idle');
    lastFinish=now()-cooldown+120;pending.correcting=true;
   }
   const readyAt=Math.max(pending.since+pending.stableMs,lastFinish+cooldown,
    pending.kind==='entry'||pending.correcting?-Infinity:(recent.get(pending.key)??-Infinity)+repeatAfter);
   if(now()<readyAt){timer=delay(pump,readyAt-now());return}
   active={...pending,token:'narration-'+(++serial),requestedAt:now()};pending=null;
   lastKey=active.key;recent.set(active.key,now());
   if(active.kind==='entry')entrySpoken=true;else actionSpoken=true;
   emit('requested');armWatchdog(options.startTimeoutMs??15000);
   try{options.speak(active.text,active.token)}catch(_){fail('语音暂不可用，请看画面。')}
  }
  function observe(cue){
   if(!enabled||fault)return;
   if(!cue||!cue.key||!cue.text){pending=null;clearSchedule();return}
   const next={...cue,kind:cue.kind||'action',stableMs:cue.stableMs??650};
   if(!eligible(next)||active?.key===next.key){pending=null;clearSchedule();return}
   if(pending?.key===next.key&&next.kind==='view'&&now()-pending.lastObserved>next.requireFreshMs)pending=null;
   // The key is semantic: angle, wording and frame counters do not reset dwell time.
   if(pending?.key===next.key){pending={...next,since:pending.since,correcting:pending.correcting,lastObserved:now(),samples:pending.samples+1};pump();return}
   pending={...next,since:now(),lastObserved:now(),samples:1};pump();
  }
  function cancel(forget=false){
   const hadActive=!!active;clearSchedule();clearWatchdog();pending=null;active=null;
   if(hadActive)options.stop();
   if(forget){recent.clear();lastKey='';entrySpoken=false;actionSpoken=false;lastFinish=-Infinity;lastCorrection=-Infinity;fault=false}
   emit('idle');
  }
  function setContext(value){if(context===value)return;cancel(true);context=value}
  function setEnabled(value){if(enabled===!!value)return;enabled=!!value;cancel(true)}
  function nativeState(event){
   if(!active||event.token!==active.token)return;
   if(event.state==='speaking'){
    if(!active.started){active.started=true;armWatchdog(options.utteranceTimeoutMs??120000)}
    emit('speaking');return;
   }
   if(event.state==='queued'||event.state==='requested')return;
   if(event.state==='finished'){
    clearWatchdog();active=null;lastFinish=now();emit('idle');pump();return;
   }
   if(['error','unavailable','busy','stopped'].includes(event.state))fail(event.message||'语音暂不可用，请看画面。',event.state!=='busy');
  }
  return {observe,setContext,setEnabled,cancel,nativeState,snapshot};
 }
 root.AssemblyNarration={create,localCue,partName,installStates};
 if(typeof module!=='undefined')module.exports=root.AssemblyNarration;
})(globalThis);
