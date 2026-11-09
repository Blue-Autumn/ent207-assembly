import {showModel} from './model.js';
import {studioKey,loadStudio,editStudio,mergeCatalog,thumbnail,visibleActivity,samplesFor,mark} from './studio-store.js';
import {cloudReady,serviceAvailable,generateReplies} from '../studio/generation.js';

const get=id=>document.getElementById(id);
const params=new URLSearchParams(location.search);
let zh=(params.get('lang')||'en').startsWith('zh');
const tr=(en,cn)=>zh?cn:en;
document.documentElement.lang=zh?'zh-CN':'en';
const profile=params.get('profile')||'guest';
const storageKey='ent207.community.v1.'+profile;
const kinds=['favorites','liked','history','comments'];
const view=kinds.includes(params.get('view'))?params.get('view'):'feed';
const initial=params.get('post');
const conceptIds=['stool','lamp','recordplayer','bridge','bookstand','tray','xylophone','drawers','plantstand','organizer'];
const title=p=>zh?p.titleZh:p.titleEn;
const body=p=>zh?p.bodyZh:p.bodyEn;
const strings=()=>({
  feed:tr('Community','社区'),favorites:tr('Saved','收藏'),liked:tr('Likes','点赞'),
  history:tr('History','足迹'),comments:tr('Comments','评论')
});
const emptyText=()=>({
  favorites:tr('Tap the bookmark on a design to save it here.','在作品中点按收藏，即可在这里找到。'),
  liked:tr('Designs you like will appear here.','你点赞的作品会出现在这里。'),
  history:tr('Open a project to start your history.','打开一件作品，开始留下足迹。'),
  comments:tr('Your comments on designs will appear here.','你写下的作品评论会出现在这里。')
});
const shelf={id:'shelf',titleEn:'Five-tier shelf', titleZh:'五层隔板架',surface:'#e3ebe2'};
const header=get('header'),scroll=get('scroll'),content=get('content');
let date=new Intl.DateTimeFormat(zh?'zh-CN':'en',{year:'numeric',month:'short',day:'numeric'});
const icons={
 back:'m14.5 5-7 7 7 7',refresh:'M19 8a7.5 7.5 0 1 0 .2 8M19 3v5h-5',
 save:'M6 3h12v18l-6-4-6 4z',like:'M12 20 3.8 12a5.1 5.1 0 0 1 7.2-7.2L12 6l1-1.2a5.1 5.1 0 0 1 7.2 7.2z',
 delete:'M4 6h16M9 6V3h6v3M7 6l1 15h8l1-15M10 10v7m4-7v7',
 right:'m9 5 7 7-7 7',tools:'M4 7h6m4 0h6M4 17h10m4 0h2M10 4v6m6 4v6'
};
let baseCatalog=[],studio=loadStudio(profile);
let catalog=[],byId=new Map(),studiesPromise=null,activePost=null,entryDirect=false;
let order=[],offset=0,feedScroll=0,cleanup=null,observer=null,noticeTimer=0;
let replyJob=null, displayedDetail=null,refreshReplies=null;
let hideRequest=null;
let pendingCommand=undefined,ready=false;
function node(tag,cls='',text){
 const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e;
}
function svg(key){
 const ns='http://www.w3.org/2000/svg',s=document.createElementNS(ns,'svg'),p=document.createElementNS(ns,'path');
 s.setAttribute('viewBox','0 0 24 24');s.setAttribute('aria-hidden','true');p.setAttribute('d',icons[key]);s.append(p);return s;
}
function button(text,cls,fn){
 const b=node('button',cls,text);b.type='button';b.addEventListener('click',fn);return b;
}
function icon(key,label,fn){
 const b=button('','icon',fn);b.append(svg(key));b.setAttribute('aria-label',label);return b;
}
function emit(type,extra={}){
 if(parent!==window)parent.postMessage({type,...extra},location.origin);
}
function notification(text){clearTimeout(noticeTimer);get('notice').textContent=text;noticeTimer=setTimeout(()=>get('notice').textContent='',3200);}
function readValue(key){
 try{return JSON.parse(localStorage.getItem(key));}catch{return null;}
}
function state(){
 const value=readValue(storageKey)||{};
 return {
  saved:Array.isArray(value.saved)?value.saved.filter(id=>typeof id==='string'):(profile==='guest'?[...conceptIds]:[]),
  liked:Array.isArray(value.liked)?value.liked.filter(id=>typeof id==='string'):[],
  history:Array.isArray(value.history)?value.history.filter(id=>typeof id==='string'):[],
  comments:Array.isArray(value.comments)?value.comments.filter(c=>c && typeof c.text==='string' && typeof c.postId==='string'):[]
 };
}
function update(change){
 const v=state();change(v);
 try{localStorage.setItem(storageKey,JSON.stringify(v));emit('ent207-community-change');return true;}
 catch{notification(tr('Could not save. Check browser storage.','保存失败，请检查浏览器存储。'));return false;}
}
function toggle(key,id){return update(s=>{s[key]=s[key].includes(id)?s[key].filter(v=>v!==id):[id,...s[key]];});}
function profileName(){
 const profiles=readValue('ent207.profiles.v2');
 return (Array.isArray(profiles)?profiles.find(p=>p.id===profile)?.name:'')||tr('Guest','访客');
}
function syncCatalog(){
 studio=loadStudio(profile);catalog=mergeCatalog(baseCatalog,studio);
 byId=new Map(catalog.map(p=>[p.id,p]));
}
function back(){
 if(activePost && !entryDirect){activePost=null;showList();return;}
 if(parent!==window){emit('ent207-close');return;}
 activePost=null;entryDirect=false;showList();
}
function nav(){emit('ent207-community-nav',{detail:!!activePost,post:activePost});}
function clearSurface(preserveReply=false){
 if(replyJob && !preserveReply){replyJob.abort();replyJob=null;}
 displayedDetail=null;refreshReplies=null;
 if(get('moderate').open)get('moderate').close();
 cleanup?.();cleanup=null;observer?.disconnect();observer=null;
 if(get('compose').open)get('compose').close();
 header.replaceChildren();content.replaceChildren();scroll.scrollTop=0;
}
function photo(p,lazy=true){
 const img=node('img');img.alt='';img.src=p.id==='shelf'?'../concepts/rack-poster.png':thumbnail(p,'');
 if(lazy)img.loading='lazy';
 return img;
}
function clickPost(p){if(p.id==='shelf')emit('ent207-open-shelf');else openDetail(p.id);}
function entry(p,line){
 const b=button('','entry row',()=>clickPost(p));const img=photo(p);img.style.background=p.surface;
 const text=node('div');text.append(node('strong','',title(p)));if(line)text.append(node('span','',line));
 b.append(img,text,svg('right'));return b;
}
function item(id){return id==='shelf'?shelf:byId.get(id);}
function showList(){
 clearSurface();const bar=node('div','headrow');
 if(view!=='feed')bar.append(icon('back',tr('Back','返回'),back));
 bar.append(node('h1','',strings()[view]));
 if(view==='feed'){
  bar.append(icon('refresh',tr('Refresh feed','换一批'),()=>{
   offset=catalog.length?(offset+7)%catalog.length:0;feedScroll=0;showList();
  }));
  if(parent!==window)bar.append(icon('tools',tr('Content studio','内容工作室'),()=>emit('ent207-studio')));
  header.append(bar,node('p','minor sub',tr('Furniture studies · includes sample activity','家居设计习作 · 含示例互动')));
  order=catalog.slice(offset).concat(catalog.slice(0,offset));renderCards();
 } else {
  header.append(bar);const s=visibleActivity(state(),studio,byId);
  if(view==='comments'){
   for(const c of s.comments){const p=byId.get(c.postId);if(p)content.append(entry(p,c.text));}
  }else{
   const key=view==='favorites'?'saved':view;
   for(const id of s[key]){const p=item(id);if(p)content.append(entry(p));}
  }
  if(!content.children.length)content.append(node('p','empty',emptyText()[view]));
 }
 scroll.scrollTop=feedScroll;nav();
}
function renderCards(){
 const activity=state();
 for(let start=0;start<order.length;start+=4){
  const spread=node('div','spread');
  for(let side=0;side<2;side++){
   const col=node('div','col');
   for(let i=side;i<4 && start+i<order.length;i+=2){
    const p=order[start+i];const card=button('','tile',()=>clickPost(p));
    const tall=(i===0 || i===3)===(start%8===0);
    card.style.setProperty('--art',tall?'142px':'108px');card.style.background=p.surface;
    const likes=p.likes+Number(activity.liked.includes(p.id));
    card.append(photo(p,start>=4),node('strong','',title(p)),node('small','',tr(likes+' likes',likes+' 个赞')));
    if(studio.settings.debug){
    const wrap=node('div','tile-wrap');wrap.append(card,markButtons(p,null,()=>{syncCatalog();feedScroll=scroll.scrollTop;showList();}));col.append(wrap);
   }else col.append(card);
   }spread.append(col);
  }content.append(spread);
 }
}
function fetchJSON(path){return fetch(path).then(r=>{if(!r.ok)throw Error(r.status);return r.json();});}
function studies(){
 if(!studiesPromise)studiesPromise=fetchJSON('studies.json').catch(e=>{studiesPromise=null;throw e;});
 return studiesPromise;
}
function postButton(key,name,active,cb){
 const b=button('','soft '+key,cb);b.setAttribute('aria-pressed',String(active));b.append(svg(key),document.createTextNode(name));return b;
}
function reactions(box,p,original,discussion){
 const s=state(),liked=s.liked.includes(p.id),saved=s.saved.includes(p.id);
 const likeLabel=liked?tr('Liked','已点赞'):tr('Like','点赞');
 const saveLabel=saved?tr('Saved','已收藏'):tr('Save','收藏');
 box.replaceChildren(postButton('like',original?likeLabel:likeLabel+' · '+(p.likes+Number(liked)),liked,()=>{
  if(toggle('liked',p.id)){reactions(box,p,original,discussion);discussion();}
 }),postButton('save',saveLabel,saved,()=>{
  if(toggle('saved',p.id))reactions(box,p,original,discussion);
 }));
}
let composeTarget=null,onPosted=null;
const compose=get('compose');
function composeLabels(){
get('compose-title').textContent=tr('Add comment','写评论');
get('comment-input').placeholder=tr('What would you use or change?','你会怎样使用或改动这个设计？');
get('comment-input').setAttribute('aria-label',tr('Comment','评论'));
get('comment-note').textContent=tr('Saved in this browser for this profile.','保存在本浏览器的当前个人空间。');
get('send').textContent=tr('Post to this device','发布到本机社区');get('cancel').textContent=tr('Cancel','取消');
};
composeLabels();
get('cancel').onclick=()=>compose.close();
get('comment-form').onsubmit=e=>{
 e.preventDefault();const text=get('comment-input').value.trim();
 if(!text || !composeTarget){get('comment-input').focus();return;}
 const at=Date.now(),id=crypto.randomUUID?.()||String(at)+'-'+Math.random().toString(36).slice(2,10);
 if(update(s=>s.comments.unshift({id,postId:composeTarget,text,at}))){
  compose.close();onPosted?.();
 }
};
function commentRow(author,text,at,mine,remove){
 const row=node('article','comment');const block=node('div','text');
 block.append(node('strong','',author),node('small','',date.format(new Date(at))+' · '+(mine?tr('Yours','我的'):tr('Sample','示例'))),node('p','',text));
 row.append(node('div','avatar',Array.from(author)[0]?.toUpperCase()),block);
 if(remove){const b=icon('delete',tr('Delete your comment','删除这条评论'),remove);b.classList.add('delete');row.append(b);}
 return row;
}
function markButtons(p,sample,after){
 const k=sample?p.id+'/'+sample.key:p.id,kept=!!studio.kept[k];
 const bar=node('div','marks');
 const keep=button(kept?'✓':'＋','mark',async()=>{
  try{await editStudio(profile,s=>mark(s,p,sample,kept?'release':'keep'));studio=loadStudio(profile);after();}
  catch{notification(tr('Could not save this change.','保存未成功。'));}
 });
 keep.setAttribute('aria-pressed',String(kept));keep.setAttribute('aria-label',kept?tr('Release kept item','取消保留'):tr('Keep item','保留条目'));
 const hide=button('×','mark hide',()=>{
  hideRequest={p,sample,after};get('reason').value='';moderationLabels();get('moderate').showModal();
 });
 hide.setAttribute('aria-label',sample?tr('Hide sample comment','隐藏示例评论'):tr('Hide this design','隐藏这件作品'));bar.append(keep,hide);return bar;
}
function moderationLabels(){
 const sample=!!hideRequest?.sample;
 get('hide-title').textContent=sample?tr('Hide sample comment','隐藏示例评论'):tr('Hide this design','隐藏这件作品');
 get('reason').placeholder=tr('Reason for review (optional)','改进原因（可选）');
 get('reason').setAttribute('aria-label',get('reason').placeholder);
 get('cancel-hide').textContent=tr('Cancel','取消');get('confirm-hide').textContent=tr('Hide','隐藏');
}
get('cancel-hide').onclick=()=>get('moderate').close();
get('confirm-hide').onclick=async()=>{
 if(!hideRequest)return;const {p,sample,after}=hideRequest;
 try{await editStudio(profile,s=>mark(s,p,sample,'hide',get('reason').value.trim().slice(0,180)));studio=loadStudio(profile);
  get('moderate').close();hideRequest=null;
  if(!sample && activePost===p.id){syncCatalog();back();}
  else if(sample && activePost===p.id){const at=scroll.scrollTop;await openDetail(p.id,entryDirect,false);scroll.scrollTop=at;}
  else after();
 }catch{notification(tr('Could not save this change.','保存未成功。'));}
};
function renderDiscussion(frame,p,model){
 const h=node('div','comment-head');const count=node('p','counts');const notes=node('div'),rows=node('div');
 const repaint=()=>{
  const s=state(),own=s.comments.filter(c=>c.postId===p.id);
  const samples=samplesFor(p,model,studio);
  const likes=p.likes+Number(s.liked.includes(p.id)), total=own.length+samples.length;
  count.textContent=tr(likes+' likes · '+total+' comments',likes+' 个赞 · '+total+' 条评论');
  rows.replaceChildren();
  const authors=profileName();
  for(const c of own){rows.append(commentRow(authors,c.text,c.at,true,()=>{
   if(window.confirm(tr('Delete your comment?','删除这条评论？')) && update(v=>{v.comments=v.comments.filter(x=>x.id!==c.id);}))repaint();
  }));}
  for(const c of samples){
    const row=commentRow(c.author,zh?c.zh:c.en,c.at,false);if(studio.settings.debug)row.append(markButtons(p,c,repaint));rows.append(row);
  }
 };
 h.append(node('h2','',tr('Discussion','讨论')),button(tr('Add comment','写评论'),'soft',()=>{
  composeTarget=p.id;onPosted=repaint;get('comment-input').value='';compose.showModal();get('comment-input').focus();
 }));
 notes.append(node('p','note',tr('Sample comments and starting likes are illustrative. Your activity is saved on this device.','示例评论与初始赞数用于展示。你的互动保存在本机。')));
 const generate=button(tr('Generate more sample replies · DeepSeek','生成更多示例评论 · DeepSeek'),'soft more',async()=>{
  if(replyJob)return;
  if(!cloudReady())return;
  const settings=studio.settings,epoch=studio.generation,ctrl=new AbortController();replyJob=ctrl;
  generate.disabled=true;generate.textContent=tr('Generating…','正在生成…');
  try{
   const extra=await generateReplies(p,samplesFor(p,model,studio),settings,ctrl.signal);
   if(ctrl.signal.aborted || activePost!==p.id)return;
   await editStudio(profile,s=>{
    if(ctrl.signal.aborted||s.generation!==epoch||s.hidden[p.id])throw Error('changed');
    const seen=new Set(samplesFor(p,model,s,true).map(row=>row.en.toLowerCase()));
    const fresh=extra.filter(row=>!seen.has(row.en.toLowerCase()));
    if(!fresh.length)throw Error('format');
    s.extra[p.id]=[...(s.extra[p.id]||[]),...fresh];
   });
   studio=loadStudio(profile);refreshReplies?.();
  }catch(e){if(!ctrl.signal.aborted)notification(tr('Could not add replies. Try again later.','未添加评论，可稍后重试。'));}
  finally{
   if(replyJob===ctrl)replyJob=null;
   const current=content.querySelector('[data-reply-request]');
   if(current && !replyJob){current.disabled=false;current.textContent=tr('Generate more sample replies · DeepSeek','生成更多示例评论 · DeepSeek');}
  }
 });
 generate.dataset.replyRequest='true';
 if(replyJob){generate.disabled=true;generate.textContent=tr('Generating…','正在生成…');}
 frame.append(h,count,notes,rows);
 if(cloudReady())frame.append(generate);
 else serviceAvailable().then(ok=>{if(ok && frame.isConnected)frame.append(generate);});
 if(studio.settings.debug)frame.append(markButtons(p,null,()=>{const pos=scroll.scrollTop;openDetail(p.id,entryDirect,false).then(()=>{scroll.scrollTop=pos;});}));
 refreshReplies=repaint;repaint();return repaint;
}
async function openDetail(id,direct=false,record=true){
 const p=byId.get(id);
 if(!p){notification(tr('Design unavailable','作品暂不可用'));return;}
 if(!activePost)feedScroll=scroll.scrollTop;
 const sameDetail=activePost===id;activePost=id;entryDirect=direct;clearSurface(sameDetail);
 const original=!!p.template && direct;
 const bar=node('div','headrow');bar.append(icon('back',tr('Back','返回'),back),node('h2','',original?tr('Project preview','作品预览'):tr('Design detail','作品详情')));header.append(bar);nav();
 if(record)update(s=>{s.history=[id,...s.history.filter(k=>k!==id)].slice(0,300);});
 const art=node('div','art'+(original?' original':''));art.style.background=p.surface;art.append(photo(p,false));content.append(art);
 const heading=node('h1',original?'original-title':'title',original?(zh?p.projectTitleZh:p.projectTitleEn):title(p));content.append(heading);
 if(original){
  const tags=node('div','tags');
  for(const name of (zh?p.tagsZh:p.tagsEn)||[]){const t=node('span','',name);t.style.background=p.surface;tags.append(t);}
  content.append(tags);
 }else{
  const line=node('p','minor sub',[p.generated?tr('AI design study','AI 设计习作'):tr('Design study','设计习作'),p.author,date.format(new Date(p.at))].join(' · '));content.append(line);
 }
 content.append(node('p','description',body(p)));
 const actions=node('div','reactions'+(original?' underline original-reactions':'')),discuss=node('section');content.append(actions,discuss);
 if(p.template){
  const frame=node('iframe');frame.title=title(p);frame.src='../concepts/index.html?id='+encodeURIComponent(p.template);art.replaceChildren(frame);
 }
 try{
  const model=p.parts?p:(await studies())[id];
  if(activePost!==id || !art.isConnected)return;
  if(!p.template){
   const canvas=node('canvas');canvas.tabIndex=0;canvas.setAttribute('role','img');
   canvas.setAttribute('aria-label',title(p)+tr('. Drag to rotate.','. 拖动旋转。'));art.replaceChildren(canvas);cleanup=showModel(canvas,model.parts);
  }
  displayedDetail={p,model};
  const repaint=renderDiscussion(discuss,p,model);
  reactions(actions,p,original,repaint);
 }catch{
  if(activePost!==id || !art.isConnected)return;
  discuss.append(button(tr('Retry loading design','重新加载作品'),'soft',()=>openDetail(id,direct,false)));
 }
}
window.addEventListener('message',e=>{
 if(e.origin!==location.origin || e.source!==parent || !e.data)return;
 const msg=e.data;
 if(msg.type==='ent207-back'){if(!ready)pendingCommand=null;else back();}
 if(msg.type==='ent207-open-post' && typeof msg.post==='string'){
  if(!ready)pendingCommand=msg.post;else if(activePost!==msg.post)openDetail(msg.post);
 }
});
async function start(){
 try{
  baseCatalog=await fetchJSON('catalog.json');syncCatalog();ready=true;
  const wanted=pendingCommand===undefined?initial:pendingCommand;
  if(wanted && byId.has(wanted))openDetail(wanted,!!initial);
  else if(initial && wanted)showMissing();
  else showList();
 }catch{
  content.replaceChildren(button(tr('Reload community','重新加载社区'),'soft',start));
 }
}
function showMissing(){
 clearSurface();entryDirect=true;activePost=null;
 const bar=node('div','headrow');bar.append(icon('back',tr('Back','返回'),back),node('h2','',tr('Design detail','作品详情')));header.append(bar);
 content.append(node('p','empty',tr('This design is no longer in the feed.','作品当前未在社区展示。')));
 nav();
}
window.ent207SetLanguage=async lang=>{
 zh=String(lang).startsWith('zh');document.documentElement.lang=zh?'zh-CN':'en';
 date=new Intl.DateTimeFormat(zh?'zh-CN':'en',{year:'numeric',month:'short',day:'numeric'});composeLabels();moderationLabels();
 if(!ready)return;
 if(entryDirect && !activePost && initial && !byId.has(initial)){showMissing();return;}
 const scrollAt=scroll.scrollTop,composing=compose.open,text=get('comment-input').value;
 const moderating=get('moderate').open,req=hideRequest,reason=get('reason').value;
 if(activePost)await openDetail(activePost,entryDirect,false);else {feedScroll=scrollAt;showList();}
 scroll.scrollTop=scrollAt;
 if(composing && displayedDetail){content.querySelector('.comment-head button')?.click();get('comment-input').value=text;}
 if(moderating && req){hideRequest=req;get('reason').value=reason;moderationLabels();get('moderate').showModal();}
};
addEventListener('storage',e=>{
 if(e.key!==studioKey(profile)||!ready)return;
 syncCatalog();if(get('compose').open || get('moderate').open)return;
 if(activePost && !byId.has(activePost)){back();return;}
 const at=scroll.scrollTop;
 if(activePost)openDetail(activePost,entryDirect,false).then(()=>{scroll.scrollTop=at;});
 else{feedScroll=at;showList();}
});
start();
