/* Board-plane teaching primitives. Measured homographies are planar overlays, not 3D pose verdicts. */
(function(root){
 'use strict';
 const finite=p=>p&&Number.isFinite(p.x)&&Number.isFinite(p.y);
 const paths=new Map(),views=new WeakMap();
 function command(event,task,parts,now,observedAt){
  if(!event||!Array.isArray(parts)||event.source!=='local'||event.task!==task||event.stable!==true||event.visible!==true||
     !['rotate','flip','direction_ok'].includes(event.state)||!parts.includes(event.part)||
     !Number.isFinite(now)||!Number.isFinite(observedAt)||now-observedAt>1500||now<observedAt)return null;
  const p=event.cueGeometry;
  if(!p||!Number.isInteger(p.supportCount)||p.supportCount<(p.method==='measured_homography'?2:3)||p.supportCount>4||!Array.isArray(p.axes)||p.axes.length!==2||!finite(p.center)||
     !p.axes.every(a=>a&&[0,1,2].includes(a.axis)&&finite(a.start)&&finite(a.end))||p.axes[0].axis===p.axes[1].axis||
     !Array.isArray(p.localCenter)||!p.localCenter.every(Number.isFinite)||p.localCenter.length!==2||!Array.isArray(p.localHalfExtent)||p.localHalfExtent.length!==2||
     !p.localHalfExtent.every(n=>Number.isFinite(n)&&n>0)||!['nominal_affine','measured_homography'].includes(p.method))return null;
  if(p.method==='measured_homography'&&!validMeasured(p))return null;
  const translating=event.state==='direction_ok';
  if(translating&&!validTranslation(p,event.placement,parts,event.part))return null;
  if(event.state==='rotate'&&(!Number.isFinite(event.angle)||Math.abs(event.angle)>180||Math.abs(event.angle)<10))return null;
  const flipIndex=p.axes.findIndex(a=>a.axis===event.flipAxis);
  if(event.state==='flip'&&flipIndex<0)return null;
  const kind=translating?'translate':event.state;
  return {kind,part:event.part,angle:event.angle||0,flipIndex,projection:p,
   translation:translating?[event.placement.deltaLocal.x,event.placement.deltaLocal.y]:null,
   label:['左手边这块','右手边这块','上面这块','下面这块'].includes(event.targetLabel)?event.targetLabel:'箭头指的这块',
   key:[task,event.part,kind,translating?event.placement.fixedPart:event.state==='flip'?event.flipAxis:Math.sign(event.angle)].join('|')};
 }
 function validTranslation(p,placement,parts,part){
  if(p.method!=='measured_homography'||!placement||placement.method!=='coplanar_measured_translation'||
     !parts.includes(placement.fixedPart)||placement.fixedPart===part||!finite(placement.deltaLocal)||
     !Number.isFinite(placement.distanceMm)||placement.distanceMm<1||placement.distanceMm>3000)return false;
  const d=placement.deltaLocal,mm=Math.hypot(d.x*p.localHalfExtent[0],d.y*p.localHalfExtent[1]);
  if(!Number.isFinite(mm)||Math.abs(mm-placement.distanceMm)>Math.max(.1,mm*.01))return false;
  // Every denominator varies linearly along this planar translation. Positive endpoints
  // therefore protect the complete path, including cutouts and off-screen destination edges.
  return [p.outlineLocal,...p.holesLocal].every(r=>r.every(q=>
   projectPoint(p.planeToImage,{x:q.x+d.x,y:q.y+d.y})!==null));
 }
 function geometry(c,width,height){
  if(!c||width<1||height<1)return null;
  const p=c.projection,center=[p.center.x*width,p.center.y*height];
  const a=p.axes.map(x=>[(x.end.x-x.start.x)*width/2,(x.end.y-x.start.y)*height/2]);
  const det=a[0][0]*a[1][1]-a[0][1]*a[1][0];
  const lengths=a.map(v=>Math.hypot(...v));
  if(![...center,...a.flat(),det].every(Number.isFinite)||lengths.some(v=>v<15||v>Math.max(width,height)*2)||
     Math.abs(det)<lengths[0]*lengths[1]*.15||center[0]<0||center[0]>width||center[1]<0||center[1]>height)return null;
  const result={center,axes:a,det,matrix:[...a[0],...a[1],...center],width,height};
  if(p.method==='measured_homography'){
   if(!validMeasured(p))return null;
   const projected=projectPoint(p.planeToImage,{x:0,y:0},width,height);
   if(!projected||Math.hypot(projected.x-center[0],projected.y-center[1])>2)return null;
   result.homography=p.planeToImage;
  }
  return result;
 }
 function validMeasured(p){
  const h=p.planeToImage,outline=p.outlineLocal,holes=p.holesLocal;
  const ring=a=>Array.isArray(a)&&a.length>=3&&a.length<=4096&&a.every(q=>finite(q)&&Math.abs(q.x)<=1.02&&Math.abs(q.y)<=1.02);
  if(!Array.isArray(h)||h.length!==9||!h.every(Number.isFinite)||Math.abs(h[8])<1e-10||!ring(outline)||
     !Array.isArray(holes)||holes.length>64||!holes.every(ring)||outline.length+holes.reduce((n,r)=>n+r.length,0)>8192)return false;
  const n=h.map(v=>v/h[8]);
  const determinant=n[0]*(n[4]*n[8]-n[5]*n[7])-n[1]*(n[3]*n[8]-n[5]*n[6])+n[2]*(n[3]*n[7]-n[4]*n[6]);
  if(Math.abs(determinant)<1e-9)return false;
  return [-1.02,1.02].every(x=>[-1.02,1.02].every(y=>n[6]*x+n[7]*y+1>.12));
 }
 function projectPoint(h,q,width=1,height=1,m=[1,0,0,1,0,0]){
  if(!Array.isArray(h)||h.length!==9||!h.every(Number.isFinite)||Math.abs(h[8])<1e-10||!finite(q)||
     !Array.isArray(m)||m.length!==6||!m.every(Number.isFinite)||!Number.isFinite(width)||!Number.isFinite(height)||width<=0||height<=0)return null;
  const x=m[0]*q.x+m[2]*q.y+m[4],y=m[1]*q.x+m[3]*q.y+m[5],w=h[6]*x+h[7]*y+h[8];
  if(!Number.isFinite(w)||w/h[8]<=.12)return null;
  const p={x:(h[0]*x+h[1]*y+h[2])/w*width,y:(h[3]*x+h[4]*y+h[5])/w*height};
  return finite(p)?p:null;
 }
 function projectedPath(c,g,m=[1,0,0,1,0,0]){
  if(c?.projection?.method!=='measured_homography'||!g)return '';
  const rings=[c.projection.outlineLocal,...c.projection.holesLocal];
  const projected=rings.map(r=>r.map(q=>projectPoint(g.homography,q,g.width,g.height,m)));
  // A long thin board can move beyond the static normalized bounds. Retract that ghost rather
  // than drawing a shape across the homography horizon or dropping only one of its holes.
  if(projected.some(r=>r.some(q=>!finite(q))))return '';
  return projected.map(r=>screenPath(r,true)).join('');
 }
 function screenPath(points,closed=false){
  if(!points.length||!points.every(finite))return '';
  return 'M'+points.map(q=>`${q.x.toFixed(2)},${q.y.toFixed(2)}`).join('L')+(closed?'Z':'');
 }
 // Extend the exact observed plane with an illustrative normal. The virtual focal length is
 // deliberately not a camera calibration: only z=0 is a measured correspondence. It gives the
 // teaching ghost a coherent near/far edge during a flip without changing the real footprint.
 function teachingProjection(c,g){
  if(c?.projection?.method!=='measured_homography'||!g?.homography)return null;
  const h=g.homography.map(v=>v/g.homography[8]),half=c.projection.localHalfExtent;
  const f=Math.max(g.width,g.height)*1.35,cx=g.width/2,cy=g.height/2;
  const basis=[0,1].map(i=>[(h[i]*g.width-cx*h[6+i])/f/half[i],(h[3+i]*g.height-cy*h[6+i])/f/half[i],h[6+i]/half[i]]);
  const [a,b]=basis,n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],length=Math.hypot(...n);
  if(!Number.isFinite(length)||length<1e-12)return null;
  const sign=n[2]>0?-1:1,normal=n.map(v=>v/length*sign);
  let scale=(Math.hypot(...a)+Math.hypot(...b))/2;
  // Bound the illustrative depth, especially with a very oblique wide-angle observation.
  // A rejected ghost must never bridge a projective horizon or silently lose an interior hole.
  const minW=1-Math.abs(h[6])*1.02-Math.abs(h[7])*1.02;
  const maxLift=Math.max(...half)+6;
  scale=Math.min(scale,Math.max(0,minW-.12)*.65/Math.max(1e-9,Math.abs(normal[2])*maxLift));
  const z=[(f*normal[0]+cx*normal[2])*scale/g.width,(f*normal[1]+cy*normal[2])*scale/g.height,normal[2]*scale];
  return {h,z,width:g.width,height:g.height,half};
 }
 function liftedPoint(projector,q,flipIndex,progress,depth=0){
  if(!projector||!finite(q)||![0,1].includes(flipIndex)||!Number.isFinite(progress)||progress<0||progress>1||!Number.isFinite(depth))return null;
  const {h,z,half,width,height}=projector,angle=progress*Math.PI;
  const sine=Math.sin(angle),cosine=Math.cos(angle),axis=1-flipIndex;
  let x=q.x,y=q.y;
  const across=axis===0?q.x:q.y,lift=across*half[axis]*sine+depth*cosine;
  const rotated=across*cosine-depth/half[axis]*sine;
  if(axis===0)x=rotated;else y=rotated;
  const w=h[6]*x+h[7]*y+h[8]+z[2]*lift;
  if(w<=.12||!Number.isFinite(w))return null;
  const point={x:(h[0]*x+h[1]*y+h[2]+z[0]*lift)/w*width,y:(h[3]*x+h[4]*y+h[5]+z[1]*lift)/w*height};
  return finite(point)?point:null;
 }
 function flipSurface(c,g,m){
  const projector=teachingProjection(c,g);
  if(!projector||c.kind!=='flip')return null;
  const rings=[c.projection.outlineLocal,...c.projection.holesLocal];
  // A modest visual bevel, not an inferred/verified physical board thickness.
  const thickness=Math.max(2,Math.min(5,Math.min(...projector.half)*.04));
  const top=rings.map(r=>r.map(q=>liftedPoint(projector,q,c.flipIndex,m.progress)));
  const bottom=rings.map(r=>r.map(q=>liftedPoint(projector,q,c.flipIndex,m.progress,-thickness)));
  if([...top,...bottom].some(r=>r.some(q=>!finite(q))))return {surface:'',edges:'',back:''};
  const sides=[];
  for(let ring=0;ring<top.length;ring++)for(let i=0;i<top[ring].length;i++){
   const j=(i+1)%top[ring].length;
   sides.push(screenPath([top[ring][i],top[ring][j],bottom[ring][j],bottom[ring][i]],true));
  }
  return {surface:top.map(r=>screenPath(r,true)).join(''),edges:sides.join(''),back:bottom.map(r=>screenPath(r,true)).join('')};
 }
 function actionShape(c,det){
  const pt=(x,y)=>({x,y}),curve=(a,b,d,e)=>Array.from({length:20},(_,i)=>{const t=i/19,s=1-t;return pt(s*s*s*a[0]+3*s*s*t*b[0]+3*s*t*t*d[0]+t*t*t*e[0],s*s*s*a[1]+3*s*s*t*b[1]+3*s*t*t*d[1]+t*t*t*e[1])});
  if(c.kind==='translate'){
   const h=c.projection.localHalfExtent,d=c.translation,dx=d[0]*h[0],dy=d[1]*h[1],length=Math.hypot(dx,dy);
   const ux=dx/length,uy=dy/length,span=Math.min(...h),tip=Math.min(length*.22,span*.45);
   const end=Math.min(length,Math.max(...h)*1.25),start=Math.min(end*.12,span*.15),shaft=Math.min(end*.12,span*.17);
   const point=(along,across)=>pt((ux*along-uy*across)/h[0],(uy*along+ux*across)/h[1]);
   return {axis:[pt(0,0),point(end,0)],arrow:[point(start,-shaft),point(end-tip,-shaft),point(end-tip,-shaft*2),point(end,0),point(end-tip,shaft*2),point(end-tip,shaft),point(start,shaft)]};
  }
  if(c.kind==='flip'){
   const arrow=[...curve([-.12,.64],[-.6,.25],[-.6,-.24],[-.12,-.61]),pt(-.31,-.66),pt(.16,-.83),pt(.19,-.34),pt(.02,-.48),...curve([.02,-.48],[-.22,-.24],[-.21,.23],[.07,.46])];
   const orient=q=>c.flipIndex===0?q:pt(q.y,q.x);
   return {axis:[pt(-.92,0),pt(.92,0)].map(orient),arrow:arrow.map(orient)};
  }
  const arc=(r,start,end)=>Array.from({length:36},(_,i)=>{const a=(start+(end-start)*i/35)*Math.PI/180;return pt(r*Math.cos(a),r*Math.sin(a))});
  const arrow=[...arc(.71,135,395),...arc(.9,395,395),...arc(.59,435,435),...arc(.27,395,395),...arc(.47,395,135)];
  return {axis:[],arrow:arrow.map(q=>c.angle*det<0?pt(-q.x,q.y):q)};
 }
 function modelPath(c,part){
  if(!part||part.id!==c.part||!Array.isArray(part.verts)||part.verts.length%9)return '';
  const p=c.projection,key=[part.id,p.axes.map(a=>a.axis),p.localCenter,p.localHalfExtent].join('|');
  if(paths.has(key))return paths.get(key);
  const uv=p.axes.map(a=>a.axis),out=[],v=part.verts;
  for(let i=0;i<v.length;i+=9){
   let tri=[0,3,6].map(k=>uv.map((axis,j)=>(v[i+k+axis]-p.localCenter[j])/p.localHalfExtent[j]));
   if(!tri.flat().every(Number.isFinite))continue;
   const area=(tri[1][0]-tri[0][0])*(tri[2][1]-tri[0][1])-(tri[1][1]-tri[0][1])*(tri[2][0]-tri[0][0]);
   if(Math.abs(area)<1e-7)continue;
   // Front and back triangles have opposite winding; normalize before the nonzero union fill.
   if(area<0)tri.reverse();
   out.push('M'+tri.map(q=>q.map(n=>+n.toFixed(5)).join(',')).join('L')+'Z');
  }
  const value=out.join('');paths.set(key,value);return value;
 }
 function sampleMotion(kind,angle,flipIndex,det,elapsed,halfExtent=[1,1],translation=[0,0]){
  const t=((elapsed%4600)+4600)%4600;
  const travel=Math.max(0,Math.min(1,(t-700)/1600)),ease=travel*travel*(3-2*travel);
  const opacity=t<300? t/300:t<3100?1:t<3550?1-(t-3100)/450:0;
  // One forward gesture, hold, disappear, then reset invisibly. Never show a reverse assembly gesture.
  if(kind==='translate')return {matrix:[1,0,0,1,translation[0]*ease,translation[1]*ease],opacity,progress:ease,face:'front'};
  if(kind==='rotate'){
   const a=angle*Math.sign(det)*ease*Math.PI/180;
   const ratio=halfExtent[0]/halfExtent[1];
   return {matrix:[Math.cos(a),Math.sin(a)*ratio,-Math.sin(a)/ratio,Math.cos(a),0,0],opacity,progress:ease,face:'front'};
  }
  const a=Math.PI*ease,scale=Math.cos(a);
  // Complete the same hinge-centered gesture for nominal and measured cues. The measured
  // renderer adds bounded illustrative depth; endpoints remain this exact planar mapping.
  return {matrix:flipIndex===0?[1,0,0,scale,0,0]:[scale,0,0,1,0,0],opacity,progress:ease,face:scale<0?'back':'front'};
 }
 function hide(element){
  const v=views.get(element);if(v){cancelAnimationFrame(v.frame);views.delete(element)}
  element.hidden=true;
 }
 function render(element,event,options){
  const c=command(event,options.task,options.parts,options.now,options.observedAt);
  const g=geometry(c,options.width,options.height),part=options.scene?.find(p=>p.id===c?.part);
  const measured=c?.projection.method==='measured_homography';
  if(!g||(!measured&&!part)){hide(element);return false}
  let v=views.get(element);
  if(!v){
   element.innerHTML='<svg class="board-cue-scene" aria-hidden="true"><g class="board-cue-plane"><path class="board-cue-footprint"/><g class="board-cue-depth" style="fill:#986025;stroke:#754717;stroke-width:.7;fill-rule:evenodd;stroke-linejoin:round"><path class="board-cue-back"/><path class="board-cue-edges"/></g><g class="board-cue-ghost"><path/></g><g class="board-cue-action"><path class="board-cue-axis"/><path class="board-cue-ribbon"/></g></g></svg><span id="guidePart"></span>';
   v={key:null,frame:0,start:0};views.set(element,v);
  }
  v.command=c;v.geometry=g;
  const explicitEpoch=Number.isFinite(options.motionStartedAt)&&options.motionStartedAt<=options.now?options.motionStartedAt:null;
  if(v.key!==c.key||explicitEpoch!==null&&explicitEpoch!==v.start){v.key=c.key;v.start=explicitEpoch??options.now}
  element.hidden=false;element.classList.toggle('flip-cue',c.kind==='flip');
  element.dataset.support=String(c.projection.supportCount);element.dataset.method=c.projection.method;
  element.classList.toggle('measured-cue',measured);
  const svg=element.querySelector('svg'),plane=element.querySelector('.board-cue-plane');
  svg.setAttribute('viewBox',`0 0 ${options.width} ${options.height}`);
  plane.setAttribute('transform',measured?'':'matrix('+g.matrix.join(' ')+')');
  const shape=measured?projectedPath(c,g):modelPath(c,part);element.querySelector('.board-cue-footprint').setAttribute('d',shape);
  element.querySelector('.board-cue-ghost path').setAttribute('d',shape);
  const action=element.querySelector('.board-cue-action'),axis=element.querySelector('.board-cue-axis');
  const ribbon=element.querySelector('.board-cue-ribbon');
  if(measured){
   action.setAttribute('transform','');const shapes=actionShape(c,g.det);
   const project=q=>projectPoint(g.homography,q,g.width,g.height);
   axis.setAttribute('d',screenPath(shapes.axis.map(project)));
   ribbon.setAttribute('d',screenPath(shapes.arrow.map(project),true));
  }else if(c.kind==='flip'){
   action.setAttribute('transform',c.flipIndex===0?'':'matrix(0 1 1 0 0 0)');
   axis.setAttribute('d','M-.9 0H.9');
   ribbon.setAttribute('d','M-.12 .64 C-.6 .25 -.6 -.24 -.12 -.61 L-.31 -.66 L.16 -.83 L.19 -.34 L.02 -.48 C-.22 -.24 -.21 .23 .07 .46 Z');
  }else{
   action.setAttribute('transform',c.angle*g.det<0?'scale(-1 1)':'');axis.setAttribute('d','');
   ribbon.setAttribute('d','M-.56 .43 A.71 .71 0 1 1 .66 -.26 L.85 -.31 L.64 .2 L.23 -.12 L.43 -.18 A.47 .47 0 1 0 -.36 .27 Z');
  }
  const label=element.querySelector('span');label.textContent=root.AssemblyText?.english?root.AssemblyText.t(c.kind==='flip'?'cue.flip':c.kind==='translate'?'cue.move':'cue.rotate',{part:root.AssemblyText.reference(c.label)}):c.label+(c.kind==='flip'?' · 翻一下面':c.kind==='translate'?' · 拿起，移到这里':' · 转一下');
  label.style.left=Math.max(60,Math.min(options.width-60,g.center[0]))+'px';
  label.style.top=Math.max(20,Math.min(options.height-32,g.center[1]+Math.min(105,Math.max(...g.axes.map(x=>Math.hypot(...x)))*.75)))+'px';
  if(!v.frame){
   const tick=now=>{
    if(views.get(element)!==v||element.hidden)return;
    const x=v.command,m=sampleMotion(x.kind,x.angle,x.flipIndex,v.geometry.det,now-v.start,x.projection.localHalfExtent,x.translation||[0,0]);
    const reduced=typeof matchMedia==='function'&&matchMedia('(prefers-reduced-motion: reduce)').matches;
    const ghost=element.querySelector('.board-cue-ghost'),isMeasured=x.projection.method==='measured_homography';
    ghost.setAttribute('transform',isMeasured?'':'matrix('+(reduced?[1,0,0,1,0,0]:m.matrix).join(' ')+')');
    const depth=element.querySelector('.board-cue-depth');
    const solid=isMeasured&&x.kind==='flip'&&!reduced?flipSurface(x,v.geometry,m):null;
    if(isMeasured)ghost.querySelector('path').setAttribute('d',solid?solid.surface:projectedPath(x,v.geometry,reduced?[1,0,0,1,0,0]:m.matrix));
    if(depth){
     depth.style.opacity=solid?String(m.opacity*.88):'0';
     depth.querySelector('.board-cue-back').setAttribute('d',solid?.back||'');
     depth.querySelector('.board-cue-edges').setAttribute('d',solid?.edges||'');
    }
    ghost.setAttribute('data-face',reduced?'front':m.face);
    ghost.style.opacity=String(reduced?.6:m.opacity*.88);
    element.querySelector('.board-cue-footprint').style.opacity=String(reduced?.14:.12+.055*Math.sin((now-v.start)/380));
    v.frame=requestAnimationFrame(tick);
   };v.frame=requestAnimationFrame(tick);
  }
  return true;
 }
 const api={command,geometry,modelPath,sampleMotion,render,hide,projectPoint,projectedPath,actionShape,validMeasured,validTranslation,teachingProjection,liftedPoint,flipSurface};
 if(typeof module!=='undefined'&&module.exports)module.exports=api;
 root.BoardCue=api;
})(typeof window!=='undefined'?window:globalThis);

/** R91 camera adapter. Coordinates from the worker are in the detector's own image pixels. */
(function(root){
  'use strict';
  const workerURL=new URL('worker.js',document.currentScript.src);
  const SVG='http://www.w3.org/2000/svg';
  function svg(tag,attrs){
    const el=document.createElementNS(SVG,tag);
    for(const [key,val] of Object.entries(attrs))el.setAttribute(key,val);
    return el;
  }
  function outline(plane){
    const h=plane?.planeToImage;
    if(!h||h.length!==9)return '';
    function ring(points){
      const out=[];
      for(const p of points||[]){
        const w=h[6]*p.x+h[7]*p.y+h[8];
        if(!Number.isFinite(w)||w<=1e-6)return '';
        const x=(h[0]*p.x+h[1]*p.y+h[2])/w,y=(h[3]*p.x+h[4]*p.y+h[5])/w;
        if(!Number.isFinite(x)||!Number.isFinite(y))return '';
        out.push(x.toFixed(1)+' '+y.toFixed(1));
      }
      return out.length>=3?'M'+out.join('L')+'Z':'';
    }
    return ring(plane.outlineLocal)+(plane.holesLocal||[]).map(ring).join('');
  }
  function normalized(plane,w,h){
    const point=p=>({x:p.x/w,y:p.y/h});
    return {...plane,center:point(plane.center),footprint:plane.footprint.map(point),
      axes:plane.axes.map(a=>({...a,start:point(a.start),end:point(a.end)})),
      planeToImage:plane.planeToImage.map((v,i)=>i<3?v/w:i<6?v/h:v)};
  }
  function createAlignmentCamera(container, {getTask,getLanguage,onReading,onClose}){
    const layer=document.createElement('div');layer.className='alignment-camera';layer.hidden=true;
    const stage=document.createElement('div');stage.className='align-stage';
    const video=document.createElement('video');video.muted=true;video.autoplay=true;video.playsInline=true;
    const overlay=svg('svg',{'aria-hidden':'true',class:'align-graphics'});
    const teaching=document.createElement('div');teaching.hidden=true;teaching.className='align-teaching';
    const toolbar=document.createElement('div');toolbar.className='align-controls';
    const lens=document.createElement('button'),back=document.createElement('button'),precision=document.createElement('button');
    precision.type='button';precision.className='align-precision';
    const qualityNote=document.createElement('span');qualityNote.className='align-quality';qualityNote.setAttribute('role','status');
    lens.type=back.type='button';lens.hidden=true;toolbar.append(precision,lens,back);
    const loading=document.createElement('span');loading.className='align-opening';
    const legend=document.createElement('span');legend.className='align-legend';legend.hidden=true;
    stage.append(video,overlay,teaching);layer.append(stage,toolbar,loading,legend,qualityNote);container.append(layer);
    const canvas=document.createElement('canvas');
    const ctx=canvas.getContext('2d',{willReadFrequently:true});
    let active=false,version=0,reader=null,stream=null,timer=0,shutter=0,deadline=0;
    let taskId='',lenses=[],currentLens='',session='',reading=null;
    let width=0,height=0,warming=null,warmTimer=0,quality='fast',applyPrecision=null;
    function qualityLabels(){
      precision.setAttribute('aria-pressed',String(quality==='ready'||quality==='loading'));
      precision.textContent=quality==='ready'?tr('Use fast scan','切回快速识码'):
        quality==='loading'?tr('Cancel precision','取消加载'):tr('Precision · 8 MB','精细识别 · 8 MB');
      qualityNote.hidden=quality==='fast';
      layer.dataset.quality=quality;
      qualityNote.textContent=quality==='ready'?tr('Precision · OpenCV','精细识别 · OpenCV'):
        quality==='loading'?tr('Loading precision reader… Fast scan is active.','正在加载精细识别，快速识码继续运行。'):
        quality==='fallback'?tr('Precision unavailable. Using fast scan.','精细识别暂不可用，已继续快速识码。'):'';
    }
    function cancelWarmup(){
      clearTimeout(warmTimer);warmTimer=0;
      if(warming){warming.terminate();warming=null}
    }
    const tr=(en,zh)=>String(getLanguage?.()||'en').startsWith('zh')?zh:en;
    function setLanguage(){
      back.textContent=tr('Back to model','返回模型');
      lens.textContent=tr('Switch camera','切换镜头');
      loading.textContent=tr('Opening camera…','正在开启相机…');qualityLabels();
    }
    function hideGeometry(){
      root.BoardCue.hide(teaching);overlay.replaceChildren();legend.hidden=true;
    }
    function clearTimers(){
      clearTimeout(timer);clearTimeout(deadline);
      timer=shutter=deadline=0;
    }
    function stop(){
      active=false;version++;clearTimers();cancelWarmup();quality='fast';applyPrecision=null;
      reader?.terminate();reader=null;
      if(stream){stream.getTracks().forEach(track=>track.stop());stream=null}
      video.srcObject=null;taskId='';reading=null;layer.hidden=true;hideGeometry();
      width=height=0;lens.hidden=true;
    }
    function close(reason){
      stop();onClose?.(reason);
    }
    function neutral(status){
      return {status,headline:tr('Show the current parts','拍清当前组件'),
        instruction:tr('Keep their markers and the base in view.','拍清板件标签，并露出基准框。'),
        visible:false,stable:false,markerIds:[],slotTargets:[],inferredSlotTargets:[],seatingVerified:false,
        cueGeometry:null,alignedPlanes:{},width,height,taskId};
    }
    function drawGeometry(check,markers){
      const nodes=[];
      function addSlots(list,estimated){
        for(const p of list||[]){
          nodes.push(svg('circle',{cx:p.x,cy:p.y,r:Math.max(6,width*.012),
            class:estimated?'align-inferred':'align-slot'}));
        }
      }
      const projected=check.alignedPlanes||{};
      for(const plane of Object.values(projected)){
        const d=outline(plane);
        if(d&&check.stable)nodes.push(svg('path',{d,class:'align-shape', 'fill-rule':'evenodd'}));
      }
      if(check.visible&&check.status!=='need_view'){
        addSlots(check.slotTargets,false);addSlots(check.inferredSlotTargets,true);
        for(const points of [check.notchCue,check.backCue]){
          if(points?.length!==2)continue;
          const [a,b]=points,dx=b.x-a.x,dy=b.y-a.y,len=Math.hypot(dx,dy);
          if(len<1)continue;
          const ux=dx/len,uy=dy/len,size=Math.min(18,len*.3);
          nodes.push(svg('path',{d:'M'+a.x+' '+a.y+'L'+b.x+' '+b.y+
            'M'+(b.x-ux*size-uy*size*.55)+' '+(b.y-uy*size+ux*size*.55)+'L'+b.x+' '+b.y+
            'L'+(b.x-ux*size+uy*size*.55)+' '+(b.y-uy*size-ux*size*.55),class:'align-vector'}));
        }
      }
      const relevant=new Set(check.markerIds);
      for(const m of markers.filter(m=>relevant.has(m.id))){
        nodes.push(svg('polygon',{points:m.corners.map(p=>p.x+','+p.y).join(' '),class:'align-marker'}));
      }
      overlay.replaceChildren(...nodes);
      const inferred=check.inferredSlotTargets?.length>0;
      legend.hidden=!inferred||!check.visible||check.status==='need_view';
      if(inferred)legend.textContent=tr('Dashed rings · estimated slots','虚线环 · 推算孔位');
      const task=getTask(),plane=check.cueGeometry,now=performance.now();
      if(task?.kind==='prepare' && plane && check.stable){
        const e={...check,state:check.status,angle:check.angleDegrees,task:taskId,source:'local',
          cueGeometry:normalized(plane,width,height)};
        root.BoardCue.render(teaching,e,{task:taskId,parts:task.context,now,observedAt:now,
          width:stage.clientWidth,height:stage.clientHeight,scene:root.SHELF_SCENE?.parts||[]});
      }else root.BoardCue.hide(teaching);
    }
    function resize(){
      if(!active||!width||!height)return;
      const fit=Math.min(layer.clientWidth/width,layer.clientHeight/height);
      stage.style.width=width*fit+'px';stage.style.height=height*fit+'px';
      if(reading)drawGeometry(reading.check,reading.markers);
    }
    const resizeObserver=new ResizeObserver(resize);
    resizeObserver.observe(layer);
    async function start(options={}){
      stop();setLanguage();
      const owner=getTask();
      taskId=typeof owner==='string'?owner:owner?.id;
      if(!taskId)return false;
      session=String(++version)+'-'+Math.random().toString(36).slice(2);
      const ticket=version;
      active=true;layer.hidden=false;loading.hidden=false;precision.disabled=true;
      try{
        const constraints=options.deviceId?{deviceId:{exact:options.deviceId}}:{facingMode:{ideal:'environment'}};
        const opened=options.stream || await navigator.mediaDevices.getUserMedia({audio:false,video:{...constraints,width:{ideal:1280},height:{ideal:960}}});
        if(ticket!==version){opened.getTracks().forEach(t=>t.stop());return false}
        stream=opened;
        const track=stream.getVideoTracks()[0];
        if(!track)throw Error('Missing video track');
        track.addEventListener('ended',()=>{if(ticket===version)close('ended')},{once:true});
        currentLens=track.getSettings().deviceId||'';
        navigator.mediaDevices.enumerateDevices?.().then(list=>{
          if(ticket!==version)return;
          lenses=list.filter(d=>d.kind==='videoinput'&&d.deviceId);
          lens.hidden=lenses.length<2;
        }).catch(()=>{});
        video.srcObject=opened;await video.play();
        if(ticket!==version)return false;
        loading.hidden=true;precision.disabled=false;
        let slowFrames=0;
        function capture(){
          if(ticket!==version)return;
          const current=getTask();
          if((typeof current==='string'?current:current?.id)!==taskId){close('task-changed');return}
          if(video.readyState<2||!video.videoWidth){timer=setTimeout(capture,90);return}
          const scale=Math.min(1,1024/Math.max(video.videoWidth,video.videoHeight));
          const w=Math.round(video.videoWidth*scale),h=Math.round(video.videoHeight*scale);
          if(w!==width||h!==height){
            width=w;height=h;canvas.width=w;canvas.height=h;
            overlay.setAttribute('viewBox','0 0 '+w+' '+h);reading=null;hideGeometry();resize();
          }
          try{
            ctx.drawImage(video,0,0,w,h);
            const image=ctx.getImageData(0,0,w,h);
            const atMs=performance.now();shutter=atMs;
            reader.postMessage({image,taskId,lang:getLanguage?.()||'en',mode:'stream',precision:quality==='ready'?'opencv':'fast',sessionId:session,atMs,captureId:session},
              [image.data.buffer]);
          }catch{close('reader-error')}
        }
        function bindReader(next){
          if(ticket!==version){next.terminate();return}
          clearTimers();
          reader?.terminate();reader=next;reading=null;hideGeometry();slowFrames=0;
          next.onerror=()=>{if(ticket!==version||reader!==next)return;
            if(quality==='ready')fallback(true);else close('reader-error');
          };
          next.onmessage=({data})=>{
          if(ticket!==version||reader!==next||data.captureId!==session)return;
          const owner=getTask();
          if((typeof owner==='string'?owner:owner?.id)!==taskId){close('task-changed');return}
          if(data.error||!data.check){close('reader-error');return}
          const age=performance.now()-shutter;
          if(quality==='ready'){
            slowFrames=age>=900?slowFrames+1:0;
            if(data.detector!=='opencv-4.13'||slowFrames>=3){fallback(true);return}
          }
          clearTimeout(deadline);
          if(age<900){
            reading=data;drawGeometry(data.check,data.markers);
            onReading?.({...data.check,width,height,taskId,markers:data.markers,detector:data.detector,slotCandidateCount:data.slotCandidateCount});
            deadline=setTimeout(()=>{
              if(ticket!==version)return;
              reading=null;hideGeometry();onReading?.(neutral('lost'));
            },Math.max(1,900-age));
          }else{reading=null;hideGeometry();onReading?.(neutral('lost'))}
          if(ticket===version)timer=setTimeout(capture,75);
          };
          capture();
        }
        function fallback(replace){
          cancelWarmup();quality='fallback';qualityLabels();
          if(replace){onReading?.(neutral('need_view'));bindReader(new Worker(workerURL))}
        }
        applyPrecision=enabled=>{
          if(ticket!==version)return;
          if(!enabled){
            const replace=quality==='ready';cancelWarmup();quality='fast';qualityLabels();
            if(replace){onReading?.(neutral('need_view'));bindReader(new Worker(workerURL))}
            return;
          }
          if(warming||quality==='ready')return;
          // Keep fast frames flowing while the second worker fetches and initialises WASM.
          const next=new Worker(workerURL);warming=next;quality='loading';qualityLabels();
          const failed=()=>{if(ticket===version&&warming===next)fallback(false)};
          next.onerror=failed;warmTimer=setTimeout(failed,45000);
          next.onmessage=({data})=>{
            if(ticket!==version||warming!==next)return;
            if(data.error||data.type!=='precision-ready'){failed();return}
            clearTimeout(warmTimer);warmTimer=0;warming=null;quality='ready';qualityLabels();
            onReading?.(neutral('need_view'));bindReader(next);
          };
          next.postMessage({type:'prepare-precision',captureId:session});
        };
        bindReader(new Worker(workerURL));
        if(options.precision==='opencv')applyPrecision(true);
        return true;
      }catch(e){
        if(ticket===version)close(e?.name==='NotAllowedError'?'denied':'unavailable');
        return false;
      }
    }
    function switchLens(){
      if(!active||lenses.length<2)return Promise.resolve(false);
      const index=lenses.findIndex(d=>d.deviceId===currentLens);
      return start({deviceId:lenses[(index+1)%lenses.length].deviceId,precision:quality==='ready'||quality==='loading'?'opencv':'fast'});
    }
    precision.onclick=()=>applyPrecision?.(quality!=='ready'&&quality!=='loading');
    lens.onclick=switchLens;back.onclick=()=>close('user');
    document.addEventListener('visibilitychange',()=>{if(document.hidden&&active)close('hidden')});
    window.addEventListener('pagehide',stop);
    setLanguage();
    return {start,stop,switchLens,setLanguage,setPrecision(enabled){applyPrecision?.(!!enabled)},get active(){return active}};
  }
  root.createAlignmentCamera=createAlignmentCamera;
})(globalThis);
