'use strict';
(() => {
  const query=new URLSearchParams(location.search);
  const mode=['correct','reverse','wrong'].includes(query.get('case'))?query.get('case'):'correct';
  const requested=query.get('animate')==='1';
  const reduce=matchMedia('(prefers-reduced-motion: reduce)').matches;
  const tr=(en,zh)=>AssemblyText.english?en:zh;
  window.uiTable=false;
  window.sceneSurface='rgba(0,0,0,0)';
  const source=WORKFLOW.tasks.find(t=>t.id==='install:层2板');
  // The native row lesson isolates the receiver and the current board to reveal both rows.
  const task={...source,context:source.context.filter(id=>id.startsWith('左侧板') || source.members.includes(id))};
  task.stationary=task.context.filter(id=>!source.members.includes(id));
  configureScene(task);
  const bases=drawParts.filter(m=>!m.moving);
  const halves=drawParts.filter(m=>m.moving);
  const blueVertices=halves.flatMap(m=>m.triangles.flat());
  const ranges=boundsOf(blueVertices);
  const center=ranges.map(([lo,hi])=>(lo+hi)/2);
  // At the middle of the +X edge the notch is recessed into the panel.
  // Derive its anchor from CAD vertices in the inset, in the task's tabletop pose.
  const xEnd=ranges[0][1], middleY=center[1], height=ranges[1][1]-ranges[1][0];
  const notchEdge=blueVertices.filter(p=>p[0]>xEnd-16 && Math.abs(p[1]-middleY)<height*.085);
  const inset=Math.min(...notchEdge.map(p=>p[0]));
  const deepEdge=boundsOf(notchEdge.filter(p=>p[0]<inset+.5));
  const notch=deepEdge.map(([lo,hi])=>(lo+hi)/2);
  const shiftY=42;
  const move=(v,angle,lift)=>{
    const [x,y,z]=sub(v,center);
    const c=Math.cos(angle),s=Math.sin(angle);
    return [center[0]+c*x+s*z,center[1]+y+lift,center[2]-s*x+c*z];
  };
  let angle=0,lift=shiftY,currentModels=halves;
  function rotateBlue(radians) {
    if(Math.abs(radians)<.00001){currentModels=halves;return;}
    currentModels=halves.map(m=>{
      const mesh=meshFromTriangles('turned-'+m.id,m.triangles.map(tr=>tr.map(p=>move(p,radians,0))),m.color,true);
      gpu?.update(mesh);return mesh;
    });
  }
  if(mode==='wrong') {
    RowLesson.start(task,1,2);
  } else {
    const baseMeshes=[...bases,...installSlotMarks].map(mesh=>({mesh,shift:[0,0,0]}));
    sceneInstances=()=>[...baseMeshes,...currentModels.map(mesh=>({mesh,shift:[0,lift,0]}))];
    const box=boundsOf([...bases.flatMap(m=>m.triangles.flat()),...blueVertices.map(v=>add(v,[0,shiftY,0]))]);
    const sz=box.map(([lo,hi])=>hi-lo);
    viewCenter=box.map(([lo,hi])=>(lo+hi)/2);
    sceneScale=(w,h)=>{
      const basis=sceneBasis(),span=ax=>ax.reduce((sum,val,i)=>sum+Math.abs(val)*sz[i],0);
      return zoom*Math.min(w*.89/span(basis[0]),h*.75/span(basis[1]));
    };
  }
  yaw=2.26; pitch=.55;
  let caption='';
  function project(p) {
    const w=cv.clientWidth,h=cv.clientHeight;
    const q=sub(p,viewCenter),b=sceneBasis(),s=sceneScale(w,h);
    return [w/2+dot(q,b[0])*s,h/2-dot(q,b[1])*s];
  }
  const baseDraw=drawScene;
  // RowLesson owns its mesh offsets and trajectory annotations. In static mode only show the geometry.
  const drawRowNotes=RowLesson.annotate;
  RowLesson.annotate=(...args)=>{if(requested)drawRowNotes(...args);};
  function drawTargetRow() {
    // Keep the two slot targets legible at phone size, including when the seated board covers one.
    ctx.save();ctx.lineJoin='round';
    for(const mesh of installSlotMarks) {
      const [[xmin,xmax],[y],[zmin,zmax]]=boundsOf(mesh.triangles.flat());
      const points=[[xmin,y,zmin],[xmax,y,zmin],[xmax,y,zmax],[xmin,y,zmax]].map(project);
      ctx.beginPath();points.forEach(([x,z],i)=>i?ctx.lineTo(x,z):ctx.moveTo(x,z));ctx.closePath();
      ctx.lineWidth=3.4;ctx.strokeStyle='#fff8db';ctx.stroke();
      ctx.lineWidth=1.65;ctx.strokeStyle='#bb711b';ctx.stroke();
    }
    ctx.restore();
  }
  function drawMarker(p) {
    const [x,y]=project(p), w=cv.clientWidth;
    if(x<0||x>w||y<0||y>cv.clientHeight)return;
    ctx.save();ctx.lineWidth=2;ctx.strokeStyle='white';ctx.fillStyle='#9b4b1e';
    ctx.beginPath();ctx.arc(x,y,4.5,0,2*Math.PI);ctx.fill();ctx.stroke();
    const label=tr('Notch','缺口');
    ctx.font='600 12px system-ui,sans-serif';ctx.textBaseline='middle';
    const width=ctx.measureText(label).width+12;
    const side=x<w/2?-1:1;
    const left=Math.max(5,Math.min(w-width-5,side<0?x-width-12:x+12));
    const top=Math.max(3,y-25);
    ctx.beginPath();ctx.moveTo(x,y-3);ctx.lineTo(left+width/2,top+10);ctx.strokeStyle='#975234';ctx.lineWidth=1.5;ctx.stroke();
    ctx.fillStyle='#fffaf0';ctx.fillRect(left,top,width,19);
    ctx.fillStyle='#623e2a';ctx.textAlign='center';ctx.fillText(label,left+width/2,top+9.5);
    ctx.restore();
  }
  drawScene=()=>{
    baseDraw();
    drawTargetRow();
    if(mode==='wrong'){
      const moving=RowLesson.instances()?.find(o=>o.mesh.moving);
      if(moving)drawMarker(add(notch,moving.shift));
    } else {
      drawMarker(move(notch,angle,lift));
      if(caption) {
        ctx.save();ctx.font='600 13px system-ui,sans-serif';ctx.textAlign='center';ctx.textBaseline='middle';
        const w=cv.clientWidth,h=cv.clientHeight,width=ctx.measureText(caption).width+22;
        ctx.fillStyle='#fffdf4ed';ctx.beginPath();
        if(ctx.roundRect)ctx.roundRect((w-width)/2,h-34,width,25,11);else ctx.rect((w-width)/2,h-34,width,25);
        ctx.fill();ctx.fillStyle='#3f4d37';ctx.fillText(caption,w/2,h-21);ctx.restore();
      }
    }
  };
  cv.setAttribute('aria-label',tr('Blue shelf, gray base frame and marked row two. Drag to rotate.','蓝色层板、灰色侧框与第二排标记。可拖动旋转。'));
  const duration=mode==='wrong'?RowLesson.duration:mode==='reverse'?4250:2400;
  const eased=(ms,a,b)=>{const t=Math.max(0,Math.min(1,(ms-a)/(b-a)));return t*t*(3-2*t);};
  function update(ms) {
    if(mode==='wrong') {RowLesson.draw(ms);return;}
    const turning=mode==='reverse';
    const nextAngle=turning?Math.PI*(1-eased(ms,300,2490)):0;
    if(nextAngle!==angle){angle=nextAngle;rotateBlue(angle);}
    lift=shiftY*(1-eased(ms,turning?2740:340,turning?3880:1900));
    caption=!requested?'':lift<.1?tr('Seat both tabs in row 2','两端插入第二排'):
      turning && ms<2740?tr('Turn the upright shelf','立起层板，再转向'):tr('Lower the shelf into row 2','对准第二排，向下插入');
    paintScene();
  }
  update(requested && reduce?duration:0);
  if(requested && !reduce) {
    const start=performance.now();
    function play(now) {
      const elapsed=now-start;update(Math.min(duration,elapsed));
      if(elapsed<duration)requestAnimationFrame(play);
    }
    requestAnimationFrame(play);
  }
})();
