'use strict';
importScripts('cv.js','aruco.js','dictionary.js','finder-frame.js?v=8');
const detector = new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
let detailDetector,geometryRequested=false;
if(new URL(self.location.href).searchParams.get('engine')==='simd'){
  try{importScripts('../vision/fast-preprocess.js?v=5');self.ShelfFastPixels.prepare();}catch{}
}
function decode(reader,image){return self.ShelfFastPixels?.ready?self.ShelfFastPixels.read(reader,image):reader.detect(image);}
function full(image){return decode(detector,image).filter(m=>m.hammingDistance===0);}
function center(image){
  detailDetector??=new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
  return decode(detailDetector,image).filter(m=>m.hammingDistance===0);
}
self.onmessage=({data})=>{
  if(data.finder&&!geometryRequested){
    geometryRequested=true;
    try{importScripts('../vision/vision.js?v=8');}catch{ /* Tag identity remains available. */ }
  }
  self.postMessage(readFinderFrame(data,full,center,self.ShelfFastPixels?.ready?'simd-4x4-250':'fast-4x4-250'));
};
