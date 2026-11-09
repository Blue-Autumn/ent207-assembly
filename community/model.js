/* Same six primitives, lighting, pitch and explosion distances as CommunityRaster. */
export function showModel(canvas, parts, poster = false) {
  const ctx = canvas.getContext('2d');
  const extents = [0,1,2].map((axis) => {
    const bottom = axis === 1;
    const lows = parts.map(p => p[1+axis] - (bottom ? 0 : p[4+axis]/2));
    const highs = parts.map(p => p[1+axis] + (bottom ? p[4+axis] : p[4+axis]/2));
    return [Math.min(...lows), Math.max(...highs)];
  });
  const center = extents.map(([lo,hi]) => (lo+hi)/2);
  const height = extents[1][1] - extents[1][0];
  const radius = Math.max(...parts.map(p => Math.hypot(Math.abs(p[1]-center[0])+p[4]/2, Math.abs(p[3]-center[2])+p[6]/2)));
  const faces = [];
  for (const [shape, x,y,z, width,height,depth, hex] of parts) {
    const w=width/2, h=height/2, d=depth/2;
    const origin=[x-center[0],y+h-center[1],z-center[2]];
    const base = [1,3,5].map(i=>parseInt(hex.slice(i,i+2),16));
    function face(normal, ...points) {
      const [nx,ny,nz]=normal;
      const light=Math.min(1.08, Math.max(.55,.77+(.23*ny+.08*nz-.12*nx)/Math.hypot(nx,ny,nz)));
      const [r,g,b]=base.map(c=>Math.min(255,Math.round(c*light)));
      faces.push({normal,origin,points,color:(0xff000000|b<<16|g<<8|r)>>>0});
    }
    const up=[0,1,0], down=[0,-1,0], front=[0,0,1], back=[0,0,-1];
    if (shape==='box' || shape==='wedge') {
      const a=[-w,-h,-d], b=[w,-h,-d], c=[w,-h,d], e=[-w,-h,d];
      const A=[-w,h,-d], B=[w,h,-d], C=[w,h,d], E=[-w,h,d];
      face(down,a,b,c,e);face(back,a,b,B,A);
      if(shape==='wedge'){face([0,d,h],A,B,c,e);face([-1,0,0],a,A,e);face([1,0,0],b,B,c);}
      else {face(up,A,B,C,E);face(front,e,c,C,E);face([-1,0,0],a,A,E,e);face([1,0,0],b,B,C,c);}
      continue;
    }
    const axial = shape==='wheel' || shape==='hoop';
    const hollow = shape==='hoop' || shape==='ring';
    const plus = axial?front:up, minus=axial?back:down;
    function point(i, r, end) {
      const a=(i%48)*Math.PI/24, u=r*Math.cos(a), v=r*Math.sin(a);
      return axial ? [w*u,h*v,d*end] : [w*u,h*end,d*v];
    }
    for(let i=0;i<48;i++){
      const angle=(i+.5)*Math.PI/24;
      const nx=Math.cos(angle)/w, nv=Math.sin(angle)/(axial?h:d);
      const normal=axial?[nx,nv,0]:[nx,0,nv];
      const a=point(i,1,-1),b=point(i+1,1,-1),c=point(i+1,1,1),e=point(i,1,1);
      face(normal,a,b,c,e);
      if(hollow){
        const ai=point(i,.7,-1),bi=point(i+1,.7,-1),ci=point(i+1,.7,1),ei=point(i,.7,1);
        face(plus,e,c,ci,ei);face(minus,a,b,bi,ai);face(normal.map(v=>-v),ai,bi,ci,ei);
      } else {face(plus,point(i,0,1),e,c);face(minus,point(i,0,-1),a,b);}
    }
  }
  const px=new Float64Array(4),py=new Float64Array(4),pz=new Float64Array(4);
  const ps=.39,pc=Math.sqrt(1-ps*ps);
  const reduce=matchMedia('(prefers-reduced-motion:reduce)');
  let width=0,h=0,frame,pixels,depth;
  let yaw=.63, drag=null, raf=0, last='', active=true,visible=true;
  let begin=performance.now(), lastPaint=-Infinity;
  function size() {
    const box=canvas.getBoundingClientRect(), ratio=Math.min(1.5,devicePixelRatio||1,540/Math.max(1,box.width));
    width=Math.max(1,Math.round(box.width*ratio));h=Math.max(1,Math.round(box.height*ratio));
    canvas.width=width;canvas.height=h;frame=ctx.createImageData(width,h);
    pixels=new Uint32Array(frame.data.buffer);depth=new Float32Array(width*h);last='';schedule();
  }
  function triangle(b,c,color){
    const dx1=px[b]-px[0],dy1=py[b]-py[0],dx2=px[c]-px[0],dy2=py[c]-py[0];
    const area=dx1*dy2-dy1*dx2;
    if(Math.abs(area)<.0001)return;
    const x0=Math.max(0,Math.ceil(Math.min(px[0],px[b],px[c])-.5)),x1=Math.min(width-1,Math.floor(Math.max(px[0],px[b],px[c])-.5));
    const y0=Math.max(0,Math.ceil(Math.min(py[0],py[b],py[c])-.5)),y1=Math.min(h-1,Math.floor(Math.max(py[0],py[b],py[c])-.5));
    const ux=dy2/area,uy=-dx2/area,vx=-dy1/area,vy=dx1/area;
    const dz1=pz[b]-pz[0],dz2=pz[c]-pz[0],dz=ux*dz1+vx*dz2;
    for(let row=y0;row<=y1;row++){
      const dx=x0+.5-px[0],dy=row+.5-py[0];
      let u=dx*ux+dy*uy,v=dx*vx+dy*vy,z=pz[0]+u*dz1+v*dz2,idx=row*width+x0;
      for(let col=x0;col<=x1;col++,idx++,u+=ux,v+=vx,z+=dz){
        if(u>=-.00001 && v>=-.00001 && u+v<=1.00001 && z>depth[idx]){pixels[idx]=color;depth[idx]=z;}
      }
    }
  }
  function paint(separation){
    const key=yaw+','+separation;
    if(!frame || key===last)return;
    last=key; pixels.fill(0);depth.fill(-Infinity);
    const scale=Math.min(width*.9/(2*radius),h*.86/(height*pc+2*radius*ps));
    const cs=Math.cos(yaw),sn=Math.sin(yaw),spread=1+.29*separation;
    for(const {normal,origin,points,color} of faces){
      if((sn*normal[0]+cs*normal[2])*pc+normal[1]*ps<=0)continue;
      for(let i=0;i<points.length;i++){
        const p=points[i],x=origin[0]*spread+p[0],y=origin[1]*spread+p[1],z=origin[2]*spread+p[2];
        const near=sn*x+cs*z;
        px[i]=width*.5+(cs*x-sn*z)*scale;
        py[i]=h*.51+(near*ps-y*pc)*scale;
        pz[i]=near*pc+y*ps;
      }
      for(let i=2;i<points.length;i++)triangle(i-1,i,color);
    }
    ctx.putImageData(frame,0,0);
  }
  const smooth=n=>{const t=Math.max(0,Math.min(1,n));return t*t*(3-2*t);};
  function tick(now){
    raf=0;
    if(!active || !visible || document.hidden)return;
    const t=reduce.matches?0:((drag?.time??now)-begin)%4800;
    if(now-lastPaint>31 || drag || !last){
      paint(t<700?0:t<1460?smooth((t-700)/760):t<1780?1:1-smooth((t-1780)/1480));
      lastPaint=now;
    }
    if(!reduce.matches && !drag)schedule();
  }
  function schedule(){if(!raf && active && visible && !document.hidden)raf=requestAnimationFrame(tick);}
  canvas.onpointerdown=e=>{
    if(!e.isPrimary || e.button!==0)return;
    drag={id:e.pointerId,x:e.clientX,yaw,time:performance.now()};canvas.setPointerCapture(e.pointerId);
  };
  canvas.onpointermove=e=>{
    if(drag?.id!==e.pointerId)return;
    yaw=drag.yaw+(e.clientX-drag.x)*.012;schedule();
  };
  canvas.onpointerup=canvas.onpointercancel=e=>{
    if(drag?.id!==e.pointerId)return;
    begin+=performance.now()-drag.time;drag=null;schedule();
  };
  canvas.onkeydown=e=>{
    if(e.key!=='ArrowLeft' && e.key!=='ArrowRight')return;
    e.preventDefault();yaw+=e.key==='ArrowLeft'?-.18:.18;schedule();
  };
  if (poster) {
    width=canvas.width=260;h=canvas.height=180;
    frame=ctx.createImageData(width,h);
    pixels=new Uint32Array(frame.data.buffer);depth=new Float32Array(width*h);
    paint(0);return canvas.toDataURL('image/png');
  }
  const onVisible = () => schedule();
  const observer=new IntersectionObserver(([entry])=>{visible=entry.isIntersecting;schedule();});
  observer.observe(canvas);
  const resize=new ResizeObserver(size);resize.observe(canvas);
  document.addEventListener('visibilitychange',onVisible);reduce.addEventListener('change',onVisible);
  size();
  return ()=>{active=false;cancelAnimationFrame(raf);resize.disconnect();observer.disconnect();
    document.removeEventListener('visibilitychange',onVisible);reduce.removeEventListener('change',onVisible);};
}
