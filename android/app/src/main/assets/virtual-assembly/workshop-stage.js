'use strict';

// Rehearsal keeps each part in one CAD world. Only the active lesson determines framing.
function createWorkshopStage(reduce) {
  const identity = [[1,0,0],[0,1,0],[0,0,1]];
  const transpose = m => m[0].map((_,i) => m.map(row => row[i]));
  const compose = (a,b) => transpose(transpose(b).map(col => mul(a,col)));
  const mix = (a,b,t) => a.map((v,i) => v+(b[i]-v)*t);
  const smooth = t => t*t*(3-2*t);
  const clamp = t => Math.min(1, Math.max(0,t));
  const center = ranges => ranges.map(([lo,hi]) => (lo+hi)/2);

  // The columns of a rotation matrix are a local frame. Interpolate its relative axis/angle.
  function interpolateRotation(a,b,t) {
    if(t<=0)return a;
    if(t>=1)return b;
    const r=compose(b,transpose(a));
    const cos=Math.min(1,Math.max(-1,(r[0][0]+r[1][1]+r[2][2]-1)/2));
    const theta=Math.acos(cos);
    if(theta<.00001)return a;
    let axis=[r[2][1]-r[1][2],r[0][2]-r[2][0],r[1][0]-r[0][1]];
    if(cos<-.99999){
      const k=[0,1,2].sort((i,j)=>r[j][j]-r[i][i])[0];
      axis=r.map((row,i)=>row[k]+(i===k?1:0));
    }
    const [x,y,z]=normalized(axis), c=Math.cos(t*theta), s=Math.sin(t*theta), v=1-c;
    return compose([[c+x*x*v,x*y*v-z*s,x*z*v+y*s],[y*x*v+z*s,c+y*y*v,y*z*v-x*s],[z*x*v-y*s,z*y*v+x*s,c+z*z*v]],a);
  }

  const rack={id:'overview',kind:'overview',context:[...partMap.keys()],members:[],
    workPose:{normalAxis:1,upSign:1},motion:{axis:'Y',dist:0}};
  const cad=new Map();
  for(const [id,part] of partMap){
    const vertices=[];
    for(let i=0;i<part.verts.length;i+=3)vertices.push(part.verts.slice(i,i+3));
    const origin=center(boundsOf(vertices)), triangles=[];
    for(let i=0;i<vertices.length;i+=3)triangles.push(vertices.slice(i,i+3).map(p=>sub(p,origin)));
    cad.set(id,{origin,mesh:meshFromTriangles(id,triangles,rgb(material[Number(id.match(/层(\d)/)?.[1])-1]||'#9da9aa'))});
  }
  gpu?.load([...cad.values()].map(p=>p.mesh));
  const orbitBasis=sceneBasis;
  yaw=.85;pitch=.50;
  let frame={points:new Map(),rotation:identity,span:520};
  let plan=null,phase='idle',timer=0,decoration=[],arrowProgress=null,lastLessonAmount=null;
  let stableOnly=false;
  const joinMs=720, holdMs=1000, fadeOutMs=160, blankMs=80, fadeInMs=200;
  const cycleMs=holdMs+fadeOutMs+blankMs+fadeInMs+joinMs;
  sceneBasis=()=>compose(orbitBasis(),frame.rotation);
  sceneScale=(w,h)=>zoom*Math.min(w,h)/frame.span;
  sceneInstances=()=>{
    const instances=[];
    for(const [id,shift] of frame.points){
      const opacity=frame.fading?.has(id)?frame.opacity:1;
      if(!stableOnly || opacity===1)instances.push({mesh:cad.get(id).mesh,shift,opacity});
    }
    return instances.concat(decoration);
  };
  // Blend two depth-tested views in premultiplied colour. A stationary piece can
  // cover the moving shelf in either view, including on the software renderer.
  const paintOpaque=drawScene,blend=document.createElement('canvas'),blendCtx=blend.getContext('2d');
  drawScene=()=>{
    if(!frame.fading?.size || frame.opacity>=1){paintOpaque();return}
    stableOnly=true;
    try{paintOpaque()}finally{stableOnly=false}
    if(frame.opacity<=0 || !cv.width || !cv.height)return;
    if(blend.width!==cv.width || blend.height!==cv.height){blend.width=cv.width;blend.height=cv.height}
    blendCtx.globalCompositeOperation='copy';
    blendCtx.globalAlpha=1-frame.opacity;
    blendCtx.drawImage(cv,0,0);
    paintOpaque();
    blendCtx.globalCompositeOperation='lighter';
    blendCtx.globalAlpha=frame.opacity;
    blendCtx.drawImage(cv,0,0);
    ctx.save();
    ctx.resetTransform();
    ctx.globalCompositeOperation='copy';
    ctx.drawImage(blend,0,0);
    ctx.restore();
  };

  function explodedOffset(id){
    const group=id.replace(/-[12]$/,''), sign=id.endsWith('-1')?1:-1;
    if(group.startsWith('层'))return [sign*24,(Number(group[1])-3)*18,0];
    if(group==='背板')return [-64,sign*15,0];
    return [-sign*20,0,group==='左侧板'?-65:65];
  }
  function makePlan(task,exploded){
    const matrix=poseFor(task),inverse=transpose(matrix);
    const solo=task.kind==='part',overview=task===rack;
    buildScene(task,matrix,overview||solo,true);
    const lessonCenter=viewCenter.slice();
    const pivot=mul(inverse,add(poseOrigin,lessonCenter));
    const travel=mul(inverse,overview||solo?[0,0,0]:motionWorld(task));
    const end=new Map(task.context.map(id=>[id,sub(cad.get(id).origin,pivot)]));
    const start=new Map([...end].map(([id,c])=>[id,task.members.includes(id)?add(c,travel):c]));
    if(exploded)for(const [id,c] of end)end.set(id,add(c,explodedOffset(id)));
    // A single framing distance per selection is independent of orbit, transient and parked parts.
    const span=(exploded?Math.max(guideDiameter*.98,510):guideDiameter*.91);
    const camera=task.kind==='install' && task.group==='背板'?Math.PI/2:
      task.kind==='install'?Math.PI-.65:overview?0:-.95;
    const c=Math.cos(camera),s=Math.sin(camera);
    const rotation=compose([[c,0,-s],[0,1,0],[s,0,c]],matrix);
    // Arrow/slot triangles are authored in the local tabletop frame; draw them with the same CAD camera.
    const toWorld=mesh=>{
      const triangles=mesh.triangles.map(tr=>tr.map(p=>mul(inverse,sub(p,lessonCenter))));
      const model=meshFromTriangles(mesh.id,triangles,mesh.color);
      gpu?.update(model);
      return {mesh:model,shift:[0,0,0]};
    };
    viewCenter=[0,0,0];
    return {rotation,span,start,end,movers:new Set(task.members),toWorld,marks:installSlotMarks.map(toWorld),
      hasMotion:!overview&&!solo&&Math.hypot(...travel)>0};
  }
  function drawLesson(progress,opacity=1){
    if(progress===lastLessonAmount && (frame.opacity??1)===opacity)return false;
    lastLessonAmount=amount=progress;
    const points=new Map();
    for(const [id,p] of plan.end)points.set(id,mix(plan.start.get(id),p,progress));
    frame={points,rotation:plan.rotation,span:plan.span,opacity,fading:plan.movers};
    const arrows=opacity===1 && progress<1?progress:null;
    if(arrows!==arrowProgress){
      decoration=arrows===null?[]:[...plan.marks,...makeMotionArrows(progress).map(plan.toWorld)];
      arrowProgress=arrows;
    }
    return true;
  }
  function parked(id,at,span,basis){
    const right=basis[0], projected=dot(at,right);
    const sign=Math.abs(projected)>12?Math.sign(projected):(id.endsWith('-1')?-1:1);
    const width=Math.max(1,cv.clientWidth/Math.max(1,Math.min(cv.clientWidth,cv.clientHeight)));
    return add(at,times(right,sign*(span*.7*width/zoom+210)-projected));
  }
  function move(points,targets,t){
    const result=new Map();
    for(const [id,end] of targets)result.set(id,mix(points.get(id),end,t));
    return result;
  }
  function select(task,exploded=false){
    cancelAnimationFrame(timer);
    const from=frame,basis=sceneBasis(),userZoom=zoom;
    plan=makePlan(task||rack,exploded);zoom=userZoom;
    amount=plan.hasMotion?0:1;decoration=[];arrowProgress=null;lastLessonAmount=null;
    const initial=!from.points.size;
    const goal=plan.hasMotion?plan.start:plan.end;
    if(reduce){
      frame={points:plan.end,rotation:plan.rotation,span:plan.span};
      amount=1;phase='settled';paintScene();return;
    }
    const exitDest=new Map(),entrance=new Map();
    for(const [id,pos] of from.points){
      exitDest.set(id,goal.has(id)?pos:parked(id,pos,from.span,basis));
    }
    for(const [id,end] of goal){
      entrance.set(id,initial?plan.start.get(id):(from.points.get(id)||parked(id,end,from.span,basis)));
    }
    const retiring=[...from.points.keys()].some(id=>!goal.has(id));
    const exitMs=retiring?245:0,stageMs=initial?570:390;
    const began=performance.now(),enterAt=exitMs+stageMs;
    function sampleLesson(time){
      if(time<joinMs)return drawLesson(smooth(time/joinMs));
      const t=(time-joinMs)%cycleMs;
      if(t<holdMs)return drawLesson(1);
      const reset=t-holdMs;
      if(reset<fadeOutMs)return drawLesson(1,1-smooth(reset/fadeOutMs));
      if(reset<fadeOutMs+blankMs)return drawLesson(0,0);
      const restoring=reset-fadeOutMs-blankMs;
      const progress=restoring/(fadeInMs+joinMs);
      return drawLesson(smooth(progress),smooth(clamp(restoring/fadeInMs)));
    }
    function tick(now){
      const elapsed=now-began;
      let changed=true;
      if(elapsed<exitMs){
        phase='retire';frame={...from,points:move(from.points,exitDest,smooth(clamp(elapsed/exitMs)))};
      }else if(elapsed<enterAt){
        phase='stage';
        const t=smooth(clamp((elapsed-exitMs)/stageMs));
        frame={points:move(entrance,goal,t),rotation:interpolateRotation(initial?plan.rotation:from.rotation,plan.rotation,t),
          span:initial?plan.span:from.span+(plan.span-from.span)*t,
          fading:from.fading,opacity:(from.opacity??1)+(1-(from.opacity??1))*t};
      }else if(plan.hasMotion){
        phase='lesson';changed=sampleLesson(elapsed-enterAt);
      }else{
        phase='settled';amount=1;decoration=[];
        frame={points:plan.end,rotation:plan.rotation,span:plan.span};
      }
      if(changed)paintScene();
      timer=phase==='settled'?0:requestAnimationFrame(tick);
    }
    tick(began);
  }
  return {select};
}
