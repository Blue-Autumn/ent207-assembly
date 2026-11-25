'use strict';
(() => {
  const id = new URLSearchParams(location.search).get('id');
  if (!['stool','lamp','recordplayer','bridge','bookstand','tray','xylophone','drawers','plantstand','organizer'].includes(id)) return;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const stage = document.querySelector('#stage');
  const canvas = document.querySelector('#scene');
  const source = document.createElement('script');
  source.src = id + '.js';
  source.onload = () => start(window.conceptModel);
  document.head.append(source);

  const clamp = t => Math.max(0,Math.min(1,t));
  function smooth(t) { t=clamp(t); return t*t*(3-2*t); }
  // Separate, settle in order, then dwell on the completed object.
  function progress(ms) {
    const time = ms % 4050;
    if (time < 750) return 1 - smooth(time/750);
    if (time < 900) return 0;
    if (time < 2850) return smooth((time-900)/1950);
    return 1;
  }

  function start(model) {
    stage.style.backgroundImage = 'url("'+id+'-light.png")';
    let draw;
    try { draw = gpu(model,canvas); } catch (error) { console.warn('Concept graphics:',String(error)); }
    if (!draw) {
      // A WebView without an active GPU still plays the same authored sequence.
      const fallback = document.createElement('canvas');
      canvas.replaceWith(fallback);
      draw = cpu(model,fallback);
    }
    let raf=0, timer=0, elapsed=0, previous=null, active=true;
    const hasSpin = model.groups.some(group => group.spin);
    function paint() {
      const now=performance.now();
      if(previous!==null)elapsed+=now-previous;
      previous=now;
      const t=reduced.matches?0:elapsed;
      const p=progress(t),angle=t/1000*2*Math.PI*.54;
      for (const group of model.groups) {
        const fraction=clamp((p-group.window[0])/(group.window[1]-group.window[0]));
        group.separation=1-smooth(fraction);
        group.opacity=group.fade?smooth(fraction/.27):1;
      }
      draw(angle);
      if(!window.conceptReady && stage.clientWidth>0) {
        window.conceptReady=true;
        document.title='concept-ready';
      }
      if (!reduced.matches && active && !document.hidden) {
        const hold=t%4050;
        if(!hasSpin && hold>=2850)timer=setTimeout(()=>{raf=requestAnimationFrame(paint)},4050-hold);
        else raf=requestAnimationFrame(paint);
      }
    }
    function restart() {
      cancelAnimationFrame(raf);clearTimeout(timer);previous=null;
      if(active&&!document.hidden)paint();
    }
    window.setConceptActive=value=>{active=!!value;restart()};
    new ResizeObserver(restart).observe(stage);
    document.addEventListener('visibilitychange',restart);
    reduced.addListener(restart);
    restart();
  }

  function gpu(model,canvas) {
    const gl=canvas.getContext('webgl',{alpha:true,antialias:true,depth:true,premultipliedAlpha:true});
    if(!gl)return null;
    const vertex=`
      attribute vec3 aPoint, aColor;
      uniform vec3 uShift;
      uniform vec2 uCentre, uTurn;
      uniform float uScale;
      varying vec3 colour;
      void main(){
        vec3 p=aPoint; vec2 radial=p.xz-vec2(-7.0,0.0);
        p.xz=vec2(-7.0,0.0)+mat2(uTurn.x,uTurn.y,-uTurn.y,uTurn.x)*radial;
        p+=uShift;
        vec2 projected=vec2((p.x-p.z)*.866,(p.x+p.z)*.36-p.y*.92);
        vec2 position=(projected-uCentre)*uScale*2.0;
        gl_Position=vec4(position.x,.02-position.y,-dot(p,vec3(1.0,.72/.92,1.0))/256.0,1.0);
        colour=aColor/255.0;
      }`;
    const fragment=`precision mediump float;varying vec3 colour;uniform float uAlpha;
      void main(){gl_FragColor=vec4(colour*uAlpha,uAlpha);}`;
    function shader(kind,text) {
      const s=gl.createShader(kind);gl.shaderSource(s,text);gl.compileShader(s);
      if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));
      return s;
    }
    const pipeline=gl.createProgram();
    gl.attachShader(pipeline,shader(gl.VERTEX_SHADER,vertex));
    gl.attachShader(pipeline,shader(gl.FRAGMENT_SHADER,fragment));gl.linkProgram(pipeline);
    if(!gl.getProgramParameter(pipeline,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(pipeline));
    gl.useProgram(pipeline);
    let offset=0;
    for(const group of model.groups){group.first=offset;group.count=group.vertices.length/6;offset+=group.count;}
    const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(model.groups.flatMap(g=>g.vertices)),gl.STATIC_DRAW);
    for(const [attr,byte] of [['aPoint',0],['aColor',12]]) {
      const loc=gl.getAttribLocation(pipeline,attr);gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc,3,gl.FLOAT,false,24,byte);
    }
    const uniforms={};
    for(const name of ['uShift','uCentre','uTurn','uScale','uAlpha'])uniforms[name]=gl.getUniformLocation(pipeline,name);
    gl.uniform2fv(uniforms.uCentre,model.center);gl.uniform1f(uniforms.uScale,model.unit);
    gl.enable(gl.DEPTH_TEST);gl.depthFunc(gl.LEQUAL);gl.enable(gl.BLEND);gl.blendFunc(gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
    canvas.addEventListener('webglcontextlost',event=>event.preventDefault());
    canvas.addEventListener('webglcontextrestored',()=>location.reload());
    canvas.dataset.renderer='webgl';
    return angle=>{
      const size=drawableSize(canvas);
      if(canvas.width!==size||canvas.height!==size){canvas.width=canvas.height=size;gl.viewport(0,0,size,size)}
      gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
      for(const g of model.groups){
        if(g.opacity<=0)continue;
        gl.uniform3f(uniforms.uShift,g.offset[0]*g.separation,g.offset[1]*g.separation,g.offset[2]*g.separation);
        gl.uniform2f(uniforms.uTurn,g.spin?Math.cos(angle):1,g.spin?Math.sin(angle):0);
        gl.uniform1f(uniforms.uAlpha,g.opacity);
        gl.drawArrays(gl.TRIANGLES,g.first,g.count);
      }
    };
  }
  function drawableSize(canvas){return Math.max(1,Math.min(1100,Math.round(canvas.clientWidth*devicePixelRatio)))}

  function cpu(model,canvas) {
    const ctx=canvas.getContext('2d');
    canvas.dataset.renderer='canvas';
    const triangles=[];
    for(const g of model.groups){
      for(let i=0;i<g.vertices.length;i+=18){
        const v=g.vertices;
        triangles.push({g,points:[v.slice(i,i+3),v.slice(i+6,i+9),v.slice(i+12,i+15)],colour:'rgb('+v.slice(i+3,i+6).join(',')+')'});
      }
    }
    return angle=>{
      const size=drawableSize(canvas);
      if(canvas.width!==size||canvas.height!==size)canvas.width=canvas.height=size;
      ctx.clearRect(0,0,size,size);
      const primitives=[];
      for(const tri of triangles){
        if(tri.g.opacity<=0)continue;
        let depth=0;
        const xy=tri.points.map(point=>{
          let [x,y,z]=point;
          if(tri.g.spin){
            const u=x+7,v=z;
            x=-7+u*Math.cos(angle)-v*Math.sin(angle);z=u*Math.sin(angle)+v*Math.cos(angle);
          }
          const g=tri.g,k=g.separation;
          x+=g.offset[0]*k; y+=g.offset[1]*k; z+=g.offset[2]*k;
          depth+=x+y*.72/.92+z;
          return [size*(.5+((x-z)*.866-model.center[0])*model.unit),
                  size*(.49+((x+z)*.36-y*.92-model.center[1])*model.unit)];
        });
        primitives.push({tri,xy,depth});
      }
      primitives.sort((a,b)=>a.depth-b.depth);
      for(const {tri,xy} of primitives){
        ctx.globalAlpha=tri.g.opacity;ctx.fillStyle=tri.colour;ctx.beginPath();
        ctx.moveTo(...xy[0]);ctx.lineTo(...xy[1]);ctx.lineTo(...xy[2]);ctx.closePath();ctx.fill();
      }
      ctx.globalAlpha=1;
    };
  }
})();
