import {loadStudio,editStudio,clearUnkept,insertWorks} from '../community/studio-store.js';
import {preparedBatch,generatedBatch,cloudReady,serviceAvailable} from './generation.js';
const params=new URLSearchParams(location.search), profile=params.get('profile')||'guest';
let zh=(params.get('lang')||'en').startsWith('zh');
const tr=(en,cn)=>zh?cn:en;
const $=id=>document.getElementById(id);
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n;};
const button=(text,action,cls='soft block')=>{const b=el('button',text,cls);b.type='button';b.onclick=action;return b;};
const send=(type)=>{if(parent!==window)parent.postMessage({type},location.origin);};
let mode='prepared',draft={...loadStudio(profile).settings},busy=null,status=null,dialogView=null;
function closePage(){if(busy)busy.abort();if(parent===window)location.assign('../');else send('ent207-close');}
function summaryText(){
 const s=loadStudio(profile);
 return tr(s.works.length+' added designs · 60 built-in studies',s.works.length+' 件已添加作品 · 60 件内置习作');
}
function result(en,cn){status=[en,cn];$('result').textContent=tr(en,cn);}
function select(label,id,choices,value,set){
 const row=el('label',null,'studio-row'), field=el('select');field.id=id;
 for(const [key,txt] of choices){const o=el('option',txt);o.value=String(key);field.append(o);}
 field.value=String(value);field.disabled=!!busy;
 field.onchange=()=>set(field.value);row.append(el('span',label),field);return row;
}
function confirmBox(kind,en,cn,action){
 dialogView=()=>{
  const div=$('review-body'),row=el('div',null,'actions');
  div.replaceChildren(el('h2',kind()),el('p',tr(en,cn)));
  row.append(button(tr('Cancel','取消'),closeDialog,'soft'),button(kind(),()=>{closeDialog();action();},'solid'));div.append(row);
 };
 displayDialog();
}
function displayDialog(){dialogView?.();if(!$('review').open)$('review').showModal();}
function closeDialog(){dialogView=null;$('review').close();}
function errorMessage(e){
 if(e.name==='AbortError')return result('Cancelled. The previous collection is available.','已取消，原有作品仍可浏览。');
 const code=e.code||e.message;
 if(code==='format')return result('The response did not match the design format. Previous work is safe.','响应未通过作品格式检查，原有作品已保留。');
 if(code==='capacity'||e.name==='QuotaExceededError')return result('Browser storage is full. Clear unkept additions to make room.','浏览器存储已满，可清除未保留的新增作品后重试。');
 if(code==='configuration')return result('The shared generation service is unavailable. Prepared designs are available.','共享生成服务暂不可用，仍可添加预置作品。');
 if(code==='changed')return result('The collection changed. Review it before trying again.','作品集已发生变更，查看后可重试。');
 return result('Could not finish. Please try again later.','未能完成。可以稍后重试。');
}
async function saveSettings(){
 try{await editStudio(profile,s=>{s.settings={...draft};});result('Settings saved in this browser.','设置已保存在此浏览器。');}
 catch(e){errorMessage(e);}
}
async function add(replace){
 if(busy)return;
 const ctrl=new AbortController();busy=ctrl;
 const current=loadStudio(profile),version=current.generation;
 const settings={...draft},source=mode;
 result('Preparing…','准备中…');render();
 try {
  const works=source==='prepared'?await preparedBatch(current,settings.size,replace):
   await generatedBatch(settings,ctrl.signal,(i,n)=>result('Requesting design '+i+' of '+n+'…','正在请求第 '+i+' / '+n+' 件作品…'));
  if(ctrl.signal.aborted)throw new DOMException('Cancelled','AbortError');
  if(!works.length){result('All available prepared designs have been added.','已添加全部可用的预置习作。');return;}
  const count=await editStudio(profile,s=>{
   if(ctrl.signal.aborted)throw new DOMException('Cancelled','AbortError');
   if(s.generation!==version)throw Error('changed');
   s.settings={...settings};if(replace)clearUnkept(s);return insertWorks(s,works);
  });
  if(source==='prepared')result('Added '+count+' prepared designs.','已添加 '+count+' 件预置习作。');
  else result('Added '+count+' validated DeepSeek designs.','已添加 '+count+' 件通过检查的 DeepSeek 作品。');
 }catch(e){errorMessage(e);}finally{busy=null;render();}
}
function review(kept){
 const bucket=kept?'kept':'hidden';
 dialogView=()=>{
  const data=loadStudio(profile),rows=Object.values(data[bucket]);
  const wrap=$('review-body'),list=el('div',null,'review-list');
  wrap.replaceChildren(el('h2',kept?tr('Kept items','保留项目'):tr('Hidden items','隐藏项目')),list);
  if(!rows.length)list.append(el('p',tr('No items to review.','暂无条目。')));
  for(const r of rows){
   const name=zh?r.titleZh:r.titleEn;
   const b=button(name,()=>{
    const label=()=>kept?tr('Release','取消保留'):tr('Restore','恢复');
    confirmBox(label,r.titleEn,r.titleZh,async()=>{
     try{await editStudio(profile,s=>{delete s[bucket][r.key];});render();review(kept);}catch(e){errorMessage(e);}
    });
   },'review-row');
   if(r.reason)b.append(el('small',r.reason));
   list.append(b);
  }
  wrap.append(button(tr('Done','完成'),closeDialog));
 };
 displayDialog();
}
function render(){
 document.documentElement.lang=zh?'zh-CN':'en';document.title=tr('Content studio','内容工作室');
 $('bar').replaceChildren(el('h1',tr('Content studio','内容工作室')),button(tr('Done','完成'),closePage,'soft'));
 $('summary').textContent=summaryText();
 $('result').textContent=status?tr(...status):'';
 const f=$('settings'),available=cloudReady();
 f.replaceChildren(el('h2',tr('Generation settings','生成设置')),
  select(tr('Design source','作品来源'),'source',[['prepared',tr('Prepared studies','预置习作')],['deepseek','DeepSeek']],mode,v=>{mode=v;status=null;render();}),
  select(tr('Designs per batch','每批作品数'),'batch',[[1,'1'],[2,'2'],[4,'4'],[8,'8'],[12,'12'],[16,'16'],[24,'24']],draft.size,v=>draft.size=+v));
 if(mode==='deepseek'){
  f.append(el('p',tr('Each new design uses a request to the shared generation service.','每件新作品使用一次共享生成服务。'),'minor'),
   select(tr('Sample dates','示例日期跨度'),'months',[1,3,6,12].map(n=>[n,tr(n+' months',n+' 个月')]),draft.months,v=>draft.months=+v),
   select(tr('Comment style','评论风格'),'style',[['plain',tr('Plain','简洁')],['balanced',tr('Balanced','自然')],['playful',tr('Playful','活泼')]],draft.style,v=>draft.style=v));
  const l=el('label',tr('Creative brief','创作简报'),'brief-label'),area=el('textarea');area.id='brief';area.value=draft.brief;
  area.placeholder=tr('Materials, uses or details to discuss','想尝试的材质、用途或细节');area.maxLength=480;area.disabled=!!busy;area.oninput=()=>draft.brief=area.value;l.append(area);f.append(l);
  if(!available)f.append(el('p',tr('Online generation is unavailable. Choose Prepared studies to continue.','在线生成暂不可用，可选择预置习作继续。'),'minor'));
 }
 const apply=button(tr('Save settings','保存设置'),saveSettings),go=button(mode==='prepared'?tr('Add prepared batch','添加预置批次'):tr('Generate with DeepSeek','使用 DeepSeek 生成'),()=>add(false),'solid block');
 apply.disabled=!!busy;go.disabled=!!busy||(mode==='deepseek'&&!available);f.append(apply,go);
 if(busy)f.append(button(tr('Cancel request','取消请求'),()=>busy.abort()));
 const s=loadStudio(profile),m=$('moderation');m.className='section-group';m.replaceChildren(el('h2',tr('Moderation','内容管理')));
 const label=el('label',null,'studio-row'),check=el('input');check.id='debug';check.type='checkbox';check.checked=!!s.settings.debug;check.disabled=!!busy;
 label.append(el('span',tr('Show keep and hide controls in the feed','在社区显示保留与隐藏控件')),check);
 check.onchange=async()=>{draft.debug=check.checked;try{await editStudio(profile,v=>{v.settings.debug=draft.debug;});}catch(e){draft.debug=s.settings.debug;render();errorMessage(e);}};
 const replace=button(tr('Replace unkept additions','替换未保留的新增作品'),()=>{
  const msg=mode==='prepared'?['Replace unkept additions with a prepared batch?','用一批预置习作替换未保留的新增作品？']:
   ['Request a new batch of designs? The current additions are replaced after the batch passes validation.','请求一批新作品？整批作品通过检查后才替换现有的新增作品。'];
  confirmBox(()=>tr('Replace','替换'),...msg,()=>add(true));
 });replace.disabled=go.disabled;
 const clear=button(tr('Clear unkept additions','清除未保留的新增内容'),()=>{
  confirmBox(()=>tr('Clear additions','清除新增内容'),'Remove unkept added works and sample replies? Kept and hidden items remain available for review.','清除未保留的新增作品与示例评论？保留和隐藏的条目仍可从管理列表查看。',async()=>{
    try{await editStudio(profile,clearUnkept);result('Cleared unkept additions.','已清除未保留的新增内容。');render();}catch(e){errorMessage(e);}
  });
 },'soft block danger');clear.disabled=!!busy;
 m.append(label,button(tr('Kept items','保留项目')+' · '+Object.keys(s.kept).length,()=>review(true)),button(tr('Hidden items','隐藏项目')+' · '+Object.keys(s.hidden).length,()=>review(false)),replace,clear);
 if($('review').open)dialogView?.();
}
window.ent207SetLanguage=lang=>{zh=String(lang).startsWith('zh');render();};
$('settings').onsubmit=e=>e.preventDefault();
$('review').addEventListener('close',()=>{if(!$('review').open)dialogView=null;});
addEventListener('pagehide',()=>busy?.abort());
addEventListener('storage',render);
addEventListener('message',e=>{if(e.source===parent&&e.origin===location.origin && ['ent207-cloud-change','ent207-cloud-status'].includes(e.data?.type))render();});

render();

serviceAvailable().then(()=>render());
