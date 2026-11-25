'use strict';
// Approximate screen orientation from declared corner layout, not a calibrated 6DoF pose.
(function(root){
 const dot=(a,b)=>a.reduce((s,x,k)=>s+x*b[k],0),cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
 const norm=v=>Math.hypot(...v),unit=v=>{const n=norm(v);return n>1e-10?v.map(x=>x/n):null};
 const times={start:1500,move:2000,end:2000,fadeOut:260,blank:160,fadeIn:260},duration=Object.values(times).reduce((a,b)=>a+b,0);
 function sample(elapsed){let t=((elapsed%duration)+duration)%duration;
  if(t<times.start)return {stage:'start',amount:0,opacity:1};t-=times.start;
  if(t<times.move){const x=t/times.move;return {stage:'turning',amount:x*x*x*(x*(x*6-15)+10),opacity:1}}t-=times.move;
  if(t<times.end)return {stage:'turned',amount:1,opacity:1};t-=times.end;
  if(t<times.fadeOut)return {stage:'fade-out',amount:1,opacity:1-t/times.fadeOut};t-=times.fadeOut;
  if(t<times.blank)return {stage:'hidden-reset',amount:0,opacity:0};t-=times.blank;
  return {stage:'fade-in',amount:0,opacity:Math.min(1,t/times.fadeIn)};
 }
 function canonicalBasis(axis,face,heading=0){
  const uv=[0,1,2].filter(k=>k!==axis),right=[0,0,0],eye=[0,0,0];right[uv[0]]=1;eye[axis]=face==='B'?-1:1;
  const up=cross(eye,right),a=heading*Math.PI/180,c=Math.cos(a),s=Math.sin(a);
  // Image y is downward, so a positive angle places the +u vector clockwise from screen right.
  return [right.map((x,k)=>c*x+s*up[k]),right.map((x,k)=>-s*x+c*up[k]),eye];
 }
 function basisFromObservation(observation,axis){
  const face=observation?.face==='B'?'B':'A',uv=[0,1,2].filter(k=>k!==axis),rows=observation?.axisDirections||[];
  const dirs=uv.map(k=>rows.find(d=>d.cadAxis==='XYZ'[k]));
  let heading=Number(dirs[0]?.screenDegreesClockwise);if(!Number.isFinite(heading))heading=0;
  const fallback=()=>({basis:canonicalBasis(axis,face,heading),quality:dirs[0]?'heading_only':'canonical_face'});
  if(observation?.reason!=='coarse_axes'||dirs.some(d=>!d||!Array.isArray(d.screenPerModelUnit)||d.screenPerModelUnit.length!==2||d.screenPerModelUnit.some(v=>!Number.isFinite(v))))return fallback();
  const a=dirs[0].screenPerModelUnit,b=dirs[1].screenPerModelUnit;
  const g00=a[0]*a[0]+b[0]*b[0],g11=a[1]*a[1]+b[1]*b[1],g01=a[0]*a[1]+b[0]*b[1];
  const lambda=(g00+g11+Math.hypot(g00-g11,2*g01))/2,scale=Math.sqrt(lambda);
  if(!Number.isFinite(scale)||scale<1e-8)return fallback();
  // Complete the 2x2 planar image map to two orthogonal camera rows. The unobserved tilt
  // branch is only a display convention; no depth, translation, or metric measurement is inferred.
  const right=[0,0,0],down=[0,0,0];right[uv[0]]=a[0]/scale;right[uv[1]]=b[0]/scale;
  down[uv[0]]=a[1]/scale;down[uv[1]]=b[1]/scale;
  const k00=Math.max(0,1-g00/lambda),k11=Math.max(0,1-g11/lambda),k01=-g01/lambda;
  right[axis]=Math.sqrt(k00);down[axis]=(k01<0?-1:1)*Math.sqrt(k11);
  const r=unit(right),up=unit(down.map(x=>-x));if(!r||!up||Math.abs(dot(r,up))>1e-5)return fallback();
  const eye=unit(cross(r,up));if(!eye||eye[axis]*(face==='B'?-1:1)<.05)return fallback();
  return {basis:[r,up,eye],quality:'approximate_planar_projection'};
 }
 function rotate(point,axis,angle){const n=unit(axis);if(!n||!Number.isFinite(angle))return point.slice();
  const c=Math.cos(angle),s=Math.sin(angle),v=cross(n,point),d=dot(n,point);return point.map((x,k)=>x*c+v[k]*s+n[k]*d*(1-c));
 }
 function select(info){
  if(!info||typeof info!=='object'||info.policy!=='layer_notch_direction')return null;
  // A general needs_adjustment verdict can concern a joint or an unaligned raised part.
  // Only a validated, explicit action from this photo may trigger an orientation demo.
  const action=info.action;
  if(!action||action.flat_pair_visible!==true||typeof action.part!=='string')return null;
  const parts=info.parts;if(!Array.isArray(parts)||parts.length!==2||parts.some(p=>!['A','B'].includes(p.face))||!parts.some(p=>p.part===action.part))return null;
  const turn=info.rotationSuggestion;
  if(action.kind==='rotate_in_plane'&&info.status==='faces_unrestricted'&&info.directionStatus==='rotate_in_plane'&&
   turn&&turn.part===action.part&&Number.isFinite(turn.screenDegreesClockwise)&&Math.abs(turn.screenDegreesClockwise)>=25&&Math.abs(turn.screenDegreesClockwise)<=180)
   return {kind:'rotate',part:turn.part,degrees:turn.screenDegreesClockwise};
  return null;
 }
 root.AssemblyOrientationPreview={sample,duration,times,canonicalBasis,basisFromObservation,rotate,select};
 if(typeof module!=='undefined')module.exports=root.AssemblyOrientationPreview;
})(globalThis);
