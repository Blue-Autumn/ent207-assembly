'use strict';
// A projection always receives the ordered corners of a single exposure and scale.
// The finder never uses the pickup UI's cached tag corners.
function readFinderFrame(data,detect,detectDetail,detector){
  const {image,detail,finder}=data;
  let markers=detect(image),usedCenter=false;
  if(detail){
    const wanted=new Set(detail.ids);
    const count=list=>new Set(list.filter(m=>wanted.has(m.id)).map(m=>m.id)).size;
    const fullCount=count(markers);
    if(fullCount<(finder?2:1)){
      const crop=detectDetail(detail.image).filter(m=>wanted.has(m.id));
      if(count(crop)>fullCount){
        markers=crop.map(m=>({...m,corners:m.corners.map(p=>({
          x:detail.x+p.x*detail.scaleX,y:detail.y+p.y*detail.scaleY
        }))}));
        usedCenter=true;
      }
    }
  }
  const {width,height}=image;
  const regions=finder&&self.ShelfVision?self.ShelfVision.findParts(finder,markers,width,height):[];
  return {markers,regions,width,height,usedCenter,detector};
}
