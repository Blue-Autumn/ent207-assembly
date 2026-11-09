'use strict';
importScripts('../field/cv.js','../field/aruco.js','../field/dictionary.js','vision.js');
const detector=new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
function read(scaleImage,scale){
  return detector.detect(scaleImage).filter(m=>m.hammingDistance===0)
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
    if(data?.type==='reset'){epoch++;ShelfVision.reset();return}
    const ticket=epoch;
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
    let result=null,detectorName='fast-4x4-250',fallback=null;
    if(data.precision==='opencv'){
      try{
        const backend=await loadPrecision();
        if(ticket!==epoch)return;
        result=backend.detect(image,taskId);detectorName='opencv-4.13';
      }catch(error){fallback=String(error.message||error)}
    }
    if(ticket!==epoch)return;
    if(!result){
      const first=read(image,{x:1,y:1});
      result={markers:mode==='photo'?detectPhoto(image,first):first,slotQuads:[]};
    }
    const {markers,slotQuads}=result;
    const check=ShelfVision.evaluate({markers,width,height,taskId,mode,lang,atMs,sessionId,slotQuads});
    self.postMessage({captureId,markers,width,height,check,detector:detectorName,slotCandidateCount:slotQuads.length,fallback});
  }catch(error){
    ShelfVision.reset();self.postMessage({captureId,error:String(error.message||error)});
  }
};
