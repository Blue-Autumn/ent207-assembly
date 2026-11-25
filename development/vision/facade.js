/* Browser boundary for the unchanged R91 Kotlin geometry engine. */
const stableStates = new Set(['direction_ok','rotate','flip','wrong_part','install_base_flip',
  'install_base_ready','install_turn','install_stand','install_oriented','install_row_ok',
  'install_wrong_row','install_wrong_part','install_base_heading','install_notch_hint']);
const tasks = new Map(config.tasks.map(task=>[task.id,task]));
const core = globalThis.shelfGeometryCore;
core.init(config);
delete globalThis.shelfGeometryCore;
let lastStream = null, lastTime = -1;
function reset(){
  core.reset();
  lastStream = null;
  lastTime = -1;
}
function validPoint(p){
  return p && Number.isFinite(p.x) && Number.isFinite(p.y);
}
function isQuad(points){
  return Array.isArray(points) && points.length===4 && points.every(validPoint);
}
function insufficient(input){
  const english = !String(input?.lang||'en').startsWith('zh');
  return {status:'need_view',rawStatus:'need_view',part:null,markerIds:[],notchRelation:null,
    observedRow:null,targetRow:null,angleDegrees:null,installMotion:null,slotTargets:[],
    inferredSlotTargets:[],notchCue:[],backCue:[],placement:null,cueGeometry:null,alignedPlanes:{},
    anchor:null,axis:null,targetLabel:null,flipAxis:null,stable:false,notify:false,
    visible:false,seatingVerified:false,coordinateSpace:'detector-pixels',
    headline:english?'Show the current parts':'拍清当前组件',
    instruction:english?'Keep the markers and their corners in view.':'请露出标签和完整四角。'};
}
function evaluate(input){
  const task=tasks.get(input?.taskId);
  const markers=input?.markers;
  const slotQuads=input?.slotQuads||[];
  const mode=input?.mode==='photo'?'photo':'stream';
  if(!task || !Number.isInteger(input.width) || !Number.isInteger(input.height) ||
     input.width<=0 || input.height<=0 || !Array.isArray(markers) || markers.length>250 ||
     !markers.every(m=>m && Number.isInteger(m.id) && m.id>=0 && m.id<250 && isQuad(m.corners)) ||
     new Set(markers.map(m=>m.id)).size!==markers.length ||
     !Array.isArray(slotQuads) || slotQuads.length>128 || !slotQuads.every(isQuad)){
    if(mode==='stream')reset();
    return insufficient(input);
  }
  const atMs=Number.isFinite(input.atMs)?input.atMs:performance.now();
  if(mode==='stream'){
    const key=JSON.stringify([input.sessionId||'',task.id,input.width,input.height]);
    if(key!==lastStream||atMs<lastTime||atMs-lastTime>900)reset();
    lastStream=key;lastTime=atMs;
  }
  const out=core.evaluate({taskId:task.id,markers,width:input.width,height:input.height,slotQuads,mode,atMs});
  let {title,message}=out;
  if(!String(input.lang||'en').startsWith('zh')){
    const copy=labels.guideText({...out,state:out.status},task);
    title=copy.title;message=copy.message;
  }
  return {...out,headline:title,instruction:message,visible:out.markerIds.length>0,
    stable:mode==='stream' && stableStates.has(out.status),seatingVerified:false,coordinateSpace:'detector-pixels'};
}
const registeredParts=new Map(config.sites.map(site=>[site.id,site.part]));
function pairEvidence(taskId,markers){
  const task=tasks.get(taskId);
  if(task?.kind!=='prepare'||task.context.length!==2)return null;
  const counts=[0,0],ids=new Set();
  for(const m of markers){
    if(ids.has(m.id))continue;ids.add(m.id);
    const index=task.context.indexOf(registeredParts.get(m.id));
    if(index>=0)counts[index]++;
  }
  return {parts:task.context,counts};
}
function findParts(frame,markers,width,height){
  if(!frame||!Number.isFinite(frame.atMs)||!Number.isInteger(frame.generation)||!Array.isArray(markers)||
     markers.length>250||!markers.every(m=>Number.isInteger(m.id)&&isQuad(m.corners)))return [];
  const targets=frame.targets||[...new Set(markers.map(m=>registeredParts.get(m.id)).filter(Boolean))];
  if(!Array.isArray(targets)||!targets.length||targets.length>16)return [];
  const visible=new Set(markers.map(m=>registeredParts.get(m.id)));
  const regions=core.findParts({token:(frame.token||'finder')+'/'+width+'x'+height,generation:frame.generation,
    targets,markers,atMs:frame.atMs});
  return regions.map(r=>({...r,observed:visible.has(r.part)}));
}
globalThis.ShelfVision=Object.freeze({evaluate,reset,pairEvidence,findParts});
