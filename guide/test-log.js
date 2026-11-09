'use strict';
function makeTestLog(profile) {
 const key='ent207.testlog.v1:'+profile;
 let record;
 try{record=JSON.parse(sessionStorage.getItem(key)||'null');}catch{record=null;}
 function save(){try{sessionStorage.setItem(key,JSON.stringify(record));}catch{}}
 function start(){
  record={format:'ent207-browser-test-1',version:'R91-web-4',started:new Date().toISOString(),
    viewport:[innerWidth,innerHeight],events:[]};save();
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
  const blob=new Blob([JSON.stringify(record,null,2)],{type:'application/json'});
  const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='ENT207-device-test-'+record.started.slice(0,10)+'.json';
  document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(link.href),30000);
  record=null;save();
 }
 return {start,add,download,get active(){return !!record}};
}
