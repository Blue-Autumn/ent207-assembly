const defaults={size:2,months:3,style:'balanced',brief:'',debug:false};
const isObject=v=>v && typeof v==='object' && !Array.isArray(v);
export const studioKey=profile=>'ent207.studio.v1.'+(profile||'guest');

export function loadStudio(profile) {
  let v;
  try {v=JSON.parse(localStorage.getItem(studioKey(profile)));}catch{v=null;}
  if(!isObject(v))v={};
  return {
    settings:{...defaults,...(isObject(v.settings)?v.settings:{})},
    works:Array.isArray(v.works)?v.works:[],
    extra:isObject(v.extra)?v.extra:{},
    kept:isObject(v.kept)?v.kept:{},hidden:isObject(v.hidden)?v.hidden:{},
    removed:Array.isArray(v.removed)?v.removed:[],
    generation:Number.isSafeInteger(v.generation)?v.generation:0
  };
}

// A single commit for each user action; long requests never hold the writer lock.
export function editStudio(profile,mutate){
  const key=studioKey(profile);
  const commit=()=>{
    const state=loadStudio(profile);
    const result=mutate(state);
    localStorage.setItem(key,JSON.stringify(state));
    if(parent!==window)parent.postMessage({type:'ent207-studio-change'},location.origin);
    window.dispatchEvent(new CustomEvent('studio-changed',{detail:{key}}));
    return result;
  };
  return navigator.locks?navigator.locks.request(key,commit):Promise.resolve().then(commit);
}
export function mergeCatalog(base,studio){
  const seen=new Set();
  return [...studio.works,...base].filter(p=>p && !studio.hidden[p.id] && !seen.has(p.id) && seen.add(p.id));
}
export function thumbnail(post,base='community/'){
  if(typeof post?.poster==='string' && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(post.poster))return post.poster;
  return base+'thumbs/'+encodeURIComponent(post.id)+'.png';
}
export function visibleActivity(activity,studio,validPosts){
  const ids=validPosts instanceof Map || validPosts instanceof Set?validPosts:
    Array.isArray(validPosts)?new Set(validPosts.map(p=>p.id||p)):null;
  const canShow=id=>id==='shelf'||(!studio.hidden[id]&&!studio.removed.includes(id)&&(!ids||ids.has(id)));
  return {
    saved:(activity.saved||[]).filter(canShow),liked:(activity.liked||[]).filter(canShow),
    history:(activity.history||[]).filter(canShow),comments:(activity.comments||[]).filter(c=>canShow(c.postId))
  };
}
export function samplesFor(post,detail,studio,includeHidden=false){
  const all=[...detail.samples.map((row,i)=>({...row,key:row.key||'b'+i})),...(studio.extra[post.id]||[])];
  return includeHidden?all:all.filter(sample=>!studio.hidden[post.id+'/'+sample.key]);
}
export function mark(state,post,sample,action,reason=''){
  const key=sample?post.id+'/'+sample.key:post.id;
  const row={key,postId:post.id,sample:!!sample,titleEn:sample?.en||post.titleEn,titleZh:sample?.zh||post.titleZh,reason};
  if(action==='hide'){
    if(!sample)for(const [k,r] of Object.entries(state.kept)){if(r.postId===post.id)delete state.kept[k];}
    delete state.kept[key];state.hidden[key]=row;
  }else if(action==='keep'){
    if(!state.hidden[key] && !state.hidden[post.id])state.kept[key]=row;
  }else if(action==='release'){delete state.kept[key];}
  else if(action==='restore'){delete state.hidden[key];}
}
// Hidden items remain archived to honor Restore. Kept replies pin their parent work.
export function clearUnkept(state){
  const protectedPosts=new Set([...Object.values(state.kept),...Object.values(state.hidden)].map(x=>x.postId));
  const retained=state.works.filter(p=>protectedPosts.has(p.id));
  const removed=state.works.filter(p=>!protectedPosts.has(p.id)).map(p=>p.id);
  state.removed=[...new Set([...state.removed,...removed])].slice(-512);
  state.works=retained.map(p=>{
    if(state.hidden[p.id])return p;
    const samples=p.samples.filter((row,i)=>{
      const key=p.id+'/'+(row.key||'b'+i);
      return Boolean(state.kept[key]||state.hidden[key]);
    });
    return {...p,samples};
  });
  for(const [postId, rows] of Object.entries(state.extra)){
    const keep=rows.filter(r=>state.hidden[postId]||state.kept[postId+'/'+r.key]||state.hidden[postId+'/'+r.key]);
    if(keep.length)state.extra[postId]=keep;else delete state.extra[postId];
  }
  state.generation++;
}
export function insertWorks(state,works){
  const ids=new Set(state.works.map(p=>p.id));
  const fresh=works.filter(p=>!ids.has(p.id) && !state.hidden[p.id] && ids.add(p.id));
  if(state.works.length+fresh.length>96)throw Error('capacity');
  for(const p of fresh){
    p.samples=p.samples.map((row,i)=>row.key?row:{...row,key:'b'+i});
  }
  state.works.unshift(...fresh);
  const recovered=new Set(fresh.map(p=>p.id));
  state.removed=state.removed.filter(id=>!recovered.has(id));
  return fresh.length;
}
