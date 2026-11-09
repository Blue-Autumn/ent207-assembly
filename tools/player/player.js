import {transitionPoses} from './math.js';
const $=id=>document.getElementById(id);
const params=new URLSearchParams(location.search);
let zh=params.get('lang')==='zh',translate=()=>{};
const tr=(en,cn)=>zh?cn:en;
window.ent207SetLanguage=lang=>{zh=lang.startsWith('zh');document.documentElement.lang=zh?'zh-CN':'en';translate();};
const key='ent207.tools.luban.v1.'+(params.get('profile')||'guest');
async function json(path){const r=await fetch(path);if(!r.ok)throw Error('asset');return r.json();}
function unpack(data){
 const decode=(type,k)=>new type(Uint8Array.from(atob(data[k]),c=>c.charCodeAt(0)).buffer);
 const xyz=decode(Float32Array,'points'),indices=decode(Uint16Array,'indices'),normals=decode(Float32Array,'normals');
 const vertices=new Float32Array(indices.length*6);
 for(let i=0;i<indices.length;i++){
  const offset=indices[i]*3,normal=Math.floor(i/3)*3,out=i*6;
  for(let j=0;j<3;j++){vertices[out+j]=xyz[offset+j];vertices[out+j+3]=normals[normal+j];}
 }
 return vertices;
}
async function start(){
 translate=()=>{$('status').textContent=tr('Loading the puzzle model…','正在载入鲁班锁模型…');};translate();
 const scene=await json('motion.json'),draft={steps:scene.steps},count=draft.steps.length-1,canvas=$('canvas');
 const gl=canvas.getContext('webgl',{antialias:true,alpha:false});
 if(!gl)throw Error('webgl');
 await Promise.all(scene.groups.map(async g=>{g.mesh=unpack(await json(g.file));}));
 let index=0,t=0,playing=false,started=0,request=0,az=-.85,el=.55,zoom=370,drag=null;
 let saveError=false;
 try{const s=JSON.parse(localStorage.getItem(key));if(s && Number.isInteger(s.index) && s.index>=0 && s.index<count && Number.isFinite(s.t) && s.t>=0 && s.t<=1){index=s.index;t=s.t;}}catch{}
 const playbackProgress=(now,start)=>Math.max(0,Math.min(1,(now-start)/3000));
 function sample(draft,idx,t){const n=count-idx,from=draft.steps[n-1],to=draft.steps[n];return transitionPoses(from.poses,to.poses,to.action,1-t);}
 function save(){try{localStorage.setItem(key,JSON.stringify({index,t}));}catch{saveError=true;}}
 function refresh(){
  const source=count-index,move=draft.steps[source].action, labels=move.ids.map(id=>scene.groups.find(g=>g.id===id)).map(g=>zh?g.label+'（'+g.colorName+'）':g.labelEn+' ('+g.colorEn+')');
  $('step').textContent=tr('Step ','第 ')+(index+1)+' / '+count+tr('',' 步');$('instruction').textContent=labels.join(' + ');
  $('previous').textContent=tr('Previous','上一步');$('previous').disabled=index===0;
  $('next').textContent=index===count-1?tr('First step','回到开头'):tr('Next step','下一步');
  $('play').textContent=playing?tr('Pause','暂停'):t===1?tr('Replay step','重播本步'):tr('Play step','播放本步');
  $('seek').value=String(Math.round(t*1000));
  $('status').textContent=saveError?tr('This browser could not save the position.','此浏览器未能保存浏览位置。'):t===1?(index===count-1?tr('Complete puzzle. Drag to inspect.','完整组装体，拖动可查看。'):tr('Step complete. Check the physical move before continuing.','动画结束，可核对实物动作后继续。')):tr('Drag the model to turn it. Scrub to inspect the movement.','拖动模型调整视角，滑动进度条观察动作。');
  $('warning').textContent=source===7?tr('The draft path passes close to a removed piece. Check its parking position.','草案路径与旁边已取出的零件接近，请核对停放位置。'):'';
 }
 function invalidate(){if(!request&&!document.hidden)request=requestAnimationFrame(draw);}
 function stop(){playing=false;save();refresh();}
 function begin(){if(t>=1)t=0;started=performance.now()-t*3000;playing=true;refresh();invalidate();}
 function select(i){playing=false;index=i;t=0;save();refresh();invalidate();}
 $('play').onclick=()=>playing?stop():begin();
 $('seek').oninput=()=>{playing=false;t=Number($('seek').value)/1000;save();refresh();invalidate();};
 $('previous').onclick=()=>select(Math.max(0,index-1));
 $('next').onclick=()=>{if(index===count-1){select(0);return;}select(index+1);if(!matchMedia('(prefers-reduced-motion:reduce)').matches)begin();};
 $('reset').onclick=()=>{if(confirm(tr('Return to step one?','清除浏览位置，回到第一步？')))select(0);};
 $('view').onclick=()=>{az=-.85;el=.55;zoom=370;invalidate();};
 $('in').onclick=()=>{zoom=Math.max(70,zoom/1.25);invalidate();};$('out').onclick=()=>{zoom=Math.min(900,zoom*1.25);invalidate();};
 canvas.onpointerdown=e=>{if(!e.isPrimary)return;drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};
 canvas.onpointermove=e=>{if(!drag||!e.isPrimary)return;az-=(e.clientX-drag[0])*.008;el=Math.max(-1.4,Math.min(1.4,el+(e.clientY-drag[1])*.008));drag=[e.clientX,e.clientY];invalidate();};
 canvas.onpointerup=canvas.onpointercancel=()=>drag=null;
 canvas.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(70,Math.min(900,zoom*Math.exp(e.deltaY*.001)));invalidate();},{passive:false});
 new ResizeObserver(invalidate).observe(canvas);
 window.addEventListener('pagehide',stop);document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();else invalidate();});
 canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();stop();fail();});
 function shader(type,code){const s=gl.createShader(type);gl.shaderSource(s,code);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;}
 const program=gl.createProgram();
 gl.attachShader(program,shader(gl.VERTEX_SHADER,'attribute vec3 p,n;uniform mat4 vp;uniform vec3 shift;uniform vec4 q;varying vec3 normal;vec3 rot(vec3 v){return v+2.0*cross(q.xyz,cross(q.xyz,v)+q.w*v);}void main(){normal=rot(n);gl_Position=vp*vec4(rot(p)+shift,1.0);}'));
 gl.attachShader(program,shader(gl.FRAGMENT_SHADER,'precision mediump float;uniform vec3 color;varying vec3 normal;void main(){float light=.35+.65*abs(dot(normalize(normal),normalize(vec3(.3,-.4,.8))));gl_FragColor=vec4(color*light,1.0);}'));
 gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program));gl.useProgram(program);
 const loc={};for(const k of ['vp','shift','q','color'])loc[k]=gl.getUniformLocation(program,k);for(const k of ['p','n']){loc[k]=gl.getAttribLocation(program,k);gl.enableVertexAttribArray(loc[k]);}
 for(const g of scene.groups){
   g.buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,g.buffer);gl.bufferData(gl.ARRAY_BUFFER,g.mesh,gl.STATIC_DRAW);delete g.mesh;
 }
 function norm(v){return v.map(x=>x/Math.hypot(...v));}function cross(a,b){return[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];}function dot(a,b){return a.reduce((s,v,i)=>s+v*b[i],0);}
 function vp(aspect){const target=scene.center,eye=[target[0]+zoom*Math.cos(el)*Math.cos(az),target[1]+zoom*Math.cos(el)*Math.sin(az),target[2]+zoom*Math.sin(el)],z=norm(eye.map((x,i)=>x-target[i])),x=norm(cross([0,0,1],z)),y=cross(z,x),view=[x[0],y[0],z[0],0,x[1],y[1],z[1],0,x[2],y[2],z[2],0,-dot(x,eye),-dot(y,eye),-dot(z,eye),1],f=1/Math.tan(Math.PI/8),p=[f/aspect,0,0,0,0,f,0,0,0,0,-1501/1499,-1,0,0,-3000/1499,0],out=new Float32Array(16);for(let r=0;r<4;r++)for(let c=0;c<4;c++)for(let k=0;k<4;k++)out[c*4+r]+=p[k*4+r]*view[c*4+k];return out;}
 function draw(now){request=0;if(playing){t=playbackProgress(now,started);if(t===1)stop();else refresh();}const poses=sample(draft,index,t),active=draft.steps[count-index].action?.ids||[],dpr=Math.min(devicePixelRatio||1,2),w=Math.max(1,Math.round(canvas.clientWidth*dpr)),h=Math.max(1,Math.round(canvas.clientHeight*dpr));if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}gl.viewport(0,0,w,h);gl.clearColor(.91,.94,.97,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.enable(gl.DEPTH_TEST);gl.uniformMatrix4fv(loc.vp,false,vp(w/h));for(const g of scene.groups){gl.uniform3fv(loc.shift,poses[g.id].position);gl.uniform4fv(loc.q,poses[g.id].quaternion);gl.uniform3fv(loc.color,g.color.map(v=>Math.min(1,v*(active.includes(g.id)?1.06:1))));gl.bindBuffer(gl.ARRAY_BUFFER,g.buffer);gl.vertexAttribPointer(loc.p,3,gl.FLOAT,false,24,0);gl.vertexAttribPointer(loc.n,3,gl.FLOAT,false,24,12);gl.drawArrays(gl.TRIANGLES,0,g.triangles*3);}if(playing)invalidate();}

 translate=()=>{
  document.documentElement.lang=zh?'zh-CN':'en';$('view').textContent=tr('Reset view','复位视角');
  $('in').ariaLabel=tr('Zoom in','放大');$('out').ariaLabel=tr('Zoom out','缩小');canvas.ariaLabel=tr('Luban puzzle model','鲁班锁模型');
  $('progress-label').textContent=tr('Movement','本步动画进度');$('notes-title').textContent=tr('Preparation and notes','准备与说明');
  $('notes').textContent=tr('Start with the grey insertion pin and white cap joined. These 15 moves reverse a recorded disassembly. Distances and axes follow the draft; check clearances on the physical puzzle.','插入销与顶盖作为预装组件。15 段动作按实物拆卸记录倒序展示；位移和轴向来自草案，请在实物上核对间隙。');
  $('reset').textContent=tr('Start again','重新开始');refresh();
 };translate();invalidate();
}
function fail(){
 document.querySelector('.player').replaceChildren();
 translate=()=>{const main=document.querySelector('.player');const h=document.createElement('h2'),p=document.createElement('p'),b=document.createElement('button');
 h.textContent=tr('3D preview unavailable','三维预览暂不可用');p.textContent=tr('The player needs WebGL and the puzzle assets. Reload to try again.','播放器需要 WebGL 和模型文件，可重新载入后再试。');
 b.textContent=tr('Retry','重试');b.onclick=()=>location.reload();main.replaceChildren(h,p,b);};translate();
}
start().catch(fail);
