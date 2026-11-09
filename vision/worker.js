'use strict';
importScripts('../field/cv.js','../field/aruco.js','../field/dictionary.js','vision.js?v=6');
const detector=new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
if(new URL(self.location.href).searchParams.get('engine')==='simd'){
  try{importScripts('fast-preprocess.js?v=5');self.ShelfFastPixels.prepare();}catch{}
}
function read(scaleImage,scale,reader=detector){
  const markers=self.ShelfFastPixels?.ready?self.ShelfFastPixels.read(reader,scaleImage):reader.detect(scaleImage);
  return markers.filter(m=>m.hammingDistance===0)
    .map(m=>({id:m.id,corners:m.corners.map(p=>({x:p.x/scale.x,y:p.y/scale.y}))}));
}
function repeatedAtDifferentLocations(markers){
  const centers=new Map();
  for(const m of markers){
    const c=m.corners.reduce((a,p)=>({x:a.x+p.x/4,y:a.y+p.y/4}),{x:0,y:0});
    const side=Math.min(...m.corners.map((p,i)=>Math.hypot(p.x-m.corners[(i+1)%4].x,p.y-m.corners[(i+1)%4].y)));
    const old=centers.get(m.id);
    if(old&&Math.hypot(c.x-old.x,c.y-old.y)>.65*Math.min(old.side,side))return true;
    centers.set(m.id,{...c,side});
  }
  return false;
}
function distinct(markers){ return new Set(markers.map(m=>m.id)).size===markers.length; }
function detectPhoto(image,first){
  // Choose a single observation of this photograph. Never combine corners from different
  // resolutions or choose a scale based on the guidance outcome.
  if(typeof OffscreenCanvas==='undefined'||repeatedAtDifferentLocations(first))return first;
  const source=new OffscreenCanvas(image.width,image.height);
  const sourceContext=source.getContext('2d',{willReadFrequently:true});
  sourceContext.putImageData(image,0,0);
  let best=distinct(first)?first:null;
  for(const scale of [.67,1.2,1.8]){
    const w=Math.max(1,Math.round(image.width*scale)),h=Math.max(1,Math.round(image.height*scale));
    if(w*h>5000000)continue;
    const target=new OffscreenCanvas(w,h),ctx=target.getContext('2d',{willReadFrequently:true});
    ctx.drawImage(source,0,0,w,h);
    const found=read(ctx.getImageData(0,0,w,h),{x:w/image.width,y:h/image.height});
    if(repeatedAtDifferentLocations(found))return found;
    if(distinct(found)&&(!best||found.length>best.length))best=found;
  }
  return best||first;
}
let preciseReader=null,epoch=0;
let scaleState=null, sourceCanvas=null, scaleCanvas=null, scaleDetector=null;
function atScale(image,scale){
  if(scale===1)return read(image,{x:1,y:1});
  const width=Math.round(image.width*scale),height=Math.round(image.height*scale);
  if(width*height>2400000)return null;
  sourceCanvas??=new OffscreenCanvas(image.width,image.height);
  scaleCanvas??=new OffscreenCanvas(width,height);
  if(sourceCanvas.width!==image.width||sourceCanvas.height!==image.height){sourceCanvas.width=image.width;sourceCanvas.height=image.height;}
  if(scaleCanvas.width!==width||scaleCanvas.height!==height){scaleCanvas.width=width;scaleCanvas.height=height;scaleDetector=null;}
  const src=sourceCanvas.getContext('2d',{willReadFrequently:true}),dst=scaleCanvas.getContext('2d',{willReadFrequently:true});
  src.putImageData(image,0,0);dst.drawImage(sourceCanvas,0,0,width,height);
  scaleDetector??=new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
  return read(dst.getImageData(0,0,width,height),{x:width/image.width,y:height/image.height},scaleDetector);
}
function pairRank(e){
  const [a,b]=e.counts;
  return 100*Number(a>0)+100*Number(b>0)+10*Math.min(3,a,b)+Math.min(4,a)+Math.min(4,b);
}
function readPairFrame(image,taskId,sessionId,at){
  const key=[sessionId,taskId,image.width,image.height].join('/');
  if(scaleState?.key!==key)scaleState={key,scale:1,lastTry:-Infinity,index:0,cost:0};
  const s=scaleState,now=performance.now();
  let marks=atScale(image,s.scale);
  if(!marks){s.scale=1;marks=atScale(image,1);}
  if(s.scale!==1 && performance.now()-now>260){
    const applied=s.scale;s.scale=1;s.cost=performance.now()-now;s.lastTry=at;return {markers:marks,scale:applied};
  }
  const ev=ShelfVision.pairEvidence(taskId,marks);
  if(!ev||!distinct(marks))return {markers:marks,scale:s.scale};
  if(s.confirmed && ev.counts.every(n=>n>=2))return {markers:marks,scale:s.scale};
  const strong=ev.counts.every(n=>n>=3);
  if((strong&&s.scale===1)||at-s.lastTry<Math.max(strong?1200:280,s.cost*3))return {markers:marks,scale:s.scale};
  const trials=[1.5,1.2,.82,1];
  let trial=strong?1:trials[s.index++%trials.length];
  if(trial===s.scale)trial=trials[s.index++%trials.length];
  s.lastTry=at;
  const alt=atScale(image,trial);
  if(alt && repeatedAtDifferentLocations(alt))return {markers:alt,scale:trial};
  if(alt && distinct(alt)){
    const current=pairRank(ev),alternative=pairRank(ShelfVision.pairEvidence(taskId,alt));
    if(alternative>current || (alternative===current&&trial===1)){s.scale=trial;marks=alt;}
  }
  s.cost=performance.now()-now;
  return {markers:marks,scale:s.scale};
}
function loadPrecision(){
  if(preciseReader)return preciseReader;
  preciseReader=new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error('Precision reader timed out')),40000);
    const ready=lib=>{
      clearTimeout(timer);
      try{resolve(createOpenCVGeometry(lib))}catch(error){reject(error)}
    };
    try{
      importScripts('opencv-backend.js','opencv-4.13.js');
      if(self.cv.Mat)ready(self.cv);else self.cv.then(ready);
    }catch(error){clearTimeout(timer);reject(error)}
  });
  return preciseReader;
}
self.onmessage=async({data})=>{
  const captureId=data?.captureId??null;
  try{
    if(data?.type==='reset'){epoch++;scaleState=null;ShelfVision.reset();return}
    const ticket=epoch;
    if(data?.type==='prepare-simd'){
      const ready=await self.ShelfFastPixels?.prepare();
      if(ticket===epoch)self.postMessage({captureId,type:ready?'simd-ready':'simd-unavailable'});
      return;
    }
    if(data?.type==='prepare-precision'){
      await loadPrecision();
      if(ticket===epoch)self.postMessage({captureId,type:'precision-ready',detector:'opencv-4.13'});
      return;
    }
    const {image,taskId,mode,lang,atMs,sessionId}=data;
    if(!image||!Number.isInteger(image.width)||!Number.isInteger(image.height)||
       image.width<=0||image.height<=0||image.width*image.height>5000000||
       image.data?.length!==image.width*image.height*4){
      throw Error('Expected RGBA pixels within the supported frame size.');
    }
    const {width,height}=image;
    let result=null,detectorName='fast-4x4-250',fallback=null,scale=1;
    if(data.precision==='opencv'){
      try{
        const backend=await loadPrecision();
        if(ticket!==epoch)return;
        result=backend.detect(image,taskId);detectorName='opencv-4.13';
      }catch(error){fallback=String(error.message||error)}
    }
    if(ticket!==epoch)return;
    if(!result){
      if(data.assistPair && mode==='stream' && typeof OffscreenCanvas!=='undefined' && ShelfVision.pairEvidence(taskId,[])){
        try{const frame=readPairFrame(image,taskId,sessionId,atMs);scale=frame.scale;result={markers:frame.markers,slotQuads:[]};}
        catch{scaleState=null;}
      }
      if(!result){
        const first=read(image,{x:1,y:1});result={markers:mode==='photo'?detectPhoto(image,first):first,slotQuads:[]};
      }
      if(self.ShelfFastPixels?.ready)detectorName='simd-4x4-250';
    }
    const {markers,slotQuads}=result;
    const check=ShelfVision.evaluate({markers,width,height,taskId,mode,lang,atMs,sessionId,slotQuads});
    if(data.assistPair&&scaleState)scaleState.confirmed=check.stable;
    self.postMessage({captureId,markers,width,height,check,detector:detectorName,scanScale:scale,
      pair:ShelfVision.pairEvidence(taskId,markers),slotCandidateCount:slotQuads.length,fallback});
  }catch(error){
    ShelfVision.reset();self.postMessage({captureId,error:String(error.message||error)});
  }
};
