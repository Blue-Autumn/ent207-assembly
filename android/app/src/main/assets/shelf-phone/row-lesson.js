'use strict';
// Both the live guide and the photo review use this CAD demonstration. Rows are numbered 1..5.
window.RowLesson=(()=>{
 const duration=5250;
 let plan=null;
 function active(){return plan!==null && plan.scene===renderTask}
 function stop(){
  if(active()){yaw=plan.yaw;pitch=plan.pitch;zoom=plan.zoom;viewCenter=plan.previousCenter;cv.style.opacity='1';}
  plan=null;paintScene();
 }
 function start(task,from,to){
  if(task?.kind!=='install'||task.id!=='install:层'+to+'板'||task.group!=='层'+to+'板'||
     !Number.isInteger(from)||!Number.isInteger(to)||Math.min(from,to)<1||Math.max(from,to)>5||from===to)return false;
  stop();
  if(renderTask?.id!==task.id||!drawParts.some(m=>m.moving))configureScene(task);
  const base=drawParts.filter(m=>m.id.startsWith('左侧板-'));
  const moving=drawParts.filter(m=>m.moving);
  const source=partMap.get('层'+from+'板-1'),target=partMap.get('层'+to+'板-1');
  if(base.length!==2||moving.length!==2||!source||!target)return false;
  function rowCenter(part){
   const vertices=part.verts;
   let lo=Infinity,hi=-Infinity;
   for(let i=1;i<vertices.length;i+=3){lo=Math.min(lo,vertices[i]);hi=Math.max(hi,vertices[i]);}
   return (lo+hi)/2;
  }
  const across=mul(poseMatrix,[0,rowCenter(source)-rowCenter(target),0]);
  const bounds=boundsOf(moving.flatMap(m=>m.triangles.flat()));
  // For a non-adjacent move, clear installed boards between the two rows.
  const crossing=task.stationary.some(id=>{
   const row=Number(/^层([1-5])板-/.exec(id)?.[1]);return row>Math.min(from,to)&&row<Math.max(from,to);
  });
  const lift=crossing?bounds[1][1]-bounds[1][0]+18:Math.max(36,extent*.13);
  const envelope=boundsOf([...base.flatMap(m=>m.triangles.flat()),
   ...moving.flatMap(m=>m.triangles.flat()).flatMap(p=>[p,add(p,across),add(p,[0,lift,0]),add(add(p,across),[0,lift,0])])]);
  plan={scene:renderTask,from,to,base,moving,across,lift,yaw,pitch,zoom,envelope,previousCenter:viewCenter,offset:across,info:null,
   // One outer edge of the moving board carries the projected movement path.
   anchor:[bounds[0][1],bounds[1][1],(bounds[2][0]+bounds[2][1])/2]};
  yaw=2.26;pitch=.55;zoom=1;installCorrection=null;
  viewCenter=envelope.map(([a,b])=>(a+b)/2);
  draw(0);
  return true;
 }
 function sample(t,lo,hi){
  const x=Math.max(0,Math.min(1,(t-lo)/(hi-lo)));return x*x*(3-2*x);
 }
 function draw(elapsedMs,reduced=false){
  if(!active())return null;
  const t=Math.max(0,elapsedMs);
  const lifted=sample(t,380,1510),moved=sample(t,1720,3070),lowered=sample(t,3290,4470);
  const y=plan.lift*(lifted-lowered);
  plan.offset=reduced?[0,0,0]:add(times(plan.across,1-moved),[0,y,0]);
  const stage=reduced?'overview':t<1630?'lift':t<3180?'move':t<4470?'lower':'end';
  const label=AssemblyText.t('row.'+stage,{from:plan.from,to:plan.to});
  plan.info={stage,label,done:t>=duration};
  cv.style.opacity='1';paintScene();return plan.info;
 }
 function instances(){
  if(!active())return null;
  return [...plan.base,...installSlotMarks,...plan.moving].map(mesh=>({mesh,shift:mesh.moving?plan.offset:[0,0,0]}));
 }
 function fit(w,h){
  if(!active())return null;
  const sizes=plan.envelope.map(([a,b])=>b-a),basis=sceneBasis();
  const span=axis=>sizes.reduce((sum,v,i)=>sum+v*Math.abs(axis[i]),0);
  return Math.min(w*.90/Math.max(1,span(basis[0])),h*.69/Math.max(1,span(basis[1])));
 }
 function annotate(w,h,B,scale){
  if(!active()||!plan.info)return;
  const project=p=>{const q=sub(p,viewCenter);return [w/2+dot(q,B[0])*scale,h/2-dot(q,B[1])*scale]};
  const end=plan.anchor,start=add(end,plan.across),up=[0,plan.lift,0];
  const points=[start,add(start,up),add(end,up),end].map(project);
  ctx.save();ctx.lineCap='round';ctx.lineJoin='round';ctx.setLineDash([4,5]);
  ctx.beginPath();for(let i=0;i<points.length;i++)i?ctx.lineTo(...points[i]):ctx.moveTo(...points[i]);
  ctx.strokeStyle='rgba(104,72,29,.70)';ctx.lineWidth=2;ctx.stroke();ctx.setLineDash([]);
  const marker=project(add(end,plan.offset));
  ctx.beginPath();ctx.arc(...marker,4.5,0,2*Math.PI);ctx.fillStyle='#b85814';ctx.fill();ctx.strokeStyle='white';ctx.stroke();
  const label=plan.info.label;
  ctx.font='600 '+Math.max(13,Math.min(19,w*.046))+'px sans-serif';ctx.textAlign='center';ctx.textBaseline='middle';
  const y=h-Math.max(24,h*.07),width=ctx.measureText(label).width+24;
  ctx.fillStyle='#faf8eefa';ctx.beginPath();
  if(ctx.roundRect)ctx.roundRect((w-width)/2,y-17,width,34,15);else ctx.rect((w-width)/2,y-17,width,34);
  ctx.fill();ctx.fillStyle='#4a412c';ctx.fillText(label,w/2,y);ctx.restore();
 }
 return {duration,start,draw,stop,active,instances,fit,annotate};
})();
