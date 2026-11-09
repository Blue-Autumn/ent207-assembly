'use strict';
// Regions come from FinderHighlight in detector-pixel coordinates. The surrounding
// SVG shares the video's transform, including full-frame and center-filled views.
function createFinderOverlay(svg){
  const ns=svg.namespaceURI,layer=document.createElementNS(ns,'g');
  layer.setAttribute('class','finder-regions');svg.prepend(layer);
  let expiry=0,lastSeen=new Map();
  function clear(){clearTimeout(expiry);layer.replaceChildren();lastSeen.clear();}
  function paint(regions,at){
    clearTimeout(expiry);
    const now=performance.now(),nodes=[],seen=new Map();
    let next=Infinity;
    for(const r of regions||[]){
      const supported=r.observed?at:lastSeen.get(r.part);
      if(!Number.isFinite(supported))continue;
      const until=Math.min(at+400,supported+(r.observed?400:220));
      if(now>=until)continue;
      const path=document.createElementNS(ns,'path');
      const d=r.rings.map(ring=>'M'+ring.map(([x,y])=>x.toFixed(1)+','+y.toFixed(1)).join('L')+'Z').join('');
      path.setAttribute('d',d);path.setAttribute('fill-rule','evenodd');
      path.setAttribute('opacity',r.opacity);path.dataset.part=r.part;
      nodes.push(path);seen.set(r.part,supported);next=Math.min(next,until);
    }
    layer.replaceChildren(...nodes);lastSeen=seen;
    if(nodes.length)expiry=setTimeout(()=>paint(regions,at),Math.max(1,Math.ceil(next-now)));
  }
  return {paint,clear};
}
