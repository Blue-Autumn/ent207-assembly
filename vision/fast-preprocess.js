'use strict';
// Optional, worker-local implementation of the JS reader's radius-2 pixel filter.
// Contour extraction, dictionary decoding and assembly decisions use their normal code.
self.ShelfFastPixels=(()=>{
  let kernel=null, inRead=false, loading;
  const original=CV.adaptiveThreshold;
  async function load(){
    const url=new URL('../vision/pickup-preprocess.wasm',self.location.href);
    const response=await fetch(url);
    if(!response.ok)throw Error('Pixel kernel unavailable');
    const mod=(await WebAssembly.instantiate(await response.arrayBuffer())).instance.exports;
    const w=17,h=9,n=w*h,input=new Uint8Array(mod.memory.buffer,0,n);
    for(let i=0;i<n;i++)input[i]=((i*17)^(i>>>4))&255;
    const expected=new CV.Image();
    original({width:w,height:h,data:input},expected,2,7);
    mod.process(0,n,n*2,w,h,7);
    if(!new Uint8Array(mod.memory.buffer,n*2,n).every((v,i)=>v===expected.data[i]))throw Error('Pixel check failed');
    kernel=mod;
    CV.adaptiveThreshold=function(src,dst,radius,threshold){
      if(!inRead || !kernel || radius!==2 || threshold<=0 || threshold>255)return original(src,dst,radius,threshold);
      const n=src.width*src.height;
      kernel.process(0,n,n*2,src.width,src.height,threshold);
      dst.width=src.width;dst.height=src.height;return dst;
    };
    return true;
  }
  function read(detector,image){
    if(!kernel || image.width<3 || image.height<3)return detector.detect(image);
    const n=image.width*image.height,gray=detector.grey.data,mask=detector.thres.data;
    try{
      const pages=Math.ceil((3*n-kernel.memory.buffer.byteLength)/65536);
      if(pages>0)kernel.memory.grow(pages);
      detector.grey.data=new Uint8Array(kernel.memory.buffer,0,n);
      detector.thres.data=new Uint8Array(kernel.memory.buffer,2*n,n);
      inRead=true;
      return detector.detect(image);
    }catch{
      kernel=null;CV.adaptiveThreshold=original;
    }finally{
      inRead=false;detector.grey.data=gray;detector.thres.data=mask;
    }
    return detector.detect(image);
  }
  return {prepare(){return loading ||= load().catch(()=>false);},read,get ready(){return !!kernel;}};
})();
