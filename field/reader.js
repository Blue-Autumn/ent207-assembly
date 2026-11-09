'use strict';
importScripts('cv.js','aruco.js','dictionary.js');
const detector = new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
let detailDetector;
if(new URL(self.location.href).searchParams.get('engine')==='simd'){
  try{importScripts('../vision/fast-preprocess.js?v=5');self.ShelfFastPixels.prepare();}catch{}
}
function decode(reader,image){return self.ShelfFastPixels?.ready?self.ShelfFastPixels.read(reader,image):reader.detect(image);}
self.onmessage = ({data}) => {
  let markers = decode(detector,data.image).filter(m => m.hammingDistance === 0);
  let usedCenter=false;
  const detail=data.detail;
  if (detail && !markers.some(m=>detail.ids.includes(m.id))) {
    // Only finding a requested half may use this second look. A crop never
    // supplies assembly pose or slot evidence. Keep one source of corners.
    detailDetector??=new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
    const targets=decode(detailDetector,detail.image).filter(m=>m.hammingDistance===0 && detail.ids.includes(m.id));
    if(targets.length){
      markers=targets.map(m=>({id:m.id,hammingDistance:0,corners:m.corners.map(p=>({
        x:detail.x+p.x*detail.scaleX,y:detail.y+p.y*detail.scaleY
      }))}));
      usedCenter=true;
    }
  }
  self.postMessage({markers,width:data.image.width,height:data.image.height,usedCenter,detector:self.ShelfFastPixels?.ready?'simd-4x4-250':'fast-4x4-250'});
};
