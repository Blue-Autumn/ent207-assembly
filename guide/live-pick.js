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
  const silhouette=createFinderOverlay(outlines),tagMarks=document.createElementNS(ns,'g');
  outlines.append(tagMarks);
  const lensButton=document.createElement('button');
  lensButton.className='pickup-lens-choice';lensButton.hidden=true;
  const picture=document.createElement('div'),fitButton=document.createElement('button');
  picture.className='camera-picture';fitButton.className='preview-fit pickup-fit';
  picture.append(video,outlines);view.append(picture,lensButton,fitButton);
  stage.append(view);
  const language=(en,zh)=>AssemblyText.english?en:zh;
  const framing=ShelfCamera.frameStage(view,picture,fitButton,language);
  const resizeVideo=()=>framing.setSize(video.videoWidth,video.videoHeight);
  video.addEventListener('loadedmetadata',resizeVideo);video.addEventListener('resize',resizeVideo);

  const buffer = document.createElement('canvas');
  const context = buffer.getContext('2d',{willReadFrequently:true});
  const centerBuffer=document.createElement('canvas'), centerContext=centerBuffer.getContext('2d',{willReadFrequently:true});
  const seen = new Map();
  const parts = new Map();
  let frameWidth=0,frameHeight=0;
  let holdMs = 600, previousFrame = 0;
  let generation = 0, running = false, worker = null, stream = null;
  let warming=null,warmTimeout=0,precision=false;
  let nextFrame = 0, expiry = 0, registry = null, observed = null;
  let epoch = 0, capturedEpoch = 0, capturedAt = 0;
  let postedAt=0, lastDetail=-Infinity, lastCost=0, needDetail=true, targetIds=null;
  const lens=ShelfCamera.lensControl(lensButton,language,
    choice=>{publish(null);return start(null,choice);},()=>{publish(null);resetTarget();});

  function resetTarget() {
    epoch++;
    clearTimeout(expiry);
    seen.clear();
    parts.clear();
    observed = null;
    previousFrame = 0;
    holdMs = 600;
    frameWidth=frameHeight=0;
    lastDetail=-Infinity;lastCost=0;needDetail=true;targetIds=null;
    silhouette.clear();tagMarks.replaceChildren();
  }

  function stop() {
    running = false;
    generation++;
    clearTimeout(nextFrame);
    clearTimeout(warmTimeout);warming?.terminate();warming=null;precision=false;
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
    tagMarks.replaceChildren(...paths);
  }
  function acceptFrame(data) {
    if(frameWidth && (frameWidth!==data.width||frameHeight!==data.height)){publish(null);resetTarget();}
    frameWidth=data.width;frameHeight=data.height;
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
    framing.setSize(data.width,data.height);
    silhouette.paint(data.regions,now);
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
      framing.refresh();
      const camera = suppliedStream || (choice ? await ShelfCamera.openRear(choice,ShelfCamera.rearOptions(),()=>ticket===generation) :
        await ShelfCamera.openPreferred(()=>ticket===generation));
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
      let slowFrames=0;
      function useReader(next,precise){
        clearTimeout(nextFrame);worker?.terminate();worker=next;precision=precise;
        worker.onmessage=({data})=>{
          if(ticket!==generation||worker!==next)return;
          if(data.error){readerFailed();return;}
          const received=performance.now();lastCost=received-capturedAt;
          if(precise){slowFrames=lastCost>=800?slowFrames+1:0;if(slowFrames>=3){readerFailed();return;}}
          if(capturedEpoch===epoch && lastCost<900){
            const wanted=targets();
            needDetail=data.usedCenter || data.markers.filter(m=>wanted.includes(registry[m.id]?.[0])).length<2;
            const gap=previousFrame?capturedAt-previousFrame:0;
            acceptFrame(data);
            onFrame?.('pickup',{gap,copy:postedAt-capturedAt,read:received-postedAt,ui:performance.now()-received,
              size:[data.width,data.height],source:[video.videoWidth,video.videoHeight],ids:data.markers.length,
              center:data.usedCenter===true,detector:data.detector,lens:lens.facts});
          }
          nextFrame=setTimeout(capture,ShelfCamera.delay(capturedAt,80));
        };
        function readerFailed(){
          if(ticket!==generation||worker!==next)return;
          if(precise)useReader(new Worker(ShelfCamera.workerURL('../field/reader.js')),false);
          else fail(ticket);
        }
        worker.onerror=readerFailed;capture();
      }
      function capture() {
        if (ticket!==generation) return;
        if (video.readyState<2 || !video.videoWidth) { nextFrame=setTimeout(capture,100); return; }
        try {
          const scale = Math.min(1,(precision?1024:850)/Math.max(video.videoWidth,video.videoHeight));
          const width = Math.max(1,Math.round(video.videoWidth*scale));
          const height = Math.max(1,Math.round(video.videoHeight*scale));
          if (buffer.width!==width || buffer.height!==height) { buffer.width=width; buffer.height=height; }
          capturedAt=performance.now();
          context.drawImage(video,0,0,width,height);
          const image = context.getImageData(0,0,width,height);
          const wanted=targets();
          const message={image,finder:{targets:wanted,token:wanted.join('/'),generation:epoch,atMs:capturedAt}},transfer=[image.data.buffer];
          if(scale<.9 && needDetail && capturedAt-lastDetail>=Math.max(140,lastCost*2.5)){
            const vw=video.videoWidth,vh=video.videoHeight;
            const side=Math.min(576,Math.round(Math.min(vw,vh)*.8));
            const x=Math.floor((vw-side)/2),y=Math.floor((vh-side)/2);
            if(centerBuffer.width!==side || centerBuffer.height!==side){centerBuffer.width=side;centerBuffer.height=side;}
            centerContext.drawImage(video,x,y,side,side,0,0,side,side);
            const detail=centerContext.getImageData(0,0,side,side);
            if(!targetIds)targetIds=Object.keys(registry).filter(id=>wanted.includes(registry[id][0])).map(Number);
            message.detail={image:detail,ids:targetIds,x:x*width/vw,y:y*height/vh,scaleX:width/vw,scaleY:height/vh};
            transfer.push(detail.data.buffer);lastDetail=capturedAt;
          }
          capturedEpoch = epoch;
          postedAt=performance.now();
          worker.postMessage(message,transfer);
        } catch { fail(ticket); }
      }
      useReader(new Worker(ShelfCamera.workerURL('../field/reader.js')),false);
      let preferPrecision=true;
      try{preferPrecision=localStorage.getItem('ent207.camera.alignmentQuality.v1')!=='fast';}catch{}
      if(preferPrecision){
        const next=new Worker('../vision/worker.js?v=8&finder=1');warming=next;
        const cancel=()=>{if(warming===next){clearTimeout(warmTimeout);next.terminate();warming=null;}};
        next.onerror=cancel;warmTimeout=setTimeout(cancel,45000);
        next.onmessage=({data})=>{
          if(ticket!==generation||warming!==next)return;
          if(data.type!=='precision-ready'){cancel();return;}
          clearTimeout(warmTimeout);warming=null;useReader(next,true);
        };
        next.postMessage({type:'prepare-precision'});
      }
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
  return {start,stop,resetTarget,takeStream,match,setLanguage(){lens.update();framing.setLanguage();},get active(){return running},get hit(){return observed}};
}
