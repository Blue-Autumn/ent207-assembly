'use strict';
// Only media and marker IDs enter this helper. The guide owns task progression.
function createPickupScanner(stage, targets, onChange, onError, onFrame) {
  const view = document.createElement('div');
  view.className = 'pickup-lens';
  view.hidden = true;
  const video = document.createElement('video');
  video.autoplay = true;
  video.muted = true;
  video.playsInline = true;
  const ns = 'http://www.w3.org/2000/svg';
  const outlines = document.createElementNS(ns, 'svg');
  outlines.setAttribute('aria-hidden','true');
  const lensButton=document.createElement('button');
  lensButton.className='pickup-lens-choice';lensButton.hidden=true;
  view.append(video, outlines,lensButton);
  stage.append(view);

  const buffer = document.createElement('canvas');
  const context = buffer.getContext('2d',{willReadFrequently:true});
  const centerBuffer=document.createElement('canvas'), centerContext=centerBuffer.getContext('2d',{willReadFrequently:true});
  const seen = new Map();
  const parts = new Map();
  let holdMs = 600, previousFrame = 0;
  let generation = 0, running = false, worker = null, stream = null;
  let nextFrame = 0, expiry = 0, registry = null, observed = null;
  let epoch = 0, capturedEpoch = 0, capturedAt = 0;
  let postedAt=0, lastDetail=-Infinity, lastCost=0, needDetail=true, targetIds=null;
  const lens=ShelfCamera.lensControl(lensButton,(en,zh)=>AssemblyText.english?en:zh,
    choice=>{publish(null);return start(null,choice);},()=>{publish(null);resetTarget();});

  function resetTarget() {
    epoch++;
    clearTimeout(expiry);
    seen.clear();
    parts.clear();
    observed = null;
    previousFrame = 0;
    holdMs = 600;
    lastDetail=-Infinity;lastCost=0;needDetail=true;targetIds=null;
    outlines.replaceChildren();
  }

  function stop() {
    running = false;
    generation++;
    clearTimeout(nextFrame);
    worker?.terminate();
    worker = null;
    stream?.getTracks().forEach(track=>track.stop());
    stream = null;
    video.srcObject = null;
    view.hidden = true;
    lens.clear();
    resetTarget();
  }
  function publish(hit) {
    const changed = observed?.id!==hit?.id || observed?.matches!==hit?.matches;
    observed = hit;
    if (changed) onChange(hit);
  }
  function updateReadout() {
    const now = performance.now();
    for (const [id, reading] of seen) {
      if (now-reading.at >= holdMs) seen.delete(id);
    }
    for (const [part, reading] of parts) {
      if (now-reading.at >= holdMs) parts.delete(part);
    }
    // A second board can stay visible while the desired tag dips out for one read.
    // Retain the current part within its evidence window and prefer a confirmed target.
    const valid = [...parts.values()].filter(p=>p.count>=2 && p.at-p.first>=(p.matches?130:260));
    valid.sort((a,b)=>Number(b.matches)-Number(a.matches) ||
      Number(b.part===observed?.part)-Number(a.part===observed?.part) || a.first-b.first || a.id-b.id);
    const best = valid[0];
    const anchor = best && observed?.part===best.part ? seen.get(observed.id) : null;
    publish(anchor ? {...best,id:anchor.id,face:anchor.face} : best || null);
    clearTimeout(expiry);
    if (seen.size) {
      const firstExpiry = Math.min(...[...seen.values()].map(reading=>reading.at+holdMs));
      expiry = setTimeout(()=>{if(running){updateReadout();paintOutlines()}},Math.max(1,Math.ceil(firstExpiry-now)));
    }
  }
  function paintOutlines() {
    const paths = [];
    for (const item of seen.values()) {
      if (!parts.has(item.part) || (observed?.matches && !item.matches)) continue;
      const outline = document.createElementNS(ns,'polygon');
      outline.setAttribute('points',item.corners.map(c=>c.x+','+c.y).join(' '));
      outline.setAttribute('class',item.matches?'target':'other');
      paths.push(outline);
    }
    outlines.replaceChildren(...paths);
  }
  function acceptFrame(data) {
    const now = capturedAt, done = new Set(), frameParts = new Map();
    const wanted = targets();
    if (previousFrame) {
      // Slow phones may only return two or three reads per second.
      // A stalled camera still loses its confirmation within one second.
      const interval = Math.min(1000, Math.max(600,(now-previousFrame)*2.5));
      holdMs = Math.round(holdMs*.65 + interval*.35);
    }
    previousFrame = now;
    for (const marker of data.markers) {
      if (marker.hammingDistance!==0 || done.has(marker.id)) continue;
      done.add(marker.id);
      const entry = registry[marker.id];
      if (!entry) continue;
      const part = entry[0];
      const reading = {id:marker.id, part, face:entry[1], corners:marker.corners,
        matches:wanted.includes(part), at:now};
      seen.set(marker.id,reading);
      const chosen = frameParts.get(part);
      const previousId = parts.get(part)?.id;
      if (!chosen || marker.id===previousId || (chosen.id!==previousId && marker.id<chosen.id)) {
        frameParts.set(part,reading);
      }
    }
    // Several markers from one half in one exposure count as a single observation.
    for (const [part,reading] of frameParts) {
      const previous = parts.get(part);
      const recent = previous && now-previous.at<holdMs;
      parts.set(part,{...reading, first:recent?previous.first:now, count:recent?previous.count+1:1});
    }
    outlines.setAttribute('viewBox','0 0 '+data.width+' '+data.height);
    updateReadout();
    paintOutlines();
  }
  function fail(ticket) {
    if (ticket!==generation) return;
    stop();
    onError();
  }
  async function start(suppliedStream,choice) {
    stop();
    running = true;
    view.hidden = false;
    const ticket = generation;
    try {
      const videoOptions={facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}};
      const camera = suppliedStream || (choice ? await ShelfCamera.openRear(choice,videoOptions,()=>ticket===generation) :
        await navigator.mediaDevices.getUserMedia({audio:false,video:videoOptions}));
      if (ticket!==generation) { camera.getTracks().forEach(track=>track.stop()); return; }
      stream = camera;
      lens.bind(stream.getVideoTracks()[0]);
      for (const track of stream.getVideoTracks()) track.addEventListener('ended',()=>fail(ticket),{once:true});
      video.srcObject = stream;
      await video.play();
      if (ticket!==generation) return;
      if (!registry) {
        const response = await fetch('../field/tags.json');
        if (!response.ok) throw Error('Tag map unavailable');
        registry = await response.json();
      }
      if (ticket!==generation) return;
      worker = new Worker(ShelfCamera.workerURL('../field/reader.js'));
      worker.onerror = ()=>fail(ticket);
      function capture() {
        if (ticket!==generation) return;
        if (video.readyState<2 || !video.videoWidth) { nextFrame=setTimeout(capture,100); return; }
        try {
          const scale = Math.min(1,850/Math.max(video.videoWidth,video.videoHeight));
          const width = Math.max(1,Math.round(video.videoWidth*scale));
          const height = Math.max(1,Math.round(video.videoHeight*scale));
          if (buffer.width!==width || buffer.height!==height) { buffer.width=width; buffer.height=height; }
          capturedAt=performance.now();
          context.drawImage(video,0,0,width,height);
          const image = context.getImageData(0,0,width,height);
          const message={image},transfer=[image.data.buffer];
          if(scale<.9 && needDetail && capturedAt-lastDetail>=Math.max(140,lastCost*2.5)){
            const vw=video.videoWidth,vh=video.videoHeight;
            const side=Math.min(576,Math.round(Math.min(vw,vh)*.8));
            const x=Math.floor((vw-side)/2),y=Math.floor((vh-side)/2);
            if(centerBuffer.width!==side || centerBuffer.height!==side){centerBuffer.width=side;centerBuffer.height=side;}
            centerContext.drawImage(video,x,y,side,side,0,0,side,side);
            const detail=centerContext.getImageData(0,0,side,side);
            if(!targetIds){const wanted=targets();targetIds=Object.keys(registry).filter(id=>wanted.includes(registry[id][0])).map(Number);}
            message.detail={image:detail,ids:targetIds,x:x*width/vw,y:y*height/vh,scaleX:width/vw,scaleY:height/vh};
            transfer.push(detail.data.buffer);lastDetail=capturedAt;
          }
          capturedEpoch = epoch;
          postedAt=performance.now();
          worker.postMessage(message,transfer);
        } catch { fail(ticket); }
      }
      worker.onmessage = ({data})=>{
        if (ticket!==generation) return;
        const received=performance.now();lastCost=received-capturedAt;
        if (capturedEpoch===epoch) {
          const wanted=targets();
          needDetail=data.usedCenter || !data.markers.some(m=>wanted.includes(registry[m.id]?.[0]));
          const gap=previousFrame?capturedAt-previousFrame:0;
          acceptFrame(data);
          onFrame?.('pickup',{gap,copy:postedAt-capturedAt,read:received-postedAt,ui:performance.now()-received,
            size:[data.width,data.height],source:[video.videoWidth,video.videoHeight],ids:data.markers.length,
            center:data.usedCenter===true,detector:data.detector,lens:lens.facts});
        }
        nextFrame = setTimeout(capture,ShelfCamera.delay(capturedAt,80));
      };
      capture();
    } catch { fail(ticket); }
  }
  function match() {
    if (!running || !observed?.matches || performance.now()-observed.at>=holdMs) return null;
    return targets().includes(observed.part) ? observed : null;
  }
  function takeStream() {
    const camera = stream;
    stream = null;
    stop();
    return camera;
  }
  return {start,stop,resetTarget,takeStream,match,setLanguage:lens.update,get active(){return running},get hit(){return observed}};
}
