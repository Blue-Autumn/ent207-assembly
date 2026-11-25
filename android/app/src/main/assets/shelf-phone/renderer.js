'use strict';
// Mobile renderer. Tutorial coordinates and motion remain unchanged; only the view is transformed.
const cv=document.getElementById('cv'),ctx=cv.getContext('2d'),partMap=new Map(SHELF_SCENE.parts.map(p=>[p.id,p]));
let renderTask=null,drawParts=[],arrowParts=[],tablePart=null,poseMatrix=null,poseOrigin=null,viewCenter=[0,30,0],extent=350,amount=0;
let finderDiameter=350,guideDiameter=350,sceneOverview=false,fitRadius=175,fitHalfHeight=175;
let arrowPlan=null,lastArrowAmount=null;
let orientationDemo=null;
let installCorrection=null,installSlotMarks=[];
let yaw=.63,pitch=.58,zoom=1,pointer=null,raf=0;
const dot=(a,b)=>a.reduce((s,x,k)=>s+x*b[k],0),mul=(M,v)=>M.map(r=>dot(r,v));
const sub=(a,b)=>a.map((x,k)=>x-b[k]),add=(a,b)=>a.map((x,k)=>x+b[k]),times=(a,s)=>a.map(x=>x*s);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const normalized=v=>times(v,1/(Math.hypot(...v)||1));
const material=['#a98bc9','#65a9c9','#dcb99f','#d9bd50','#8db78b'];
const rgb=hex=>hex.match(/\w\w/g).map(x=>parseInt(x,16)/255);
function poseFor(task){const n=task.workPose.normalAxis,sign=task.workPose.upSign,[u,v]=[0,1,2].filter(k=>k!==n);
 const M=[[0,0,0],[0,0,0],[0,0,0]];M[0][u]=1;M[1][n]=sign;M[2][v]=(n===1?1:-1)*sign;return M}
function motionWorld(task,k=1){const v=[0,0,0];v['XYZ'.indexOf(task.motion.axis)]=task.motion.dist*k;return mul(poseFor(task),v)}
function boundsOf(points){const ranges=[[Infinity,-Infinity],[Infinity,-Infinity],[Infinity,-Infinity]];
 for(const p of points)for(let k=0;k<3;k++){ranges[k][0]=Math.min(ranges[k][0],p[k]);ranges[k][1]=Math.max(ranges[k][1],p[k])}return ranges}
function meshFromTriangles(id,triangles,color,moving=false){const data=[];
 for(const tr of triangles){const n=normalized(cross(sub(tr[1],tr[0]),sub(tr[2],tr[0])));for(const p of tr)data.push(...p,...n)}
 return {id,triangles,data,color,moving};}

// In the assembled CAD the lowest vertices of each upright half belong to its insertion tab.
// Trace that tab on the upper face of the receiver, including when a hand covers the real hole.
function installSlots(task){
 if(task.kind!=='install'||!/^层[1-5]板$/.test(task.group))return [];
 const base=drawParts.filter(m=>m.id.startsWith('左侧板-'));
 if(!base.length)return [];
 const y=Math.max(...base.flatMap(m=>m.triangles.flat().map(p=>p[1])))+.45;
 return drawParts.filter(m=>m.moving).map(mesh=>{
  const vertices=mesh.triangles.flat();
  const low=Math.min(...vertices.map(p=>p[1]));
  const [[left,right],,[near,far]]=boundsOf(vertices.filter(p=>p[1]<low+.25));
  const quad=(a,b,c,d)=>{const points=[[a,y,b],[c,y,b],[c,y,d],[a,y,d]];
   return [[points[0],points[1],points[2]],[points[0],points[2],points[3]]]};
  const w=2.6;
  const rim=[...quad(left-w,near-w,right+w,near),...quad(left-w,far,right+w,far+w),
   ...quad(left-w,near,left,far),...quad(right,near,right+w,far)];
  return meshFromTriangles('slot:'+mesh.id,rim,rgb('#f4ab32'));
 });
}
function setInstallMotion(event){
 const rotations={install_base_heading:[1,Math.PI],install_notch_hint:[1,Math.PI],install_base_flip:[0,Math.PI],install_stand:[0,Math.PI/2],install_turn:[1,Math.PI]};
 if(renderTask?.kind!=='install'||!event?.stable||!Object.hasOwn(rotations,event.state)||
    (event.state==='install_notch_hint' && event.installMotion!=='turn_panel_upright')){installCorrection=null;return}
 const one=['turn_one','flip_one'].includes(event.installMotion);
 const subject=event.part||renderTask.members[0],group=subject.replace(/-[12]$/,'');
 const moving=drawParts.filter(m=>one?m.id===subject:m.id.replace(/-[12]$/,'')===group);
 if(!moving.length){installCorrection=null;return}
 const key=event.state+'|'+moving.map(m=>m.id).join(',')+'|'+(event.installMotion||'');
 if(installCorrection?.key===key){
  if(event.state==='install_base_heading' && Number.isFinite(event.angle)){
   const angle=event.angle*Math.PI/180;
   if(Math.abs(angle-installCorrection.turn[1])>.12 && !(Math.abs(angle)>2.8 && Math.abs(installCorrection.turn[1])>2.8)){
    installCorrection.turn[1]=angle;installCorrection.amount=null;
   }
  }
  return;
 }
 const triangles=moving.flatMap(m=>m.triangles),ranges=boundsOf(triangles.flat());
 const center=ranges.map(([lo,hi])=>(lo+hi)/2);
 const local=triangles.map(tr=>tr.map(p=>sub(p,center)));
 // Looking down on the table, a clockwise movement in the image corresponds to
 // negative rotation about the upward world axis. The loop renders the inverse start pose.
 const back=renderTask.group==='背板';
 const backAxis={turn_back_ends:1,turn_over_end:2,turn_back_in_plane:0}[event.installMotion];
 const turn=event.state==='install_base_heading'?[1,(event.angle||180)*Math.PI/180]:
  back && backAxis!==undefined?[backAxis,Math.PI]:rotations[event.state];
 // One marked CAD corner makes a half turn readable even if the two ends look alike.
 const corner=back?local.flat().reduce((a,b)=>b[1]+b[2]>a[1]+a[2]?b:a):null;
 installCorrection={key,state:event.state,turn,triangles:local,color:moving[0].color,
  radius:Math.max(1,...local.flat().map(p=>Math.hypot(...p))),corner,amount:null,mesh:null};
}
function correctionRotation([axis,angle],progress){
 const radians=angle*(1-progress),c=Math.cos(radians),s=Math.sin(radians);
 return axis===0?([x,y,z])=>[x,c*y-s*z,s*y+c*z]:
  axis===1?([x,y,z])=>[c*x+s*z,y,-s*x+c*z]:([x,y,z])=>[c*x-s*y,s*x+c*y,z];
}
function correctionInstance(){
 const demo=installCorrection;
 if(demo.amount!==amount){
  const rotate=correctionRotation(demo.turn,amount);
  demo.mesh=meshFromTriangles('install-correction',demo.triangles.map(tr=>tr.map(rotate)),demo.color);
  demo.amount=amount;gpu?.update(demo.mesh);
 }
 return {mesh:demo.mesh,shift:[0,0,0]};
}

// Closed cylinder + cone. Arrow geometry participates in exactly the same depth test as the parts.
function arrowMesh(id,tail,tip,radius,crossScale=[1,1,1]){const axis=sub(tip,tail),length=Math.hypot(...axis),d=normalized(axis);
 const u=normalized(cross(d,Math.abs(d[1])<.8?[0,1,0]:[1,0,0])),v=cross(d,u);
 const headLength=Math.min(length*.36,radius*4.7),headRadius=radius*2.35,neck=add(tip,times(d,-headLength)),triangles=[];
 const ring=(center,r,i)=>add(center,add(times(u,Math.cos(i*Math.PI/6)*r),times(v,Math.sin(i*Math.PI/6)*r)).map((x,k)=>x*crossScale[k]));
 for(let i=0;i<12;i++){
  const a=ring(tail,radius,i),b=ring(tail,radius,i+1),c=ring(neck,radius,i),e=ring(neck,radius,i+1);
  const h=ring(neck,headRadius,i),j=ring(neck,headRadius,i+1);
  triangles.push([a,b,c],[b,e,c],[tail,b,a],[h,j,tip],[neck,j,h]);
 }
 return meshFromTriangles(id,triangles,rgb('#bd671e'));
}
function projectedLeading(triangles,axis,sign,perp,point){let leading=Infinity;
 for(const tr of triangles){const [a,b,c]=tr.map(p=>[p[perp[0]],p[perp[1]]]),x=point[perp[0]],y=point[perp[1]];
  const d=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(d)<1e-8)continue;
  const wa=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/d;
  const wb=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/d,wc=1-wa-wb;
  if(Math.min(wa,wb,wc)<-1e-7)continue;
  leading=Math.min(leading,sign*(wa*tr[0][axis]+wb*tr[1][axis]+wc*tr[2][axis]));
 }return Number.isFinite(leading)?leading:null;
}
function arrowProfile(triangles,axis,sign,perp,anchor,radius){const scale=[1,1,1],head=radius*2.35;
 for(const k of perp){let r=head;for(let attempt=0;attempt<35;attempt++){
   const a=anchor.slice(),b=anchor.slice();a[k]+=r;b[k]-=r;
   if(projectedLeading(triangles,axis,sign,perp,a)!==null&&projectedLeading(triangles,axis,sign,perp,b)!==null)break;r*=.75;
  }scale[k]=r/head}
 // Thin standing boards need a narrow elliptical section. Sample both rings and their interiors
 // so a frame's opening is not crossed by a nominally in-bounds arrow head.
 let leading=anchor[axis]*sign;
 for(let attempt=0;attempt<35;attempt++){let inside=true,minimum=Infinity;
  for(const fraction of [.25,.5,.75,1])for(let i=0;i<24;i++){
   const p=anchor.slice();p[perp[0]]+=Math.cos(i*Math.PI/12)*head*scale[perp[0]]*fraction;
   p[perp[1]]+=Math.sin(i*Math.PI/12)*head*scale[perp[1]]*fraction;
   const local=projectedLeading(triangles,axis,sign,perp,p);if(local===null)inside=false;else minimum=Math.min(minimum,local);
  }
  if(inside){leading=Math.min(leading,minimum);break}for(const k of perp)scale[k]*=.8;
 }
 anchor[axis]=leading*sign;return scale;
}
function makeArrowPlan(travel){if(sceneOverview||Math.hypot(...travel)<1e-6)return null;
 const triangles=drawParts.filter(m=>m.moving).flatMap(m=>m.triangles);if(!triangles.length)return null;
 const ranges=boundsOf(triangles.flat()),axis=travel.map(Math.abs).indexOf(Math.max(...travel.map(Math.abs))),sign=Math.sign(travel[axis]);
 const perp=[0,1,2].filter(k=>k!==axis),side=perp.slice().sort((a,b)=>(ranges[b][1]-ranges[b][0])-(ranges[a][1]-ranges[a][0]))[0],other=perp.find(k=>k!==side);
 const center=ranges.map(r=>(r[0]+r[1])/2),span=ranges[side][1]-ranges[side][0],otherSpan=ranges[other][1]-ranges[other][0];
 const radius=Math.max(2.8,Math.min(5,span*.018)),length=Math.hypot(...travel),gap=Math.min(2,length*.025);
 const anchors=[-1,1].map(direction=>{
  const desired=center.slice();desired[side]+=direction*span*.22;
  // The middle of a frame may be a hole. Keep the desired lateral spacing and search inwards
  // on actual projected triangles, rather than putting a direction arrow into empty space.
  for(const lateral of [0,-.04,.04,-.08,.08])for(const across of [0,-.12,.12,-.24,.24,-.36,.36,-.43,.43]){
   const p=desired.slice();p[side]+=lateral*span;p[other]+=across*otherSpan;
   const lead=projectedLeading(triangles,axis,sign,perp,p);if(lead!==null){p[axis]=lead*sign;return p}
  }
  let best=null,cost=Infinity;for(const tr of triangles){const p=[0,1,2].map(k=>(tr[0][k]+tr[1][k]+tr[2][k])/3);
   const lead=projectedLeading([tr],axis,sign,perp,p);if(lead===null)continue;
   const distance=((p[side]-desired[side])/Math.max(1,span))**2+((p[other]-desired[other])/Math.max(1,otherSpan))**2;
   if(distance<cost){cost=distance;p[axis]=lead*sign;best=p}}
  return best;
 }).filter(Boolean);
 const profiles=anchors.map(anchor=>arrowProfile(triangles,axis,sign,perp,anchor,radius));
 return {axis,sign,perp,side,span,triangles,anchors,profiles,radius,length,gap};
}
function makeMotionArrows(progress){if(!arrowPlan)return [];const p=arrowPlan,remaining=p.length*(1-Math.max(0,Math.min(1,progress))),length=remaining-2*p.gap;
 if(length<2)return [];
 const radius=p.radius*Math.min(1,length/(p.radius*5));
 return p.anchors.map((anchor,i)=>{const tail=anchor.slice(),tip=anchor.slice();
  // Both ends are in the remaining gap: never through or above the moving leading face.
  tail[p.axis]+=p.sign*(remaining-p.gap);tip[p.axis]+=p.sign*p.gap;
  return arrowMesh('direction-'+i,tail,tip,radius,p.profiles[i]);
 });
}
function updateMotionArrows(progress){if(lastArrowAmount===progress)return;lastArrowAmount=progress;
 arrowParts=makeMotionArrows(progress);for(const mesh of arrowParts)gpu?.update(mesh);
}

function createGpu(){const surface=document.createElement('canvas'),g=surface.getContext('webgl',{alpha:true,antialias:true,preserveDrawingBuffer:true});if(!g)return null;
 const pr=g.createProgram();for(const [type,src] of [[g.VERTEX_SHADER,'attribute vec3 p; attribute vec3 n; uniform vec3 shift,rx,ry,rz,color; uniform vec2 scale; varying vec3 c; void main(){vec3 q=p+shift;gl_Position=vec4(dot(q,rx)*scale.x,dot(q,ry)*scale.y,-dot(q,rz)/5000.,1.);c=color*(.7+.27*abs(dot(n,normalize(vec3(.3,.9,.4)))));}'],[g.FRAGMENT_SHADER,'precision mediump float; varying vec3 c; void main(){gl_FragColor=vec4(c,1.);}']]){
  const sh=g.createShader(type);g.shaderSource(sh,src);g.compileShader(sh);if(!g.getShaderParameter(sh,g.COMPILE_STATUS))return null;g.attachShader(pr,sh)}
 g.linkProgram(pr);if(!g.getProgramParameter(pr,g.LINK_STATUS))return null;g.useProgram(pr);
 const U=Object.fromEntries(['shift','rx','ry','rz','color','scale'].map(n=>[n,g.getUniformLocation(pr,n)])),ap=g.getAttribLocation(pr,'p'),an=g.getAttribLocation(pr,'n');
 g.enableVertexAttribArray(ap);g.enableVertexAttribArray(an);const buffers=new Map();let lost=false;
 surface.addEventListener('webglcontextlost',e=>{e.preventDefault();lost=true;paintScene()});
 return {surface,ready:()=>!lost&&!g.isContextLost(),load(models){if(lost||g.isContextLost())return;for(const b of buffers.values())g.deleteBuffer(b);buffers.clear();
  for(const m of models){const b=g.createBuffer();buffers.set(m.id,b);g.bindBuffer(g.ARRAY_BUFFER,b);g.bufferData(g.ARRAY_BUFFER,new Float32Array(m.data),g.STATIC_DRAW)}},
 update(m){if(lost||g.isContextLost())return;let b=buffers.get(m.id);if(!b){b=g.createBuffer();buffers.set(m.id,b)}g.bindBuffer(g.ARRAY_BUFFER,b);g.bufferData(g.ARRAY_BUFFER,new Float32Array(m.data),g.DYNAMIC_DRAW)},
 begin(w,h,B,scale){if(surface.width!==w||surface.height!==h){surface.width=w;surface.height=h}g.viewport(0,0,w,h);g.clearColor(0,0,0,0);g.clearDepth(1);g.depthMask(true);g.clear(g.COLOR_BUFFER_BIT|g.DEPTH_BUFFER_BIT);g.enable(g.DEPTH_TEST);g.depthFunc(g.LEQUAL);g.disable(g.BLEND);
  ['rx','ry','rz'].forEach((k,i)=>g.uniform3fv(U[k],B[i]));g.uniform2f(U.scale,...scale)},
 mesh(m,shift){g.bindBuffer(g.ARRAY_BUFFER,buffers.get(m.id));g.vertexAttribPointer(ap,3,g.FLOAT,false,24,0);g.vertexAttribPointer(an,3,g.FLOAT,false,24,12);g.uniform3fv(U.shift,shift);g.uniform3fv(U.color,m.color);g.drawArrays(g.TRIANGLES,0,m.data.length/6)}};
}
const gpu=createGpu();

// Pixel-depth fallback rather than painter sorting: large board triangles can correctly hide arrows.
const softwareSurface=document.createElement('canvas'),softwareContext=softwareSurface.getContext('2d');
let softwareDepth=null,softwarePixels=null;
function softwareScene(instances,w,h,B,scale){const ratio=Math.min(1,600/Math.max(w,h)),sw=Math.max(1,Math.round(w*ratio)),sh=Math.max(1,Math.round(h*ratio));
 if(softwareSurface.width!==sw||softwareSurface.height!==sh||!softwarePixels){softwareSurface.width=sw;softwareSurface.height=sh;softwarePixels=softwareContext.createImageData(sw,sh);softwareDepth=new Float32Array(sw*sh)}
 softwareDepth.fill(-Infinity);softwarePixels.data.fill(0);const pixels=softwarePixels.data;
 const project=p=>{const q=sub(p,orientationDemo||installCorrection?[0,0,0]:viewCenter);return [sw/2+dot(q,B[0])*scale*ratio,sh/2-dot(q,B[1])*scale*ratio,dot(q,B[2])]};
 for(const {mesh,shift} of instances)for(const tr of mesh.triangles){
  const p=tr.map(q=>project(add(q,shift))),[a,b,c]=p;
  const denominator=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(denominator)<1e-8)continue;
  const minX=Math.max(0,Math.floor(Math.min(a[0],b[0],c[0]))),maxX=Math.min(sw-1,Math.ceil(Math.max(a[0],b[0],c[0])));
  const minY=Math.max(0,Math.floor(Math.min(a[1],b[1],c[1]))),maxY=Math.min(sh-1,Math.ceil(Math.max(a[1],b[1],c[1])));
  const n=normalized(cross(sub(tr[1],tr[0]),sub(tr[2],tr[0]))),light=.7+.27*Math.abs(dot(n,normalized([.3,.9,.4]))),color=mesh.color.map(x=>Math.round(x*light*255));
  for(let y=minY;y<=maxY;y++)for(let x=minX;x<=maxX;x++){
   const px=x+.5,py=y+.5,wa=((b[1]-c[1])*(px-c[0])+(c[0]-b[0])*(py-c[1]))/denominator;
   const wb=((c[1]-a[1])*(px-c[0])+(a[0]-c[0])*(py-c[1]))/denominator,wc=1-wa-wb;
   if(wa<0||wb<0||wc<0)continue;const z=wa*a[2]+wb*b[2]+wc*c[2],index=y*sw+x;
   if(z<softwareDepth[index])continue;softwareDepth[index]=z;const k=index*4;pixels[k]=color[0];pixels[k+1]=color[1];pixels[k+2]=color[2];pixels[k+3]=255;
  }
 }
 softwareContext.putImageData(softwarePixels,0,0);ctx.drawImage(softwareSurface,0,0,w,h);
}

function buildScene(task,M,overview,prepareOnly=false){renderTask=task;sceneOverview=overview;amount=overview?1:0;poseMatrix=M;zoom=1;
 installCorrection=null;
 const vertices=task.context.flatMap(id=>{const a=partMap.get(id).verts;return Array.from({length:a.length/3},(_,i)=>mul(M,a.slice(i*3,i*3+3)))}),ranges=boundsOf(vertices);
 poseOrigin=[(ranges[0][0]+ranges[0][1])/2,ranges[1][0],(ranges[2][0]+ranges[2][1])/2];
 drawParts=task.context.map(id=>{const p=partMap.get(id),triangles=[];
  for(let i=0;i<p.verts.length;i+=9)triangles.push([0,3,6].map(j=>sub(mul(M,p.verts.slice(i+j,i+j+3)),poseOrigin)));
  return meshFromTriangles(id,triangles,rgb(material[Number(id.match(/层(\d)/)?.[1])-1]||'#9da9aa'),task.members.includes(id));
 });
 const travel=overview?[0,0,0]:motionWorld(task);arrowPlan=makeArrowPlan(travel);arrowParts=makeMotionArrows(0);lastArrowAmount=0;
 const envelope=drawParts.flatMap(m=>m.triangles.flat().flatMap(p=>m.moving?[p,add(p,travel)]:[p]));
 for(const arrow of arrowParts)envelope.push(...arrow.triangles.flat());
 const fit=boundsOf(envelope);viewCenter=fit.map(r=>(r[0]+r[1])/2);
 fitRadius=Math.max(1,...envelope.map(p=>Math.hypot(p[0]-viewCenter[0],p[2]-viewCenter[2])));
 fitHalfHeight=Math.max(1,...envelope.map(p=>Math.abs(p[1]-viewCenter[1])));
 finderDiameter=Math.hypot(...ranges.map(r=>r[1]-r[0]));guideDiameter=Math.hypot(...fit.map(r=>r[1]-r[0]));
 extent=Math.max(100,...fit.map(r=>r[1]-r[0]));
 const r=Math.max(extent*.8,fitRadius*1.35),y=-.8,table=[[-r,y,-r],[r,y,-r],[r,y,r],[-r,y,r]];
 tablePart=meshFromTriangles('work-table',[[table[0],table[1],table[2]],[table[0],table[2],table[3]]],rgb('#e4ddd1'));
 installSlotMarks=installSlots(task);if(prepareOnly)return;gpu?.load([...drawParts,...arrowParts,...installSlotMarks,tablePart]);paintScene();
}
function configureScene(task){dropOrientationDemo(false);
 if(task.kind==='install'&&task.members.length){
  // The last insertion approaches the -X end. Look at the whole rack from that side.
  const back=task.group==='背板';
  yaw=Math.PI+.26+(back?Math.PI/2:0);pitch=back ? .31 : .58;
 }
 buildScene(task,poseFor(task),false);
}
function configureOverview(){
 dropOrientationDemo(false);
 // Source assembly is already upright: Y is height; layer 1 is below layers 2..5.
 const task={id:'overview',kind:'overview',context:SHELF_SCENE.parts.map(p=>p.id),members:[],motion:{axis:'Y',dist:0},workPose:{normalAxis:1,upSign:1}};
 yaw=1.02;pitch=.30;buildScene(task,[[1,0,0],[0,1,0],[0,0,1]],true);
}
function sceneBasis(){if(orientationDemo)return orientationDemo.basis;return [[Math.cos(yaw),0,-Math.sin(yaw)],[-Math.sin(yaw)*Math.sin(pitch),Math.cos(pitch),-Math.cos(yaw)*Math.sin(pitch)],[Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)]]}
function sceneScale(w,h){
 if(window.RowLesson?.active())return window.RowLesson.fit(w,h);
 if(orientationDemo)return zoom*Math.min(w*.86/(2*orientationDemo.halfWidth),h*.84/(2*orientationDemo.halfHeight));
 if(installCorrection)return Math.min(w,h)*.92/(2*installCorrection.radius);
 // A yaw-independent cylinder bounds every azimuth, so a full orbit never breathes or clips.
 const halfHeight=fitHalfHeight*Math.abs(Math.cos(pitch))+fitRadius*Math.abs(Math.sin(pitch));
 return zoom*Math.min(w*.92/(2*fitRadius),h*.92/(2*Math.max(1,halfHeight)));
}
function sceneInstances(){if(orientationDemo)return [{mesh:orientationDemo.mesh,shift:[0,0,0]}];
 const teachingParts=window.RowLesson?.instances();if(teachingParts)return teachingParts;
 if(installCorrection)return [correctionInstance()];
 const travel=sceneOverview?[0,0,0]:motionWorld(renderTask,1-amount),instances=drawParts.map(mesh=>({mesh,shift:mesh.moving?travel:[0,0,0]}));
 updateMotionArrows(sceneOverview?1:amount);
 if(!sceneOverview&&window.uiTable!==false)instances.unshift({mesh:tablePart,shift:[0,0,0]});
 if(!sceneOverview&&!window.finderModel)for(const mesh of [...installSlotMarks,...arrowParts])instances.push({mesh,shift:[0,0,0]});return instances;
}
function drawScene(){if(!renderTask)return;const w=cv.clientWidth,h=cv.clientHeight,dpr=Math.min(devicePixelRatio||1,2);if(!w||!h)return;
 const pw=Math.round(w*dpr),ph=Math.round(h*dpr);if(cv.width!==pw||cv.height!==ph){cv.width=pw;cv.height=ph}
 ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,w,h);if(window.sceneSurface!=='rgba(0,0,0,0)'&&(!window.finderModel||sceneOverview||orientationDemo)){
  const wash=ctx.createRadialGradient(w*.5,h*.62,0,w*.5,h*.62,Math.max(w,h)*.65);
  wash.addColorStop(0,window.sceneSurface||'#f8f7f1');wash.addColorStop(1,'#ffffff');ctx.fillStyle=wash;ctx.fillRect(0,0,w,h);
 }
 const B=sceneBasis(),scale=sceneScale(w,h),instances=sceneInstances();
 if(gpu?.ready()){gpu.begin(cv.width,cv.height,B,[2*scale/w,2*scale/h]);for(const {mesh,shift} of instances)gpu.mesh(mesh,sub(shift,orientationDemo||installCorrection?[0,0,0]:viewCenter));ctx.drawImage(gpu.surface,0,0,w,h)}
 else softwareScene(instances,w,h,B,scale);
 if(orientationDemo)paintOrientationFace(w,h);
 window.RowLesson?.annotate(w,h,B,scale);
 if(installCorrection?.corner){
  const tip=correctionRotation(installCorrection.turn,amount)(installCorrection.corner);
  const x=w/2+dot(tip,B[0])*scale,y=h/2-dot(tip,B[1])*scale;
  ctx.save();ctx.fillStyle='#D08224';ctx.strokeStyle='white';ctx.lineWidth=2;
  ctx.beginPath();ctx.arc(x,y,Math.max(3,Math.min(6,w*.026)),0,Math.PI*2);ctx.fill();ctx.stroke();ctx.restore();
 }
}
function paintScene(){if(!raf)raf=requestAnimationFrame(()=>{raf=0;drawScene()})}
function orientationDemoActive(){return orientationDemo!==null}
function dropOrientationDemo(restore){const previous=orientationDemo?.previous;orientationDemo=null;cv.style.opacity='1';
 if(restore&&previous){yaw=previous.yaw;pitch=previous.pitch;buildScene(previous.task,previous.matrix,previous.overview);zoom=previous.zoom;amount=previous.amount;paintScene()}}
function clearOrientationDemo(){dropOrientationDemo(true)}
function configureOrientationDemo(localOrientation){
 const math=window.AssemblyOrientationPreview;if(!math||!renderTask)return false;
 const selected=math.select(localOrientation);if(!selected||!partMap.has(selected.part))return false;
 const part=partMap.get(selected.part),observation=(localOrientation.parts||[]).find(p=>p.part===part.id);
 if(!observation||!['A','B'].includes(observation.face))return false;
 dropOrientationDemo(true);
 const previous={task:renderTask,matrix:poseMatrix,overview:sceneOverview,yaw,pitch,zoom,amount};
 const vertices=Array.from({length:part.verts.length/3},(_,i)=>part.verts.slice(3*i,3*i+3)),ranges=boundsOf(vertices),center=ranges.map(r=>(r[0]+r[1])/2);
 const triangles=[];for(let i=0;i<vertices.length;i+=3)triangles.push(vertices.slice(i,i+3).map(p=>sub(p,center)));
 const projected=math.basisFromObservation(observation,part.axis),basis=projected.basis;
 const axis=selected.kind==='flip'?basis[1]:basis[2],angle=selected.kind==='flip'?Math.PI:-selected.degrees*Math.PI/180;
 // A fixed envelope for the whole turn keeps the large single part from breathing or clipping.
 const centered=triangles.flat(),radius=Math.max(1,...centered.map(p=>Math.hypot(...p)));
 const halfWidth=selected.kind==='flip'?Math.max(1,...centered.map(p=>Math.hypot(dot(p,basis[0]),dot(p,basis[2])))):radius;
 const halfHeight=selected.kind==='flip'?Math.max(1,...centered.map(p=>Math.abs(dot(p,basis[1])))):radius;
 orientationDemo={...selected,partAxis:part.axis,face:observation.face,previous,triangles,basis,axis,angle,quality:projected.quality,
  radius,halfWidth,halfHeight,color:rgb(material[Number(part.id.match(/层(\d)/)?.[1])-1]||'#9da9aa'),mesh:null,progress:0};
 zoom=1;pointer=null;advanceOrientationDemo(0);return true;
}
function advanceOrientationDemo(elapsedMs){if(!orientationDemo)return false;
 const d=orientationDemo,math=window.AssemblyOrientationPreview,frame=math.sample(elapsedMs);d.progress=frame.amount;d.stage=frame.stage;
 const triangles=d.triangles.map(tr=>tr.map(p=>math.rotate(p,d.axis,d.angle*frame.amount)));
 d.mesh=meshFromTriangles('orientation-demo:'+d.part,triangles,d.color);gpu?.update(d.mesh);
 cv.style.opacity=String(frame.opacity);paintScene();return true;
}
function paintOrientationFace(w,h){const d=orientationDemo,n=[0,0,0];n[d.partAxis]=1;
 const normal=window.AssemblyOrientationPreview.rotate(n,d.axis,d.angle*d.progress),facing=dot(normal,d.basis[2]);
 if(Math.abs(facing)<.14)return;
 ctx.save();ctx.globalAlpha=Math.min(1,Math.abs(facing)*2.4);ctx.textAlign='center';ctx.textBaseline='middle';
 const size=Math.max(23,Math.min(34,w*.075)),text=AssemblyText.t('canvas.face',{face:facing>0?'A':'B'});ctx.font='700 '+size+'px sans-serif';
 const width=ctx.measureText(text).width+size*.8,height=size*1.6;
 ctx.fillStyle='rgba(255,255,255,.90)';ctx.beginPath();if(ctx.roundRect)ctx.roundRect(w/2-width/2,h/2-height/2,width,height,9);else ctx.rect(w/2-width/2,h/2-height/2,width,height);ctx.fill();
 ctx.fillStyle='#374256';ctx.fillText(text,w/2,h/2);ctx.restore();
}
cv.onpointerdown=e=>{if(orientationDemo)return;pointer={x:e.offsetX,y:e.offsetY,yaw,pitch};cv.setPointerCapture(e.pointerId)};
cv.onpointermove=e=>{if(!pointer)return;yaw=pointer.yaw-(e.offsetX-pointer.x)*.009;pitch=Math.max(.1,Math.min(1.5,pointer.pitch+(e.offsetY-pointer.y)*.008));paintScene()};cv.onpointerup=cv.onpointercancel=()=>pointer=null;
cv.onwheel=e=>{e.preventDefault();zoom=Math.min(3,Math.max(.5,zoom*Math.exp(-e.deltaY*.001)));paintScene()};new ResizeObserver(paintScene).observe(cv);
