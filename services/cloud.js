const endpoint='https://ent207-review.pages.dev/api';
let status={configured:false,enabled:false}, checkedAt=0, ping;

export function getCloudStatus(){return {...status};}
export function cloudReady(){
  if(Date.now()-checkedAt<30000)return Promise.resolve(getCloudStatus());
  if(!ping)ping=(async()=>{
    const stop=new AbortController();const timer=setTimeout(()=>stop.abort(),9000);
    try{
      const response=await fetch(endpoint+'/health',{credentials:'omit',cache:'no-store',signal:stop.signal});
      const info=response.ok?await response.json():null;
      status={configured:info?.configured===true,enabled:info?.enabled===true};
    }catch{status={configured:false,enabled:false};}
    finally{clearTimeout(timer);checkedAt=Date.now();ping=null;}
    return getCloudStatus();
  })();
  return ping;
}
async function call(task,data,signal){
  const controller=new AbortController();
  const cancel=()=>controller.abort();
  if(signal?.aborted)cancel();else signal?.addEventListener('abort',cancel,{once:true});
  const timer=setTimeout(cancel,65000);
  try{
    const response=await fetch(endpoint+'/'+task,{
      method:'POST',credentials:'omit',cache:'no-store',redirect:'error',signal:controller.signal,
      headers:{'Content-Type':'application/json'},body:JSON.stringify({...data,requestId:crypto.randomUUID()})
    });
    if(!response.ok)throw new Error(response.status===429?'usage_limit':'unavailable');
    const dataOut=await response.json();
    if(!dataOut.result||typeof dataOut.result!=='object')throw new Error('invalid_response');
    return dataOut.result;
  }finally{clearTimeout(timer);signal?.removeEventListener('abort',cancel);}
}
export function askJSON({task,context,signal}){
  if(!['design','comments'].includes(task))throw new Error('unsupported_task');
  return call(task,{context},signal);
}
export function reviewPhotos({taskId,lang,views,question,signal}){
  return call('review',{taskId,lang:lang.startsWith('zh')?'zh':'en',question:question||'',
    views:views.map(v=>({jpegDataUrl:v.jpegDataUrl,markerIds:v.markers.map(m=>m.id),
      check:{status:v.check.status,notchRelation:v.check.notchRelation,observedRow:v.check.observedRow,targetRow:v.check.targetRow,
        installMotion:v.check.installMotion}}))},signal);
}
