import {showModel} from '../community/model.js';
import {askJSON,getCloudStatus,cloudReady as checkService} from '../services/cloud.js';

const resource=name=>fetch(new URL(name,import.meta.url)).then(r=>{if(!r.ok)throw Error('asset');return r.json();});
const authors=['Mina','Rae','Jun','Noor','Ivo','Kai','Cleo','Em','Lin','Remy','Bo','Pip','Lou','Jo','Sam'];
const day=86400000;
const freshId=()=>crypto.randomUUID();
const shuffle=values=>{
  const result=[...values];
  for(let i=result.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[result[i],result[j]]=[result[j],result[i]];}
  return result;
};
const promises=new Map();
function cached(name){
  if(!promises.has(name))promises.set(name,resource(name).catch(e=>{promises.delete(name);throw e;}));
  return promises.get(name);
}
function requiredText(value,max){
  if(typeof value!=='string'||!value.trim()||value.length>max)throw Error('format');
  return value.trim();
}
export function cloudReady(){
  const {configured,enabled}=getCloudStatus();return configured && enabled;
}
export function decodeRecipe(recipe,kit,allowed){
  if(recipe?.kit!=='desk-v2'||!allowed.includes(recipe.design)||!Object.hasOwn(kit,recipe.design))throw Error('format');
  const s=[recipe.width,recipe.height,recipe.depth];
  if(s.some(x=>!Number.isFinite(x)||x<.75||x>1.3))throw Error('format');
  const count=recipe.details,c=recipe.palette;
  if(!Number.isInteger(count)||count<2||count>5||!Array.isArray(c)||c.length!==3||
      c.some(color=>typeof color!=='string'||!/^#[a-fA-F0-9]{6}$/.test(color)))throw Error('format');
  // Native desk-v2 geometry passed the native audit at unit scale for every family/count.
  // All parts in one design share the same positive scale on each axis.
  const parts=kit[recipe.design][count].map(([shape,x,y,z,w,h,d,i])=>
    [shape,x*s[0],y*s[1],z*s[2],w*s[0],h*s[1],d*s[2],c[i]]);
  return {parts,recipe:{kit:'desk-v2',design:recipe.design,width:s[0],height:s[1],depth:s[2],details:count,palette:[...c]}};
}
export function decodeComments(raw,wanted,from,now){
  if(!Array.isArray(raw)||raw.length!==wanted)throw Error('format');
  const seen=new Set(),people=shuffle(authors);
  return raw.map((r,i)=>{
    const en=requiredText(r?.textEn,340),zh=requiredText(r?.textZh,230);
    const key=en.toLowerCase();if(seen.has(key))throw Error('format');seen.add(key);
    return {en,zh,key:'r-'+freshId(),author:people[i%people.length],at:Math.round(from+(now-from)*(i+1)/(wanted+1))};
  });
}
function withPoster(post){
  return {...post,poster:showModel(document.createElement('canvas'),post.parts,true)};
}
export async function preparedBatch(studio,n,replace){
  const rows=await cached('prepared.json');
  const used=new Set(studio.works.map(p=>p.id));
  let pool=rows.filter(r=>!used.has(r.id)&&!studio.hidden[r.id]);
  if(replace && !pool.length)pool=rows.filter(r=>!studio.hidden[r.id] && !studio.kept[r.id]);
  return pool.slice(0,n).map(withPoster);
}
export async function serviceAvailable(){
  try{await checkService();return cloudReady();}catch{return false;}
}
export async function generatedBatch(settings,signal,onProgress){
  if(!await serviceAvailable())throw Error('configuration');
  const kit=await cached('kits.json');
  const keys=shuffle(Object.keys(kit)).slice(0,settings.size),results=[],names=new Set();
  for(const [i,key] of keys.entries()){
    if(signal.aborted)throw new DOMException('Aborted','AbortError');
    onProgress(i+1,keys.length);
    const result=await askJSON({task:'design',context:{designKey:key,style:settings.style,brief:settings.brief,months:settings.months},signal});
    if(!Array.isArray(result?.works)||result.works.length!==1)throw Error('format');
    const o=result.works[0],geometry=decodeRecipe(o?.model,kit,[key]);
    const titleEn=requiredText(o.titleEn,85),titleZh=requiredText(o.titleZh,50);
    if(names.has(titleEn.toLowerCase()))throw Error('format');
    names.add(titleEn.toLowerCase());
    const now=Date.now(),at=now-(1+Math.floor(Math.random()*settings.months*30*day));
    results.push(withPoster({id:'studio-'+freshId(),titleEn,titleZh,bodyEn:requiredText(o.bodyEn,950),bodyZh:requiredText(o.bodyZh,500),
      ...geometry,author:authors[Math.floor(Math.random()*authors.length)],surface:'#e3ebe2',at,
      generated:true,source:'online',likes:4+Math.floor(Math.random()*180),samples:decodeComments(o.comments,20,at,now)}));
  }
  return results;
}
export async function generateReplies(post,sampleRows,settings,signal){
  if(!await serviceAvailable())throw Error('configuration');
  const count=20;
  const context={title:post.titleEn,description:post.bodyEn,style:settings.style,brief:settings.brief,count,
    earlierDemoComments:sampleRows.slice(-60).map(s=>s.en)};
  const result=await askJSON({task:'comments',context,signal});
  const now=Date.now();
  const rows=decodeComments(result?.comments,count,Math.min(now,Math.max(post.at, ...sampleRows.map(r=>r.at))),now);
  const existing=new Set(sampleRows.map(r=>r.en.toLowerCase()));
  if(rows.some(r=>existing.has(r.en.toLowerCase())))throw Error('format');
  return rows;
}
