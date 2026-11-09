/* Same-photo endpoint checks mirror AiFrameOrientation for the two registered gray frames. */
const graySlots={
  '左侧板-1':[116,76,31,117,124,121,96,84], '左侧板-2':[21,127,128,25,24,22,115,9],
  '右侧板-1':[15,34,3,57,106,41,100,107], '右侧板-2':[91,119,47,39,99,118,129,82]
};
const gray=/^prepare:(左侧板|右侧板)$/;
const bad=new Set(['face_mismatch','joint_mismatch','flip','rotate','wrong_part','translate','install_base_flip','install_turn','install_wrong_row','install_wrong_part','install_stand']);
const good=new Set(['aligned','joint_match','direction_ok','install_row_ok','install_base_ready','install_oriented']);
export function classification(check) {
  if(!check)return 'unknown';
  if(bad.has(check.status)||check.notchRelation==='opposed')return 'adjust';
  if(good.has(check.status)||check.notchRelation==='aligned')return 'direction';
  return 'unknown';
}
export function validCorners(m) {
  const p=m?.corners;
  if(!Array.isArray(p)||p.length!==4||p.some(v=>!Number.isFinite(v.x)||!Number.isFinite(v.y)))return false;
  return area(p)>=16;
}
function area(p){return Math.abs(p.reduce((a,v,i)=>{const n=p[(i+1)%4];return a+v.x*n.y-n.x*v.y;},0))/2;}
function dist(a,b){return Math.hypot(a.x-b.x,a.y-b.y);}
function nearest(a,rows){return rows.reduce((b,p)=>!b||dist(a,p)<dist(a,b)?p:b,null);}
function localized(lang,status,extra={}) {
  const text={
    insufficient:['Show both sides of the joint','Include one registered tag on each half, beside the same connector.','请拍到接缝两侧','把同一接头两侧各一枚已登记标签拍进同一张照片。'],
    face_mismatch:['One frame half is flipped','Separate the joint. Flip one half over and line it up again.','灰框两半正反没对上','先退出接头，把其中一半翻面，再重新对齐。'],
    joint_mismatch:['The ends are reversed','Separate the joint. Turn one half 180° on the table and line up the same ends.','两端接反了','先退出接头，将其中一半在桌面上平转半圈，再对齐同一端。'],
    joint_match:['The tags match at this end','Both tags show the same face and connector end. Check the seam by eye.','这处接头的标签配对正确','两枚标签属于同面、同一连接端。请再目视检查接缝。'],
    aligned:['The frame halves point the same way','Check that the joint sits flush before confirming.','两半方向一致','确认之前，请再检查接缝是否贴合。'],
    rotate:['Turn one frame half','Separate the joint and turn one half flat on the table before joining again.','转正其中一半','先退出接头，在桌面上平转一半，再重新接合。'],
    unavailable:['Local check unavailable','Try the local check again, or inspect the joint by eye.','本地检查暂不可用','可重试本地检查，也可目视检查接头。']
  };
  const words=text[status],i=lang==='zh'?2:0;
  return {status,headline:words[i],instruction:words[i+1],seatingVerified:false,...extra};
}
export const unavailable=lang=>localized(lang,'unavailable');
export function photoCheck(taskId,markers,input,lang) {
  if(!gray.test(taskId))return {...(input||unavailable(lang)),seatingVerified:false};
  const group=taskId.slice(8);
  const parts=[group+'-1',group+'-2'];
  const rows=[];
  for(const [half,part] of parts.entries()){
    for(const m of markers) {
      const slot=graySlots[part].indexOf(m.id);
      if(slot<0)continue;
      if(!validCorners(m)||rows.some(s=>s.id===m.id))return localized(lang,'insufficient');
      const [x,y]=['x','y'].map(axis=>m.corners.reduce((a,v)=>a+v[axis]/4,0));
      const ends=half===0?[1,2]:[0,3];
      rows.push({id:m.id,half,face:slot<4?'A':'B',endpoint:ends.indexOf(slot%4),size:Math.sqrt(area(m.corners)),x,y});
    }
  }
  const faces=[0,1].map(h=>new Set(rows.filter(v=>v.half===h).map(v=>v.face)));
  const facts={markerIds:rows.map(r=>r.id),scope:'current_gray_frame_pair'};
  if(faces.some(f=>f.size!==1))return input?.status==='wrong_part'?{...input,seatingVerified:false}:localized(lang,'insufficient',facts);
  if([...faces[0]][0]!==[...faces[1]][0])return localized(lang,'face_mismatch',facts);
  if(input?.status==='direction_ok')return localized(lang,'aligned',facts);
  if(input?.status==='rotate')return localized(lang,'rotate',facts);
  const [left,right]=[0,1].map(h=>rows.filter(v=>v.half===h && v.endpoint>=0)),matches=new Set(),pairs=[];
  for(const a of left) {
    const b=nearest(a,right);
    if(!b||nearest(b,left)!==a)continue;
    const separation=dist(a,b)/Math.sqrt(a.size*b.size);
    if(separation<.8 || separation>6 || Math.max(a.size,b.size)/Math.min(a.size,b.size)>3)continue;
    pairs.push([a.id,b.id]);matches.add(a.endpoint===b.endpoint);
  }
  const state=matches.size===1?(matches.has(true)?'joint_match':'joint_mismatch'):'insufficient';
  return localized(lang,state,{...facts,pairs,scope:'local_joint_only'});
}
/* A current correction always wins. An old matching pose cannot certify a new, unresolved photo. */
export function guardCloud(raw,taskId,latest,lang) {
  if(!raw||!['ok','issue','need_view'].includes(raw.assessment))throw Error('invalid-review');
  const state=classification(latest), zh=lang==='zh';
  if(state==='adjust')return {assessment:'issue',title:latest.headline,instruction:latest.instruction,source:'local'};
  if(raw.assessment==='ok' && state!=='direction' && (gray.test(taskId)||taskId.startsWith('install:')))
    return {assessment:'need_view',title:zh?'再拍一张当前接头':'Show the current joint',
      instruction:zh?'把当前零件和对应接头的标签同时拍进照片，再检查方向。':'Show the current part and the reference tags together so their direction can be checked.',source:'local'};
  return raw;
}
