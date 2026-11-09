'use strict';
(() => {
  function capabilities(device) {
    try { return device.getCapabilities?.() || {}; } catch { return {}; }
  }
  function facing(device, caps) {
    const mode = device.getSettings?.().facingMode || caps.facingMode;
    const modes = Array.isArray(mode) ? mode : [mode];
    const name = device.label || '';
    if (modes.includes('user') || /\bfront\b|selfie|前置|前摄|前攝|前鏡|正面/i.test(name)) return 'front';
    if (modes.includes('environment') || /\b(back|rear|environment)\b|后置|後置|后摄|後攝|背面|朝后|朝後/i.test(name)) return 'rear';
    return 'unknown';
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
        if (current() && track && (!actual || actual===id) && facing(track,capabilities(track))!=='front') return stream;
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
      if (facts.facing==='rear' && !zoomFailed && facts.zoomMin>0 && facts.zoomMin<1 &&
          Number.isFinite(zoom) && track.applyConstraints) {
        if (zoom>facts.zoomMin+.02) zoomOption=facts.zoomMin;
        else if (facts.zoomMax>=1) zoomOption=1;
      }
      button.hidden=false;
      button.disabled=busy || (!zoomOption && !alternatives.length);
      if (zoomOption) button.textContent=tr('View at ','取景 ')+Number(zoomOption.toFixed(2))+'×';
      else if (alternatives.length) button.textContent=alternatives[0].wide?
        tr('Try rear ultrawide','试用后置超广角'):tr('Other rear camera','切换后置镜头');
      else button.textContent=tr('No other rear view exposed','未开放其他后置取景');
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
        if (position==='rear') rear.push({id:device.deviceId,wide:/ultra[ -]?wide|超广角|超廣角/i.test(device.label)});
        if (position==='unknown') facts.unknownInputs++;
      }
      facts.otherRear=activeId && facts.facing==='rear'?rear.length:null;
      // Use only positively identified back-facing alternatives. A numeric
      // device label or an entry's position in the list says nothing about its optics.
      if (activeId && facts.facing==='rear') {
        const ring=rear.concat({id:activeId,wide:false}).sort((a,b)=>a.id.localeCompare(b.id));
        const i=ring.findIndex(d=>d.id===activeId);
        alternatives=ring.slice(i+1).concat(ring.slice(0,i));
        if (!/ultra[ -]?wide|超广角|超廣角/i.test(track.label || '')) alternatives.sort((a,b)=>Number(b.wide)-Number(a.wide));
      }
      update();
    }
    async function activate() {
      if (!track || busy || button.disabled) return false;
      const owner=track, zoom=zoomOption;
      if (zoom===null) {
        if (!alternatives.length) return false;
        return switchDevice({next:alternatives[0].id,previous:activeId});
      }
      busy=true;update();
      try {
        await owner.applyConstraints({advanced:[{zoom}]});
        if (track!==owner) return false;
        const actual=owner.getSettings?.().zoom;
        if (!Number.isFinite(actual) || Math.abs(actual-zoom)>.025) throw Error('Zoom not applied');
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
    const url=new URL(path,document.baseURI);url.searchParams.set('v','5');
    try{if(localStorage.getItem('ent207.camera.simd.v1')==='on')url.searchParams.set('engine','simd');}catch{}
    return url;
  }
  window.ShelfCamera={lensControl,openRear,delay,workerURL};
})();
