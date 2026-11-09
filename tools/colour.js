const linear=Array.from({length:256},(_,i)=>{const s=i/255;return s<=.04045?s/12.92:((s+.055)/1.055)**2.4;});
function lab(rgb,i){
  const [r,g,b]=[linear[rgb[i]],linear[rgb[i+1]],linear[rgb[i+2]]];
  const f=t=>t>216/24389?Math.cbrt(t):t*(24389/27)/116+16/116;
  const x=f((.4124564*r+.3575761*g+.1804375*b)/.95047),y=f(.2126729*r+.7151522*g+.072175*b),z=f((.0193339*r+.119192*g+.9503041*b)/1.08883);
  return [116*y-16,500*(x-y),200*(y-z)];
}
const distance=(a,b)=>Math.hypot(...a.map((v,i)=>v-b[i]));
export function patch(rgb,w,h,x,y){
  const rows=[];
  for(let j=Math.max(y-3,0);j<=Math.min(y+3,h-1);j++)for(let i=Math.max(x-3,0);i<=Math.min(x+3,w-1);i++)rows.push(lab(rgb,4*(j*w+i)));
  return [0,1,2].map(c=>rows.map(v=>v[c]).sort((a,b)=>a-b)[rows.length>>1]);
}
export function inspect(image,point,references){
  const {width:w,height:h,data}=image,{x,y}=point,seed=patch(data,w,h,x,y);
  const labels=new Map();
  for(const row of references){const d=distance(row.lab,seed);labels.set(row.label,Math.min(labels.get(row.label)??Infinity,d));}
  const sorted=[...labels].sort((a,b)=>a[1]-b[1]);
  const name=sorted.length>=2 && sorted[0][1]<=20 && sorted[1][1]-sorted[0][1]>=5?sorted[0][0]:null;
  const seen=new Uint8Array(w*h),queue=new Uint32Array(w*h);
  queue[0]=y*w+x;seen[queue[0]]=1;let end=1,head=0;
  let sx=0,sy=0,sxx=0,syy=0,sxy=0,edge=false;
  function add(i){if(seen[i])return;seen[i]=1;if(distance(lab(data,i*4),seed)<=18)queue[end++]=i;}
  while(head<end){
    const i=queue[head++],a=i%w,b=Math.floor(i/w);sx+=a;sy+=b;sxx+=a*a;syy+=b*b;sxy+=a*b;edge||=a===0||a===w-1||b===0||b===h-1;
    if(a>0)add(i-1);if(a<w-1)add(i+1);if(b>0)add(i-w);if(b<h-1)add(i+w);
  }
  const xx=sxx-sx*sx/end,yy=syy-sy*sy/end,xy=sxy-sx*sy/end;
  const spread=Math.hypot(xx-yy,2*xy),elongation=(xx+yy+spread)/Math.max(1e-9,xx+yy-spread);
  const angle=(Math.atan2(2*xy,xx-yy)*90/Math.PI+180)%180;
  return {name,count:end,cx:sx/end,cy:sy/end,angle:end>=64 && end/(w*h)<.35 && !edge && elongation>=2?angle:null};
}

export function mountProbe(pane,lang,profile){
  let zh=lang==='zh',at=null,image=null,analysis=null,done='',fileURL='',active=true,version=0;
  const tr=(en,cn)=>zh?cn:en;
  const ids=['red','green','orange','yellow','blue','white','black','pink','purple','grey','background'];
  const cn=['红色','绿色','橘黄色','亮黄色','蓝色','白色','黑色','粉色','紫色','灰色','背景'];
  const name=id=>zh?cn[ids.indexOf(id)]:id;
  const key='ent207.tools.colour.v1.'+profile;
  let refs=[];try{const rows=JSON.parse(localStorage.getItem(key));if(Array.isArray(rows))refs=rows.filter(x=>ids.includes(x?.label)&&x.lab?.length===3&&x.lab.every(Number.isFinite));}catch{}
  pane.innerHTML='<p class="hint" id="help"></p><div class="buttons"><button id="sample"></button><label class="pick"><span id="choose"></span><input id="file" type="file" accept="image/png,image/jpeg,image/webp"></label></div><div class="probe"><canvas id="probe-canvas" tabindex="0"></canvas></div><div class="pick-line"><span class="swatch" id="swatch"></span><label for="label" id="label-name"></label><select id="label"></select></div><div class="buttons"><button id="learn"></button><button class="primary" id="analyze"></button></div><p id="answer" aria-live="polite"></p><button id="clear"></button>';
  const $=id=>pane.querySelector('#'+id),canvas=$('probe-canvas'),ctx=canvas.getContext('2d',{willReadFrequently:true});
  function render(){
    $('help').textContent=tr('Tap inside a colour patch. Save two different reference colours, then analyse a patch. Photos stay in this page.','点一下色块内部。保存至少两种不同颜色的参考样本，再分析所选区域。照片只在当前页处理。');
    $('sample').textContent=tr('Use field photo','使用实物样片');$('choose').textContent=tr('Choose local photo','选择本机照片');
    canvas.ariaLabel=tr('Choose a patch in the photograph','点选照片中的色块');
    $('file').ariaLabel=$('choose').textContent;$('label-name').textContent=tr('Reference colour','参考颜色');
    const selected=$('label').value||'blue';$('label').replaceChildren(...ids.map(id=>new Option(name(id),id)));$('label').value=selected;
    $('learn').textContent=tr('Save reference','记住此颜色');$('analyze').textContent=tr('Analyse patch','分析选区');
    $('learn').disabled=$('analyze').disabled=!at;$('clear').disabled=!refs.length;
    $('clear').textContent=tr('Clear references','清除参考样本')+' · '+refs.length;
    const a=analysis;
    $('answer').textContent=a?tr('Colour candidate: ','颜色候选：')+(a.name?name(a.name):tr('unknown','不确定'))+
      tr(' · patch axis: ',' · 色块长轴：')+(a.angle===null?tr('undetermined','不确定'):a.angle.toFixed(1)+'°')+
      tr('. Measured in the image; the two axis directions are equivalent.','。仅在图像平面测量，长轴两端等价。'):
      done==='saved'?tr('Reference saved in this browser.','参考值已保存到本浏览器。'):done==='error'?tr('Could not open this photo.','无法打开这张照片。'):
      done==='storage'?tr('Could not save in this browser.','此浏览器未能保存参考值。'):at?tr('Patch selected.','已选中色块。'):'';
    if(!image)return;
    ctx.putImageData(image,0,0);
    if(at){
      const i=4*(at.y*image.width+at.x);$('swatch').style.background='rgb('+Array.from(image.data.slice(i,i+3)).join(',')+')';
      ctx.beginPath();ctx.arc(at.x,at.y,7,0,2*Math.PI);ctx.strokeStyle='#00ffff';ctx.lineWidth=2;ctx.stroke();
    }else $('swatch').style.background='transparent';
    if(a && a.angle!==null){const rad=a.angle*Math.PI/180,dx=28*Math.cos(rad),dy=28*Math.sin(rad);
      ctx.beginPath();ctx.moveTo(a.cx-dx,a.cy-dy);ctx.lineTo(a.cx+dx,a.cy+dy);ctx.strokeStyle='#d51188';ctx.stroke();}
  }
  async function load(src){
    const token=++version,im=new Image();im.src=src;
    try{await im.decode();if(!active||token!==version)return;
      const scale=Math.min(1,420/Math.max(im.naturalWidth,im.naturalHeight));
      canvas.width=Math.max(1,Math.round(im.naturalWidth*scale));canvas.height=Math.max(1,Math.round(im.naturalHeight*scale));
      ctx.drawImage(im,0,0,canvas.width,canvas.height);image=ctx.getImageData(0,0,canvas.width,canvas.height);
      at=null;analysis=null;done='';render();
    }catch{if(active&&version===token){done='error';render();}}
  }
  $('sample').onclick=()=>load('../field/blue-row.webp');
  $('file').onchange=e=>{const file=e.target.files[0];if(!file)return;if(fileURL)URL.revokeObjectURL(fileURL);fileURL=URL.createObjectURL(file);load(fileURL);};
  canvas.onclick=e=>{
    if(!image)return;const b=canvas.getBoundingClientRect(),ratio=Math.min(b.width/image.width,b.height/image.height);
    const x=Math.floor((e.clientX-b.left-(b.width-ratio*image.width)/2)/ratio),y=Math.floor((e.clientY-b.top-(b.height-ratio*image.height)/2)/ratio);
    if(x<0||x>=image.width||y<0||y>=image.height)return;at={x,y};analysis=null;done='';render();
  };
  canvas.onkeydown=e=>{if(!image||!['Enter','ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key))return;e.preventDefault();
    at??={x:image.width>>1,y:image.height>>1};at.x=Math.max(0,Math.min(image.width-1,at.x+Number(e.key==='ArrowRight')-Number(e.key==='ArrowLeft')));at.y=Math.max(0,Math.min(image.height-1,at.y+Number(e.key==='ArrowDown')-Number(e.key==='ArrowUp')));analysis=null;done='';render();};
  $('learn').onclick=()=>{
    if(!at)return;const next=[...refs,{label:$('label').value,lab:patch(image.data,image.width,image.height,at.x,at.y)}].slice(-120);
    try{localStorage.setItem(key,JSON.stringify(next));refs=next;done='saved';analysis=null;}catch{done='storage';}render();
  };
  $('analyze').onclick=()=>{if(at)analysis=inspect(image,at,refs);render();};
  $('clear').onclick=()=>{if(confirm(tr('Clear all saved colour references for this profile?','清除此档案的全部颜色参考值？'))){
    try{localStorage.removeItem(key);refs=[];done='';analysis=null;}catch{done='storage';}render();}};
  render();load('../field/blue-row.webp');
  return {language:l=>{zh=l==='zh';render();},dispose:()=>{active=false;if(fileURL)URL.revokeObjectURL(fileURL);}};
}
