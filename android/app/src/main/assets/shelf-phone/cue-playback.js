(function(root){
 'use strict';
 // Keep only the action's clock across brief missing observations. Never cache geometry.
 function create(maxGapMs=1500){
  let current=null,turn=null;
  function reset(){current=null;turn=null}
  function turnAngle({scope,part,face,angle,now,observedAt}){
   if(!scope||!part||!Number.isFinite(angle)||!Number.isFinite(now)||!Number.isFinite(observedAt)||observedAt>now||now-observedAt>maxGapMs)return angle;
   if(current&&(current.scope!==scope||now<current.lastAt||now-current.lastAt>maxGapMs))reset();
   // +179 and -179 degrees describe almost the same half turn. Choose one route until
   // the board actually leaves this small ambiguous band, instead of reversing each frame.
   if(turn&&turn.scope===scope&&turn.part===part&&turn.face===face&&now>=turn.at&&now-turn.at<=maxGapMs&&Math.abs(angle)>=165&&Math.abs(turn.angle)>=165)angle=Math.sign(turn.angle)*Math.abs(angle);
   turn={scope,part,face,angle,at:observedAt};return angle;
  }
  function observe({scope,key,now,observedAt,clear=false}){
   if(clear||!scope||!Number.isFinite(now)){reset();return null}
   if(current&&(current.scope!==scope||now<current.lastAt||now-current.lastAt>maxGapMs))reset();
   if(!key)return null;
   if(!Number.isFinite(observedAt)||observedAt>now||now-observedAt>maxGapMs)return null;
   if(!current||current.key!==key)current={scope,key,startedAt:observedAt,lastAt:observedAt};
   else current.lastAt=Math.max(current.lastAt,observedAt);
   return current.startedAt;
  }
  return {observe,turnAngle,reset,snapshot:()=>current?{...current}:null};
 }
 const api={create};if(typeof module==='object'&&module.exports)module.exports=api;root.CuePlayback=api;
})(typeof window!=='undefined'?window:globalThis);
