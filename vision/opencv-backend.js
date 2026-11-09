'use strict';
/** OpenCV.js adapter with the same 4x4 dictionary and subpixel corner refinement as Android. */
function createOpenCVGeometry(cv){
 const dictionary=cv.getPredefinedDictionary(cv.DICT_4X4_250);
 const params=new cv.aruco_DetectorParameters(),refine=new cv.aruco_RefineParameters(10,3,true);
 params.cornerRefinementMethod=cv.CORNER_REFINE_SUBPIX;
 const detector=new cv.aruco_ArucoDetector(dictionary,params,refine);
 params.delete();refine.delete();dictionary.delete();
 // See R91 ArucoProcessor.slotCandidates. Rectangles are unlabelled visual supports:
 // FrameSlotGrid applies the measured slot spacing and current reference gate.
 const baseFaceA=new Set([76,116,31,117,21,127,128,25]);
 function slotCandidates(gray,markers){
  const base=markers.filter(m=>baseFaceA.has(m.id));
  if(!base.length||base.length>2)return [];
  const edge=Math.max(...base.map(m=>m.corners.reduce((s,p,i)=>{
   const q=m.corners[(i+1)%4];return s+Math.hypot(p.x-q.x,p.y-q.y)/4;
  },0)));
  if(edge<10)return [];
  const points=base.flatMap(m=>m.corners),radius=edge*22;
  const left=Math.max(0,Math.floor(Math.min(...points.map(p=>p.x))-radius));
  const top=Math.max(0,Math.floor(Math.min(...points.map(p=>p.y))-radius));
  const right=Math.min(gray.cols,Math.ceil(Math.max(...points.map(p=>p.x))+radius));
  const bottom=Math.min(gray.rows,Math.ceil(Math.max(...points.map(p=>p.y))+radius));
  if(left>=right||top>=bottom)return [];
  const scale=Math.min(1,512/Math.max(right-left,bottom-top));
  const roi=gray.roi(new cv.Rect(left,top,right-left,bottom-top));
  const small=new cv.Mat(),smooth=new cv.Mat(),signal=new cv.Mat(),hierarchy=new cv.Mat();
  const result=[];
  try {
   cv.resize(roi,small,new cv.Size(0,0),scale,scale,cv.INTER_AREA);
   cv.GaussianBlur(small,smooth,new cv.Size(0,0),4,4,cv.BORDER_DEFAULT);
   for(const light of [false,true]){
    cv.subtract(light?small:smooth,light?smooth:small,signal);
    cv.threshold(signal,signal,16,255,cv.THRESH_BINARY);
    const contours=new cv.MatVector();
    try {
     cv.findContours(signal,contours,hierarchy,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE);
     for(let i=0;i<contours.size();i++){
      const contour=contours.get(i);
      try {
       const box=cv.boundingRect(contour),longest=Math.max(box.width,box.height);
       if(longest<8||longest>edge*scale*3.8||contour.rows<4)continue;
       const rect=cv.minAreaRect(contour),long=Math.max(rect.size.width,rect.size.height),short=Math.min(rect.size.width,rect.size.height);
       if(short<1.2||long/short<2.6||long/short>25||cv.contourArea(contour)<long*short*.45)continue;
       result.push(cv.RotatedRect.points(rect).map(p=>({x:left+p.x/scale,y:top+p.y/scale})));
       if(result.length>96)return [];
      }finally{contour.delete()}
     }
    }finally{contours.delete()}
   }
   return result;
  }finally{for(const m of [roi,small,smooth,signal,hierarchy])m.delete()}
 }
 return {
  detect(image,taskId){
   const rgba=cv.matFromImageData(image),gray=new cv.Mat(),corners=new cv.MatVector(),ids=new cv.Mat();
   try{
    cv.cvtColor(rgba,gray,cv.COLOR_RGBA2GRAY);
    detector.detectMarkers(gray,corners,ids);
    const markers=[];
    for(let i=0;i<ids.rows;i++){
     const ring=corners.get(i);
     try{const xy=ring.data32F;markers.push({id:ids.data32S[i],corners:[0,2,4,6].map(k=>({x:xy[k],y:xy[k+1]}))})}
     finally{ring.delete()}
    }
    return {markers,slotQuads:/^install:层[1-5]板$/.test(taskId)?slotCandidates(gray,markers):[]};
   }finally{for(const m of [rgba,gray,corners,ids])m.delete()}
  },
  dispose(){detector.delete()}
 };
}
