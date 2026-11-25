'use strict';
// A disassembly reset is performed only while the entire illustration is invisible.
(function(root){
 const times={start:2000,move:2800,end:2000,fadeOut:300,blank:180,fadeIn:300};
 const duration=Object.values(times).reduce((a,b)=>a+b,0);
 const insetTimes={start:0,move:900,end:260,fadeOut:120,blank:40,fadeIn:100};
 const insetDuration=Object.values(insetTimes).reduce((a,b)=>a+b,0);
 function insetEase(x){
  // Accelerate and decelerate over 130 ms each; travel at a steady speed in between.
  const ramp=130/insetTimes.move;
  if(x<ramp)return x*x/(2*ramp*(1-ramp));
  if(x>1-ramp)return 1-(1-x)*(1-x)/(2*ramp*(1-ramp));
  return (x-ramp/2)/(1-ramp);
 }
 function cycle(elapsed,period,stages,ease){let t=((elapsed%period)+period)%period;
  if(t<stages.start)return {stage:'start',amount:0,opacity:1};t-=stages.start;
  if(t<stages.move)return {stage:'inserting',amount:ease(t/stages.move),opacity:1};t-=stages.move;
  if(t<stages.end)return {stage:'assembled',amount:1,opacity:1};t-=stages.end;
  if(t<stages.fadeOut)return {stage:'fade-out',amount:1,opacity:1-t/stages.fadeOut};t-=stages.fadeOut;
  if(t<stages.blank)return {stage:'hidden-reset',amount:0,opacity:0};t-=stages.blank;
  return {stage:'fade-in',amount:0,opacity:Math.min(1,t/stages.fadeIn)};
 }
 const smootherStep=x=>x*x*x*(x*(x*6-15)+10);
 const sample=elapsed=>cycle(elapsed,duration,times,smootherStep);
 const sampleInset=elapsed=>cycle(elapsed,insetDuration,insetTimes,insetEase);
 root.AssemblyMotionCycle={sample,duration,times,sampleInset,insetDuration};
 if(typeof module!=='undefined')module.exports=root.AssemblyMotionCycle;
})(globalThis);
