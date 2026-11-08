'use strict';
importScripts('cv.js','aruco.js','dictionary.js');
const detector = new AR.Detector({dictionaryName:'SHELF_4X4_250',maxHammingDistance:0});
self.onmessage = ({data}) => {
  const markers = detector.detect(data.image).filter(m => m.hammingDistance === 0);
  self.postMessage({markers,width:data.image.width,height:data.image.height});
};
