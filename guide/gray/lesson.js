'use strict';
(() => {
  const $=id=>document.getElementById(id),zh=new URLSearchParams(location.search).get('lang')?.startsWith('zh');
  const tr=(en,cn)=>zh?cn:en;
  const feedback=ShelfFeedback.attach(document.body);
  document.documentElement.lang=zh?'zh-CN':'en';
  $('title').textContent=tr('Check a gray frame joint','检查灰框接头');
  $('intro').textContent=tr('Compare two close-ups of the left frame. Keep one tag on each side of the seam in view.','对比左侧框的两张接头近照。接缝两侧各保留一枚码。');
  $('rule').textContent=tr('Gray frame halves must show the same face. Colored shelf halves may mix faces when the notch direction matches.','灰框两半须同面。彩色层板允许混面，但缺口方向要一致。');
  const photos=['same-end','flipped'];
  let sample=0,result=false,model=false,animation=0,revision=0,cachedMesh;
  for(let i=0;i<2;i++){
    const b=document.createElement('button');
    b.textContent=i?'024 + 031':'025 + 031';
    b.onclick=()=>{sample=i;result=false;model=false;revision++;cancelAnimationFrame(animation);draw();};
    $('chooser').append(b);
  }
  function draw(){
    for(const [i,b] of [...$('chooser').children].entries())b.setAttribute('aria-pressed',String(i===sample));
    const img=$('photo'),canvas=$('stage');img.hidden=model;canvas.hidden=!model;
    img.src=photos[sample]+'.webp';img.alt=tr('Two numbered tags beside the frame seam','灰框接缝两侧的两枚编号标签');
    canvas.setAttribute('aria-label',tr('CAD demonstration of turning over the right-hand frame half.','将右手边灰框半件翻面的 CAD 动作。'));
    $('caption').textContent=model?tr('CAD demonstration · right-hand half','CAD 动作示范 · 右手边半件'):tr('Recorded close-up · left frame','实拍近照 · 左侧框');
    $('finding').hidden=$('why').hidden=!result;
    $('finding').textContent=sample?tr('Flip the right-hand half','把右手边这块翻面'):tr('Same face and matching end','同面，连接端相符');
    const tone=result?(sample?'adjust':'ok'):'neutral';
    ShelfFeedback.mark($('finding'),tone);feedback.show('gray/'+sample,tone);
    $('why').textContent=sample?tr('024 shows face B on the left. 031 shows face A on the right. Lift the right-hand half clear, flip it over and align the joint again.','左边的 024 是 B 面，右边的 031 是 A 面。先将右手边这块退出接头，再翻面、重新对齐。'):
      tr('025 and 031 identify face A at the same connector end. Now check the seam by eye and confirm it yourself.','025 与 031 对应两块的 A 面和同一连接端。再看接缝是否贴齐，由你确认。');
    const action=$('action');action.hidden=result && !sample;
    action.textContent=!result?tr('Show recorded check','看已记录的复核'):model?tr('Back to the photo','返回接头近照'):tr('Show the flip','看翻面动作');
    action.onclick=()=>{if(!result){result=true;draw();return;}
      model=!model;const version=++revision;cancelAnimationFrame(animation);draw();if(model)playFlip(version);
    };
  }
  async function playFlip(version){
    try {
      if(!cachedMesh) {const response=await fetch('half.json');if(!response.ok)throw Error();cachedMesh=await response.json();}
      if(version!==revision)return;
      const cv=$('stage'),ctx=cv.getContext('2d'),began=performance.now();
      const smooth=t=>{t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);};
      function paint(now){
        if(version!==revision || cv.hidden)return;
        const dt=matchMedia('(prefers-reduced-motion: reduce)').matches?3500:now-began;
        const w=cv.clientWidth,h=cv.clientHeight,dpr=Math.min(2,devicePixelRatio);
        cv.width=Math.round(w*dpr);cv.height=Math.round(h*dpr);ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,w,h);
        const withdraw=smooth(dt/750),angle=Math.PI*smooth((dt-950)/1800);
        const c=Math.cos(angle),s=Math.sin(angle),size=Math.min(w*.44,h*.51),cx=w*(.46+.08*withdraw);
        // Face A, photographed with +U pointing left; the 024/031 cue turns about U.
        const points=cachedMesh.points.map(([u,v,n])=>{
          const y=v*c-n*s,z=v*s+n*c,k=1/(1-.14*z);
          return [cx-u*k*size,h*.48+y*k*size,z];
        });
        const ordered=cachedMesh.triangles.map(idx=>idx.map(i=>points[i])).sort((a,b)=>(a[0][2]+a[1][2]+a[2][2])-(b[0][2]+b[1][2]+b[2][2]));
        for(const [a,b,c] of ordered){
          const side=Math.max(a[2],b[2],c[2])-Math.min(a[2],b[2],c[2]);
          ctx.fillStyle=side<.06?'#8e9690':'#637169';ctx.strokeStyle=ctx.fillStyle;ctx.lineWidth=.6;
          ctx.beginPath();ctx.moveTo(a[0],a[1]);ctx.lineTo(b[0],b[1]);ctx.lineTo(c[0],c[1]);ctx.closePath();ctx.fill();ctx.stroke();
        }
        if(dt>=750&&dt<2950){ctx.beginPath();ctx.setLineDash([4,4]);ctx.strokeStyle='#fff8dd';ctx.lineWidth=2;ctx.moveTo(cx-size*.51,h*.48);ctx.lineTo(cx+size*.51,h*.48);ctx.stroke();ctx.setLineDash([]);}
        if(dt<2950)animation=requestAnimationFrame(paint);
      }
      paint(began);
    } catch {
      if(revision===version){model=false;draw();$('caption').textContent=tr('Model unavailable. Use the instruction below.','模型暂未载入，可查看下方动作说明。');}
    }
  }
  draw();
})();
