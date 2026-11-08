'use strict';
// Only media and marker IDs enter this helper. The guide owns task progression.
function createPickupScanner(stage, targets, onChange, onError) {
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
  view.append(video, outlines);
  stage.append(view);

  const buffer = document.createElement('canvas');
  const context = buffer.getContext('2d',{willReadFrequently:true});
  const seen = new Map();
  const lossMs = 500;
  let generation = 0, running = false, worker = null, stream = null;
  let nextFrame = 0, expiry = 0, registry = null, observed = null;

  function stop() {
    running = false;
    generation++;
    clearTimeout(nextFrame);
    clearTimeout(expiry);
    worker?.terminate();
    worker = null;
    stream?.getTracks().forEach(track=>track.stop());
    stream = null;
    video.srcObject = null;
    outlines.replaceChildren();
    view.hidden = true;
    seen.clear();
    observed = null;
  }
  function publish(hit) {
    const changed = observed?.id!==hit?.id || observed?.matches!==hit?.matches;
    observed = hit;
    if (changed) onChange(hit);
  }
  function updateReadout() {
    const now = performance.now(), valid = [];
    for (const [id, reading] of seen) {
      if (now-reading.at >= lossMs) seen.delete(id);
      else if (reading.count>=2) valid.push(reading);
    }
    valid.sort((a,b)=>Number(b.matches)-Number(a.matches) || b.at-a.at || a.id-b.id);
    publish(valid[0] || null);
    clearTimeout(expiry);
    if (valid.length) {
      const firstExpiry = Math.min(...valid.map(reading=>reading.at+lossMs));
      expiry = setTimeout(()=>{if(running){updateReadout();paintOutlines()}},Math.max(1,Math.ceil(firstExpiry-now)));
    }
  }
  function paintOutlines() {
    const paths = [];
    for (const item of seen.values()) {
      if (item.count<2) continue;
      const outline = document.createElementNS(ns,'polygon');
      outline.setAttribute('points',item.corners.map(c=>c.x+','+c.y).join(' '));
      outline.setAttribute('class',item.matches?'target':'other');
      paths.push(outline);
    }
    outlines.replaceChildren(...paths);
  }
  function acceptFrame(data) {
    const now = performance.now(), done = new Set();
    let registeredVisible = false, targetVisible = false;
    for (const marker of data.markers) {
      if (marker.hammingDistance!==0 || done.has(marker.id)) continue;
      done.add(marker.id);
      const entry = registry[marker.id];
      if (!entry) continue;
      const matches = targets().includes(entry[0]);
      registeredVisible = true;
      targetVisible ||= matches;
      const prev = seen.get(marker.id);
      const count = prev && now-prev.at<lossMs ? prev.count+1 : 1;
      seen.set(marker.id,{id:marker.id,part:entry[0],face:entry[1],corners:marker.corners,
        matches,at:now,count});
    }
    // Brief occlusion is tolerated, but a new view containing a different board
    // must immediately withdraw the old matching candidate.
    if (registeredVisible && !targetVisible) {
      for(const [id,item] of seen) if(item.matches) seen.delete(id);
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
  async function start() {
    stop();
    running = true;
    view.hidden = false;
    const ticket = generation;
    try {
      const camera = await navigator.mediaDevices.getUserMedia({
        audio:false,video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}}
      });
      if (ticket!==generation) { camera.getTracks().forEach(track=>track.stop()); return; }
      stream = camera;
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
      worker = new Worker('../field/reader.js');
      worker.onerror = ()=>fail(ticket);
      function capture() {
        if (ticket!==generation) return;
        if (video.readyState<2 || !video.videoWidth) { nextFrame=setTimeout(capture,100); return; }
        try {
          const scale = Math.min(1,850/Math.max(video.videoWidth,video.videoHeight));
          const width = Math.max(1,Math.round(video.videoWidth*scale));
          const height = Math.max(1,Math.round(video.videoHeight*scale));
          if (buffer.width!==width || buffer.height!==height) { buffer.width=width; buffer.height=height; }
          context.drawImage(video,0,0,width,height);
          const image = context.getImageData(0,0,width,height);
          worker.postMessage({image},[image.data.buffer]);
        } catch { fail(ticket); }
      }
      worker.onmessage = ({data})=>{
        if (ticket!==generation) return;
        acceptFrame(data);
        nextFrame = setTimeout(capture,80);
      };
      capture();
    } catch { fail(ticket); }
  }
  function match() {
    if (!running || !observed?.matches || performance.now()-observed.at>=lossMs) return null;
    return targets().includes(observed.part) ? observed : null;
  }
  return {start,stop,match,get active(){return running},get hit(){return observed}};
}
