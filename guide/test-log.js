'use strict';
function makeTestLog(profile) {
 const key='ent207.testlog.v1:'+profile;
 let record, batch=null;
 try{record=JSON.parse(sessionStorage.getItem(key)||'null');}catch{record=null;}
 function save(){try{sessionStorage.setItem(key,JSON.stringify(record));}catch{}}
 function start(){
  batch=null;
  record={format:'ent207-browser-test-1',version:'R91-web-5',started:new Date().toISOString(),
    viewport:[innerWidth,innerHeight],events:[],cameraTimings:[]};save();
 }
 function flushCamera(){
  if(!record || !batch)return;
  function median(name){
   const values=batch.frames.map(f=>f[name]).filter(n=>Number.isFinite(n)&&(name!=='gap'||n>0)).sort((a,b)=>a-b);
   if(!values.length)return null;
   const middle=Math.floor(values.length/2);
   return Math.round(10*(values.length%2?values[middle]:(values[middle-1]+values[middle])/2))/10;
  }
  const last=batch.frames.at(-1);
  (record.cameraTimings??=[]).push({phase:batch.phase,detector:batch.detector,elapsedMs:Date.now()-Date.parse(record.started),
   frames:batch.frames.length,sourcePixels:last.source,analysisPixels:last.size,lens:last.lens,
   medianIntervalMs:median('gap'),medianCanvasMs:median('copy'),medianWorkerRoundTripMs:median('read'),
   medianUiUpdateMs:median('ui'),medianTagCount:median('ids'),centerRescues:batch.frames.filter(f=>f.center).length});
  if(record.cameraTimings.length>120)record.cameraTimings.shift();
  batch=null;save();
 }
 function sample(phase,frame){
  if(!record)return;
  const detector=frame.detector||'fast-4x4-250',now=performance.now();
  if(batch && (batch.phase!==phase || batch.detector!==detector || !frame.gap || now-batch.since>=1000))flushCamera();
  if(!batch)batch={phase,detector,since:now,frames:[]};
  batch.frames.push(frame);
 }
 function add(info){
  if(!record)return;
  const previous=record.events.at(-1);
  const clean={task:info.taskId,phase:info.phase,source:info.source,status:info.status,
    ids:(info.markerIds||[]).slice(0,40).sort((a,b)=>a-b),observedRow:info.observedRow||null,targetRow:info.targetRow||null};
  if(previous && JSON.stringify(previous.result)===JSON.stringify(clean))return;
  record.events.push({elapsedMs:Math.max(0,Date.now()-Date.parse(record.started)),result:clean});
  if(record.events.length>300)record.events.shift();save();
 }
 function download(){
  if(!record)return;
  flushCamera();
  const blob=new Blob([JSON.stringify(record,null,2)],{type:'application/json'});
  const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='ENT207-device-test-'+record.started.slice(0,10)+'.json';
  document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(link.href),30000);
  record=null;save();
 }
 window.addEventListener('pagehide',flushCamera);
 return {start,add,sample,download,latestCamera(){flushCamera();return record?.cameraTimings?.at(-1);},get active(){return !!record}};
}
