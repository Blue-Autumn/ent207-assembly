'use strict';
(() => {
 const $ = id => document.getElementById(id);
 const zh = new URLSearchParams(location.search).get('lang')?.startsWith('zh');
 const tr = (en,cn) => zh ? cn : en;
 document.documentElement.lang = zh ? 'zh-CN' : 'en';
 $('head').textContent = tr('Field guide','实物指导');
 $('back').ariaLabel = tr('Back','返回');
 $('title').textContent = tr('Fit the blue shelf','装入蓝色层板');
 $('intro').textContent = tr('The grey base lies flat. Compare the notch direction and the pair of slots in row 2.','灰色基准框平放。看蓝板的缺口朝向，再看第 2 排的两个槽。');
 $('photo').alt = tr('The blue shelf at the first row of the grey frame, photographed during assembly.','装配时拍到的蓝色层板，位于灰框第一排。');
 $('scan-title').textContent = tr('Find a marked part','识别带码板件');
 $('scan-intro').textContent = tr('Point the rear camera at a shelf marker. Frames are processed on this device.','将后置相机对准板件标签，画面只在本机处理。');
 $('marker').textContent = tr('Show practice tag','查看练习标签');
 $('marker-title').textContent = tr('Blue shelf · half 1','蓝色层板 · 半件 1');
 $('marker-help').textContent = tr('Display this tag on a second screen, then scan it with your phone. It identifies face A of this half.','在另一块屏幕上显示这枚标签，用手机扫描，即可识别这个半件的 A 面。');
 $('close-marker').textContent = tr('Close','关闭');
 const states = {
   wrong: [tr('Wrong row','错排'),tr('Lift the shelf and move to row 2.','提起蓝板，移到第 2 排。'),tr('The notch points the right way. In this photo the Android check found row 1. The target is row 2.','缺口朝向正确。这张照片的安卓判定为第 1 排，目标是第 2 排。')],
   reverse: [tr('Reversed','缺口反向'),tr('Turn the shelf half a turn.','将蓝板水平转半圈。'),tr('The notch is at the opposite end. Keep the shelf upright and turn it before lining up the slots.','缺口在相反的一端。保持层板竖直，转向后再对槽。')],
   correct: [tr('Aligned','对准'),tr('Hold it steady and lower it.','扶稳，再往下插。'),tr('The notch faces the reference end and both tabs line up with row 2. Check the seam yourself after insertion.','缺口朝向基准端，两端凸榫对准第 2 排。插入后请自行检查接缝。')]
 };
 let selected='wrong', illustrated=false, cameraMode=false;
 for(const key of ['correct','reverse','wrong']){
   const b=document.createElement('button');b.textContent=states[key][0];b.dataset.case=key;
   b.onclick=()=>{selected=key;illustrated=false;renderCase()};$('choices').append(b);
 }
 function renderCase(){
   stopReader();
   $('boxes').replaceChildren();$('tags-found').textContent='';
   for(const b of $('choices').children)b.setAttribute('aria-pressed',String(b.dataset.case===selected));
   const photo=selected==='wrong'&&!illustrated;
   $('photo').hidden=!photo;$('boxes').hidden=!photo;$('drawing').replaceChildren();
   if(!photo){
     const iframe=document.createElement('iframe');
     iframe.title=tr('Blue shelf placement model','蓝板装配示意');
     iframe.src='model.html?'+new URLSearchParams({case:selected,lang:zh?'zh':'en',animate:illustrated?'1':'0'});
     $('drawing').append(iframe);
   }
   $('source').textContent=photo?tr('Field photo · saved Android result','示例照片 · 安卓判定回放'):tr('CAD demonstration','CAD 动作示意');
   $('verdict').textContent=states[selected][1];$('instruction').textContent=states[selected][2];
   $('explain').textContent=illustrated?(selected==='wrong'?tr('Back to photo','返回照片'):tr('Back to position','回到摆位')):tr('Show the move','看示范动作');
   $('detect').hidden=!photo;$('detect').textContent=tr('Read photo tags','识别照片标签');
 }
 $('explain').onclick=()=>{illustrated=!illustrated;renderCase()};
 const ns='http://www.w3.org/2000/svg';
 function highlight(element,markers,width,height){
   element.setAttribute('viewBox','0 0 '+width+' '+height);
   const nodes=[];
   for(const marker of markers){
     const poly=document.createElementNS(ns,'polygon');
     poly.setAttribute('points',marker.corners.map(p=>p.x+','+p.y).join(' '));nodes.push(poly);
     const label=document.createElementNS(ns,'text');
     label.setAttribute('x',marker.corners[0].x);label.setAttribute('y',Math.max(16,marker.corners[0].y-3));
     label.textContent=String(marker.id).padStart(3,'0');nodes.push(label);
   }
   element.replaceChildren(...nodes);
 }
 let registry=null,worker=null,session=0,stream=null,timer=0,running=false;
 const seen=new Map();
 const buffer=document.createElement('canvas');
 const ctx=buffer.getContext('2d',{willReadFrequently:true});
 function stopReader(){
   session++;clearTimeout(timer);worker?.terminate();worker=null;
   stream?.getTracks().forEach(t=>t.stop());stream=null;
   $('video').srcObject=null;running=false;seen.clear();
   $('outlines').replaceChildren();$('power').textContent=tr('Open camera','开启相机');$('detect').disabled=false;
 }
 async function loadRegistry(){
   if(!registry){
     const response=await fetch('tags.json');if(!response.ok)throw new Error('Marker registry unavailable');
     registry=await response.json();
   }
 }
 function describe(id){
   const [piece,face]=registry[id], [group,half]=piece.split('-');
   const names={'左侧板':tr('Left frame','左侧框'),'右侧板':tr('Right frame','右侧框'),'背板':tr('Back panel','背板'),'层1板':tr('Purple shelf','紫色层板'),'层2板':tr('Blue shelf','蓝色层板'),'层3板':tr('Peach shelf','肉色层板'),'层4板':tr('Yellow shelf','黄色层板'),'层5板':tr('Green shelf','绿色层板')};
   return String(id).padStart(3,'0')+' · '+names[group]+tr(' · half ',' · 半件 ')+half+' · '+face;
 }
 function scanPixels(image,w,h,max){
   const scale=Math.min(1,max/Math.max(w,h));
   buffer.width=Math.max(1,Math.round(w*scale));buffer.height=Math.max(1,Math.round(h*scale));
   ctx.drawImage(image,0,0,buffer.width,buffer.height);
   const imageData=ctx.getImageData(0,0,buffer.width,buffer.height);
   worker.postMessage({image:imageData},[imageData.data.buffer]);
 }
 function showError(photo){
   stopReader();
   if(photo)$('tags-found').textContent=tr('Photo reader unavailable. The saved result is shown above.','暂时无法重新识码，仍可查看已保存的判定。');
   else{
     $('reading').textContent=tr('Camera unavailable','相机暂不可用');
     $('cam-status').textContent=tr('Allow camera access in your browser, or return to the sample photos.','可在浏览器中允许相机权限，或返回样片继续体验。');
   }
 }
 $('detect').onclick=async()=>{
   stopReader();const ticket=session;$('detect').disabled=true;
   try{
     await Promise.all([loadRegistry(),$('photo').decode()]);if(ticket!==session)return;
     worker=new Worker('reader.js');worker.onerror=()=>showError(true);
     worker.onmessage=({data})=>{
       if(ticket!==session)return;
       const matched=data.markers.filter(m=>registry[m.id]);highlight($('boxes'),matched,data.width,data.height);
       $('tags-found').textContent=tr('Read on this device: ','本次浏览器识别：')+matched.length+tr(' registered tags. The row judgement above is the saved Android result.',' 枚已登记标签。上方孔排判定来自已保存的安卓结果。');
       worker.terminate();worker=null;$('detect').disabled=false;
     };
     const img=$('photo');scanPixels(img,img.naturalWidth,img.naturalHeight,900);
   }catch{if(ticket===session)showError(true)}
 };
 function idleCamera(){
   $('reading').textContent=tr('Ready to scan','准备识码');
   $('cam-status').textContent=tr('A tag identifies the part and face. Check direction, slot alignment and seating separately.','标签用于确认板件和面。朝向、槽位和接缝需要分别检查。');
 }
 async function startCamera(){
   stopReader();running=true;const ticket=session;
   $('power').textContent=tr('Stop camera','关闭相机');$('reading').textContent=tr('Opening camera…','正在打开相机…');
   try{
     if(!navigator.mediaDevices?.getUserMedia)throw new Error('No camera');
     const tracks=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}}});
     if(ticket!==session){tracks.getTracks().forEach(t=>t.stop());return}
     stream=tracks;const video=$('video');video.srcObject=stream;await video.play();await loadRegistry();if(ticket!==session)return;
     worker=new Worker('reader.js');worker.onerror=()=>showError(false);
     function capture(){if(ticket!==session)return;if(video.readyState>=2)scanPixels(video,video.videoWidth,video.videoHeight,850);else timer=setTimeout(capture,100)}
     worker.onmessage=({data})=>{
       if(ticket!==session)return;
       const now=performance.now(), visible=new Set();
       for(const marker of data.markers){
         if(!registry[marker.id]||visible.has(marker.id))continue;visible.add(marker.id);
         const old=seen.get(marker.id);
         const item=old && now-old.time<=500 ? old : {count:0};
         Object.assign(item,{marker,time:now,count:item.count+1});seen.set(marker.id,item);
       }
       for(const [id,item] of seen)if(now-item.time>500)seen.delete(id);
       const stable=[...seen.values()].filter(item=>item.count>=2).sort((a,b)=>a.marker.id-b.marker.id);
       highlight($('outlines'),stable.map(item=>item.marker),data.width,data.height);
       $('reading').textContent=stable.length?stable.slice(0,3).map(item=>describe(item.marker.id)).join(' / '):tr('Looking for a shelf tag…','正在找板件标签…');
       timer=setTimeout(capture,85);
     };
     capture();
   }catch{if(ticket===session)showError(false)}
 }
 $('power').onclick=()=>{if(running){stopReader();idleCamera()}else startCamera()};
 $('marker').onclick=()=>{stopReader();idleCamera();$('practice').showModal()};
 $('close-marker').onclick=()=>$('practice').close();
 function displayMode(){
   stopReader();$('examples').hidden=cameraMode;$('scanner').hidden=!cameraMode;
   $('mode').textContent=cameraMode?tr('Sample photos','实物样片'):tr('Live camera','实时相机');
   if(cameraMode)idleCamera();else renderCase();
 }
 $('mode').onclick=()=>{cameraMode=!cameraMode;displayMode();document.querySelector('main').scrollTop=0};
 $('back').onclick=()=>{stopReader();if(parent===window)location.assign('../');else parent.postMessage({type:'ent207-close'},location.origin)};
 document.addEventListener('visibilitychange',()=>{if(document.hidden){stopReader();if(cameraMode)idleCamera()}});
 window.addEventListener('pagehide',stopReader);
 displayMode();
})();
