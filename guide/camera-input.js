'use strict';
(() => {
  function capabilities(device) {
    try { return device.getCapabilities?.() || {}; } catch { return {}; }
  }
  function facing(device, caps) {
    const actual=device.getSettings?.().facingMode;
    const modes=Array.isArray(caps.facingMode)?caps.facingMode:[caps.facingMode];
    const name = device.label || '';
    if (actual==='user' || /\bfront\b|selfie|前置|前摄|前攝|前鏡|正面|朝前/i.test(name)) return 'front';
    if (actual==='environment' || /\b(back|rear|environment)\b|后置|後置|后摄|後攝|后镜|後鏡|背面|朝后|朝後/i.test(name)) return 'rear';
    if(modes.includes('user')&&!modes.includes('environment'))return 'front';
    if(modes.includes('environment')&&!modes.includes('user'))return 'rear';
    return 'unknown';
  }
  const choiceKey='ent207.rearChoice.v1';
  const wideName=name=>/ultra[ -]?wide|超广角|超廣角/i.test(name||'');
  function rearOptions(){
    return {facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:960},
      aspectRatio:{ideal:4/3},resizeMode:{ideal:'none'}};
  }
  function remembered(){
    try{const c=JSON.parse(sessionStorage.getItem(choiceKey)||'null');
      return c && typeof c.id==='string' && (c.zoom===null || (Number.isFinite(c.zoom)&&c.zoom>=0)) ? c:null;
    }catch{return null;}
  }
  function remember(id,zoom){
    try{sessionStorage.setItem(choiceKey,JSON.stringify({id,zoom}));return true;}catch{return false;}
  }
  async function setZoom(track,value){
    const caps=capabilities(track).zoom;
    if(!Number.isFinite(value)||!caps||value<caps.min-.001||value>caps.max+.001||!track.applyConstraints)return false;
    const constraints={...(track.getConstraints?.()||{})};
    const advanced=(constraints.advanced||[]).filter(c=>!Object.hasOwn(c,'zoom'));
    delete constraints.zoom;delete constraints.advanced;
    const matches=()=>{const read=track.getSettings?.().zoom;return Number.isFinite(read)&&Math.abs(read-value)<=.015;};
    if(matches())return true;
    for(const request of [{...constraints,advanced:[...advanced,{zoom:value}]},{...constraints,zoom:{exact:value},advanced}]){
      try{await track.applyConstraints(request);}catch{continue;}
      for(let i=0;i<4;i++){
        if(matches())return true;
        if(track.readyState==='ended')return false;
        await new Promise(resolve=>setTimeout(resolve,40));
      }
    }
    return false;
  }
  async function openPreferred(current=()=>true){
    const c=remembered(),video=rearOptions();
    if(c && current()){
      let selected;
      try{
        selected=await navigator.mediaDevices.getUserMedia({audio:false,video:{...video,facingMode:{exact:'environment'},
          ...(c.id?{deviceId:{exact:c.id}}:{}),...(c.zoom!==null?{zoom:true}:{})}});
        const t=selected.getVideoTracks()[0],actual=t?.getSettings?.().deviceId;
        if(t && current() && (!c.id||!actual||actual===c.id) && facing(t,capabilities(t))!=='front' &&
            (c.zoom===null||await setZoom(t,c.zoom)) && current())return selected;
      }catch{ /* Continue with the browser's normal outward-facing camera. */ }
      selected?.getTracks().forEach(t=>t.stop());
    }
    if(!current())throw Error('Camera request cancelled');
    const fallback=await navigator.mediaDevices.getUserMedia({audio:false,video:c?{...video,facingMode:{exact:'environment'}}:video});
    const t=fallback.getVideoTracks()[0];
    if(!current() || (c && (!t || facing(t,capabilities(t))==='front'))){
      fallback.getTracks().forEach(track=>track.stop());throw Error('Rear camera unavailable');
    }
    return fallback;
  }

  // Some phones must close the old lens before opening another. Recover the
  // previous rear input if the advertised alternative cannot actually be opened.
  async function openRear(choice, video, current) {
    for (const id of [choice.next, choice.previous]) {
      if (!id || !current()) break;
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({audio:false, video:{
          ...video, deviceId:{exact:id}, facingMode:{exact:'environment'}
        }});
        const track = stream.getVideoTracks()[0], actual = track?.getSettings?.().deviceId;
        if (current() && track && (!actual || actual===id) && facing(track,capabilities(track))!=='front') {
          if(id===choice.next)remember(id,null);
          return stream;
        }
      } catch { /* Try the previous rear input if the attempted change failed. */ }
      stream?.getTracks().forEach(t=>t.stop());
    }
    throw new Error('No verified rear camera available');
  }

  function lensControl(button, tr, switchDevice, resetView) {
    let track=null, alternatives=[], zoomOption=null, activeId='', busy=false, zoomFailed=false;
    let facts={facing:'unknown',otherRear:0,unknownInputs:0,zoomMin:null,zoomMax:null,zoom:null};
    button.type='button';
    function update() {
      if (!track) { button.hidden=true; return; }
      const zoom = track.getSettings?.().zoom;
      facts.zoom = Number.isFinite(zoom) ? zoom : null;
      zoomOption = null;
      if (facts.facing==='rear' && !zoomFailed && Number.isFinite(facts.zoomMin) && facts.zoomMin>=0 &&
          Number.isFinite(zoom) && track.applyConstraints) {
        if (zoom>facts.zoomMin+.02) zoomOption=facts.zoomMin;
        else if (facts.zoomMin<.98 && facts.zoomMax>=1) zoomOption=1;
      }
      button.hidden=false;
      button.disabled=busy;
      if (zoomOption!==null) button.textContent=facts.zoomMin>0 && facts.zoomMin<1 && zoomOption>0 && zoomOption<=1?
        tr('View at ','取景 ')+Number(zoomOption.toFixed(2))+'×':tr('Widen view','扩大取景');
      else if (alternatives.length) button.textContent=alternatives[0].wide?
        tr('Try rear ultrawide','试用后置超广角'):tr('Other rear camera','切换后置镜头');
      else button.textContent=tr('Check wide view','检查广角');
    }
    function clear() {
      track=null;activeId='';alternatives=[];zoomOption=null;busy=false;zoomFailed=false;
      facts={facing:'unknown',otherRear:0,unknownInputs:0,zoomMin:null,zoomMax:null,zoom:null};
      button.hidden=true;
    }
    async function bind(nextTrack) {
      clear();if (!nextTrack) return;
      track=nextTrack;
      const caps=capabilities(track);
      activeId=track.getSettings?.().deviceId || '';
      facts.facing=facing(track,caps);
      facts.zoomMin=Number.isFinite(caps.zoom?.min)?caps.zoom.min:null;
      facts.zoomMax=Number.isFinite(caps.zoom?.max)?caps.zoom.max:null;
      let devices=[];
      try { devices=await navigator.mediaDevices.enumerateDevices(); } catch { /* Zoom may still be available. */ }
      if (track!==nextTrack) return;
      const rear = [], seenIds=new Set();
      for (const device of devices) {
        if (device.kind!=='videoinput' || !device.deviceId || seenIds.has(device.deviceId)) continue;
        seenIds.add(device.deviceId);
        const position=facing(device,capabilities(device));
        if (device.deviceId===activeId) {
          if (facts.facing==='unknown') facts.facing=position;
          continue;
        }
        if (position==='rear') rear.push({id:device.deviceId,wide:wideName(device.label)});
        if (position==='unknown') facts.unknownInputs++;
      }
      facts.otherRear=activeId && facts.facing==='rear'?rear.length:null;
      // Use only positively identified back-facing alternatives. A numeric
      // device label or an entry's position in the list says nothing about its optics.
      if (activeId && facts.facing==='rear') {
        const ring=rear.concat({id:activeId,wide:false}).sort((a,b)=>a.id.localeCompare(b.id));
        const i=ring.findIndex(d=>d.id===activeId);
        alternatives=ring.slice(i+1).concat(ring.slice(0,i));
        if (!wideName(track.label)) alternatives.sort((a,b)=>Number(b.wide)-Number(a.wide));
      }
      update();
    }
    async function activate() {
      if (!track || busy || button.disabled) return false;
      const owner=track, zoom=zoomOption;
      if (zoom===null) {
        if (!alternatives.length) { window.dispatchEvent(new Event('ent207-inspect-lenses'));return true; }
        return switchDevice({next:alternatives[0].id,previous:activeId});
      }
      busy=true;update();
      try {
        if(!await setZoom(owner,zoom))throw Error('Zoom not applied');
        if (track!==owner) return false;
        remember(activeId,zoom);
        resetView();
        return true;
      } catch {
        if (track===owner) zoomFailed=true;
        return false;
      } finally {
        if (track===owner) { busy=false;update(); }
      }
    }
    button.onclick=activate;
    return {bind,clear,update,activate,get facts(){return {...facts};}};
  }
  // One analysis may be in flight. Include its time in the sampling period
  // and leave proportional breathing room when processing itself is slow.
  function delay(start, period) {
    const elapsed=Math.max(0,performance.now()-start);
    return Math.ceil(Math.max(16,period-elapsed,Math.min(period,elapsed*.4)));
  }
  function workerURL(path){
    const url=new URL(path,document.baseURI);url.searchParams.set('v','8');
    try{if(localStorage.getItem('ent207.camera.simd.v1')==='on')url.searchParams.set('engine','simd');}catch{}
    return url;
  }
  // Size the picture, tag plane and animation together. Analysis always sees the entire source.
  function frameStage(host,stage,button,tr,onResize){
    const key='ent207.camera.previewFit.v1';
    let width=0,height=0,fill=true;
    function refresh(){try{fill=sessionStorage.getItem(key)!=='full';}catch{}resize();}
    function resize(){
      const cw=host.clientWidth,ch=host.clientHeight;
      if(width>0&&height>0&&cw&&ch){
        const scale=fill?Math.max(cw/width,ch/height):Math.min(cw/width,ch/height);
        stage.style.width=width*scale+'px';stage.style.height=height*scale+'px';
      }
      host.dataset.framing=fill?'fill':'full';
      button.textContent=fill?tr('Full frame','看全幅'):tr('Fill view','铺满取景');
      button.setAttribute('aria-label',fill?tr('Show the full camera frame','查看相机全幅画面'):tr('Fill the camera view','铺满相机取景框'));
      onResize?.();
    }
    button.type='button';button.onclick=()=>{
      fill=!fill;try{sessionStorage.setItem(key,fill?'fill':'full');}catch{}resize();
    };
    new ResizeObserver(resize).observe(host);
    refresh();
    return {refresh,setSize(w,h){if(w!==width||h!==height){width=w;height=h;resize();}},setLanguage:resize};
  }
  window.ShelfCamera={lensControl,frameStage,openRear,openPreferred,rearOptions,remember,remembered,setZoom,wideName,facing,capabilities,delay,workerURL};
})();
