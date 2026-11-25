// Physical hole coordinates are normalized to the current preview crop by CameraX.
function renderSlotTargets(event) {
 const layer = document.getElementById('slotTargets');
 const points = event?.visible && Array.isArray(event.slotTargets) ? event.slotTargets : [];
 renderInstallHeading(event);
 const predicted = event?.visible && Array.isArray(event.inferredSlotTargets) ? event.inferredSlotTargets : [];
 const marks = [...points.map(p => ({...p, kind:'slot'})), ...predicted.map(p=>({...p,kind:'estimated'}))];
 if (event?.visible && event.stable && ['turn_one','flip_one'].includes(event.installMotion)) {
   marks.push({x:event.anchorX, y:event.anchorY, kind:'adjust'});
 }
 const visible = marks.filter(p => Number.isFinite(p.x) && Number.isFinite(p.y) &&
   p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1);
 while (layer.children.length > visible.length) layer.lastChild.remove();
 visible.forEach((p, index) => {
   let mark = layer.children[index];
   if (!mark) { mark = document.createElement('i'); layer.append(mark); }
   mark.classList.toggle('estimated', p.kind === 'estimated');
   mark.classList.toggle('adjust-target', p.kind === 'adjust');
   mark.style.left = (p.x * 100) + '%';
   mark.style.top = (p.y * 100) + '%';
 });
}

// Points are in the displayed camera crop. A hinge has two ends; an in-plane
// turn has a centre and a radius; an insertion goes from the moving end to the frame.
function backArrow(event,w,h){
 const points=event?.backCue,move=event?.installMotion;
 if(event?.task!=='install:背板' || !event.visible || points?.length!==2 ||
    !points.every(p=>p && Number.isFinite(p.x) && Number.isFinite(p.y) && p.x>=0 && p.x<=1 && p.y>=0 && p.y<=1))return null;
 const [a,b]=points.map(p=>[p.x*w,p.y*h]);
 const dx=b[0]-a[0],dy=b[1]-a[1],length=Math.hypot(dx,dy);
 if(length<8)return null;
 if(move==='move_back_to_frame'){
  if(!['install_align','install_oriented'].includes(event.state))return null;
  return {path:'M'+a.join(' ')+'L'+b.join(' '),axis:'',label:AssemblyText.phrase('对准这个接头'),labelPos:[(a[0]+b[0])/2,(a[1]+b[1])/2-14]};
 }
 if(event.state!=='install_turn'||!event.stable)return null;
 const ux=dx/length,uy=dy/length;
 if(move==='turn_back_in_plane'){
  const r=Math.min(length,w*.13,h*.13);
  const start=[a[0]+ux*r,a[1]+uy*r],end=[a[0]-ux*r,a[1]-uy*r];
  // Pick the semicircle towards the visible image so a hand at either edge can see it.
  const mid=[a[0]-uy*r,a[1]+ux*r];
  const cw=(mid[0]>=5 && mid[0]<=w-5 && mid[1]>=5 && mid[1]<=h-5)?1:0;
  return {path:'M'+start.join(' ')+' A'+[r,r,0,0,cw,...end].join(' '),axis:'',label:AssemblyText.phrase('沿板面转半圈'),labelPos:[a[0],a[1]-10]};
 }
 if(move!=='turn_back_ends'&&move!=='turn_over_end')return null;
 const cx=(a[0]+b[0])/2,cy=(a[1]+b[1])/2,r=Math.min(length*.42,w*.11,h*.11);
 const start=[cx+uy*r,cy-ux*r],end=[cx-uy*r,cy+ux*r];
 const ctrl=[cx+ux*r*.95,cy+uy*r*.95];
 return {path:'M'+start.join(' ')+' Q'+ctrl.join(' ')+' '+end.join(' '),axis:'M'+a.join(' ')+' L'+b.join(' '),
  label:move==='turn_over_end'?AssemblyText.phrase('上下翻转'):AssemblyText.phrase('左右翻转'),labelPos:[cx,Math.min(start[1],end[1],ctrl[1])-14]};
}

// Keep only arrows with fresh positions in this frame.
function renderInstallHeading(event) {
 const svg=document.getElementById('installHeading');
 const host=svg.parentElement,w=host.clientWidth,h=host.clientHeight;
 const base=event?.state==='install_base_heading' && event?.stable;
 const notch=event?.notchCue;
 const valid=p=>p && Number.isFinite(p.x) && Number.isFinite(p.y);
 const back=backArrow(event,w,h);
 const supported=event?.visible && w>0 && h>0 && (back || base || (notch?.length===2 && notch.every(valid)));
 svg.toggleAttribute('hidden',!supported);
 if(!supported)return;
 const px=p=>[p.x*w,p.y*h];
 let start,dir,path,label,axis='',labelPos;
 if(back){
  ({path,label,axis,labelPos}=back);
 }else if(base){
  if(!Number.isFinite(event.anchorX)||!Number.isFinite(event.anchorY)){svg.setAttribute('hidden','');return}
  const cx=event.anchorX*w,cy=event.anchorY*h;
  const row=event.slotTargets;
  if(row?.length!==2 || !row.every(valid) || !Number.isFinite(event.angle)){svg.setAttribute('hidden','');return}
  const midpoint=[(row[0].x+row[1].x)*w/2,(row[0].y+row[1].y)*h/2];
  const dx=midpoint[0]-cx,dy=midpoint[1]-cy;
  const rad=Math.min(Math.hypot(dx,dy),.31*w,.36*h);
  const from=Math.atan2(dy,dx),to=from+event.angle*Math.PI/180;
  const turnPoint=angle=>[cx+rad*Math.cos(angle),cy+rad*Math.sin(angle)];
  const finish=turnPoint(to);
  path='M'+turnPoint(from).join(' ')+' A'+rad+' '+rad+' 0 0 '+(event.angle>=0?1:0)+' '+finish.join(' ');
  label=AssemblyText.phrase('首排孔转到这里');labelPos=[finish[0],finish[1]-15];
 }else{
  start=px(notch[0]);const end=px(notch[1]),len=Math.hypot(end[0]-start[0],end[1]-start[1]);
  if(len<8){svg.setAttribute('hidden','');return}
  dir=[(end[0]-start[0])/len,(end[1]-start[1])/len];
  const reach=Math.min(len, Math.min(w,h)*.24);
  path='M'+start.join(' ')+'l'+(reach*dir[0])+' '+(reach*dir[1]);
  label=AssemblyText.phrase('缺口朝这边');labelPos=[start[0]+reach*dir[0]*.46,start[1]+reach*dir[1]*.46-16];
  const a=event.installAxis;
  if(event.installMotion==='turn_panel_upright' && a?.length===2 && a.every(valid)){
   const u=px(a[0]),v=px(a[1]),dx=v[0]-u[0],dy=v[1]-u[1],n=Math.hypot(dx,dy),radius=Math.min(n*.4,75);
   if(n>10)axis='M'+(start[0]-dx*radius/n)+' '+(start[1]-dy*radius/n)+'l'+(2*dx*radius/n)+' '+(2*dy*radius/n);
  }
 }
 svg.setAttribute('viewBox','0 0 '+w+' '+h);
 svg.querySelector('.hinge').setAttribute('d',axis);
 for(const node of svg.querySelectorAll('.heading-stroke,.heading-ink'))node.setAttribute('d',path);
 const name=svg.querySelector('.heading-name');name.textContent=label;
 name.setAttribute('x',String(Math.max(43,Math.min(w-43,labelPos[0]))));name.setAttribute('y',String(Math.max(22,Math.min(h-12,labelPos[1]))));
}

const feedbackStyles = {pulse:AssemblyText.phrase('醒目后淡出'),strong:AssemblyText.phrase('持续醒目'),quiet:AssemblyText.phrase('柔和')};
function applyFeedbackStyle(style) {
 document.documentElement.dataset.feedbackStyle = Object.hasOwn(feedbackStyles, style) ? style : 'pulse';
}
function feedbackPicker() {
 const box = showOverlay(AssemblyText.phrase('提示颜色'));
 for (const [value, label] of Object.entries(feedbackStyles)) {
   const selected = document.documentElement.dataset.feedbackStyle === value;
   const row = button(label, () => {
     AssemblyNative.setFeedbackStyle?.(value);
     applyFeedbackStyle(value);
     feedbackPicker();
   });
   row.setAttribute('aria-pressed', String(selected));
   row.classList.add('feedback-choice');
   box.append(row);
 }
 const sample = element('div',null,'feedback-sample');
 sample.setAttribute('aria-label',AssemblyText.phrase('提示颜色预览'));
 sample.append(element('span',AssemblyText.phrase('方向对了'),'positive'),element('span',AssemblyText.phrase('需要调整'),'adjust'));
 box.append(sample);
}
applyFeedbackStyle(AssemblyNative.feedbackStyle?.() || 'pulse');
