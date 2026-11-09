const pane=document.getElementById('pane'), params=new URLSearchParams(location.search);
let zh=params.get('lang')==='zh';
const profile=params.get('profile')||'guest', tr=(en,cn)=>zh?cn:en;
const el=(tag,text='',cls='')=>{const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;};
const btn=(text,fn,cls='')=>{const b=el('button',text,cls);b.type='button';b.onclick=fn;return b;};
const text=(en,cn,cls='')=>el('p',tr(en,cn),cls);
const titles={
 menu:['Tools and earlier demos','更多工具与早期演示'],network:['Network and AI','联网与 AI'],marker:['Live marker test','实时识码测试'],
 voice:['Reading voice','朗读音色'],screen:['Screen-based test','屏幕联调'],pilot:['Earlier shelf demo','早期层板架演示'],
 puzzle:['15-step Luban puzzle','鲁班锁 15 步装配'],check:['Preparation checklist','装配准备清单'],
 color:['Colour and orientation test','颜色与朝向观察'],native:['Android hardware tests','安卓硬件测试'],buttons:['Button check','按钮测试']
};
const name=id=>tr(...titles[id]);
let section='menu',active=null,serial=0,clicks=0,voiceLine=null,voiceStatus=null,networkStatus=null;
let stage=0,found=new Set(),layer=1,screenLayer=1,checked=false,tagPairs=null,scrollPos=0;
const key=id=>'ent207.tools.'+id+'.v1.'+profile;
function read(id,fallback){try{return JSON.parse(localStorage.getItem(key(id)))??fallback;}catch{return fallback;}}
function write(id,v){try{localStorage.setItem(key(id),JSON.stringify(v));return true;}catch{return false;}}
layer=[1,2,3,4,5].includes(read('pilot-layer',1))?read('pilot-layer',1):1;
let checklist=read('check',0)===1?1:0;
function openRoute(target){
 if(parent===window){location.assign('../#'+target);return;}
 parent.postMessage({type:'ent207-tools-open',target},location.origin);
}
function close(){
 if(section!=='menu'){show('menu');return;}
 if(parent!==window)parent.postMessage({type:'ent207-close'},location.origin);else location.assign('../');
}
document.getElementById('back').onclick=close;
function navigation(label,ids){
 pane.append(el('h3',label));const group=el('div','','group');
 for(const id of ids){const b=btn(name(id),()=>show(id),'route');b.ariaLabel=name(id);group.append(b);}pane.append(group);
}
function menu(){
 navigation(tr('Diagnostics','能力检查'),['network','marker','voice','screen','color','buttons']);
 navigation(tr('Earlier projects','早期项目'),['pilot','puzzle','check']);
 navigation(tr('Demonstration phone','现场展示机'),['native']);pane.scrollTop=scrollPos;
}
function network(){
 pane.append(text('Shared AI availability for this web edition. Photos and write requests are sent only when you choose an online action.','查看网页体验版的共享 AI 服务状态。照片和生成请求仅在主动选择在线操作后发送。'),
  btn(tr('Check service availability','检查服务状态'),async()=>{
   const token=serial;networkStatus=['Checking…','正在检查…'];render();
   try{const {cloudReady}=await import('../services/cloud.js');const s=await cloudReady();
    networkStatus=s.configured&&s.enabled?['The shared service is configured and available.','共享服务已配置并可用。']:
     ['The shared service is unavailable. Prepared exercises and discussions are available.','共享服务暂不可用，仍可浏览预置练习与讨论。'];
   }catch{networkStatus=['The shared service is unavailable.','共享服务暂不可用。'];}
   if(token===serial && section==='network')render();
  },'block'),text(...(networkStatus||['','']),'hint'),
  btn(tr('Open Content studio','打开内容工作室'),()=>openRoute('studio'),'block'),
  btn(tr('Open photo review','打开照片复核'),()=>openRoute('review'),'block'));
}
function marker(){
 pane.append(text('Read the printed shelf markers with the browser camera, or decode the sample photograph. The reader reports the part and face.','用浏览器相机读取层板架标签，也可在样片上识码。识别结果包含半件和面。'),
   btn(tr('Open field guide','打开实物指导'),()=>openRoute('field'),'primary block'),
   btn(tr('Show a pair of test tags','显示一对测试标签'),()=>show('screen'),'block'));
}
function screen(){
 const choose=levelSelect(screenLayer,n=>{screenLayer=n;render();});pane.append(text('Display these face-A tags on a second screen. Open Live camera on the scanning device.','请用另一块屏幕显示以下两张 A 面码，在扫码设备上打开实时相机。'),choose);
 if(!tagPairs){const token=serial;fetch('tags/index.json').then(r=>r.json()).then(v=>{tagPairs=v;if(serial===token && section==='screen')render();}).catch(()=>{if(serial===token)pane.append(text('Could not load the tags. Reopen this tool to retry.','标签载入失败，可重新打开此工具重试。'));});return;}
 const pair=el('div','','pair');for(const [i,id] of tagPairs[screenLayer-1].entries()){
   const fig=el('figure'),img=el('img','','tag');img.src='tags/'+id+'.png';img.alt='ArUco '+id;fig.append(img,el('figcaption',String(id).padStart(3,'0')+' · '+tr('Half ','半件 ')+(i+1)+' · A'));pair.append(fig);
 }
 pane.append(pair,text('The pair comes from the registered five-tier shelf. A successful screen scan identifies a printed pattern.','本对编号取自当前五层架登记。屏摄成功表示读到了标签图样。','hint'),
  btn(tr('Open field guide','打开实物指导'),()=>openRoute('field'),'block'));
}
function voice(){
 const synth=window.speechSynthesis,code=zh?'zh':'en',setting='ent207.tts.voice.v1.'+code;
 let saved='';try{saved=localStorage.getItem(setting)||'';}catch{}
 const voices=synth?synth.getVoices().filter(v=>v.localService&&v.lang.toLowerCase().split(/[-_]/)[0]===code):[];
 const picker=el('select');picker.ariaLabel=tr('On-device voice','设备本地音色');
 picker.append(new Option(tr('System choice','系统选择'),''));for(const v of voices)picker.append(new Option(v.name+' · '+v.lang,v.voiceURI));
 if(voices.some(v=>v.voiceURI===saved))picker.value=saved;
 const onSave=btn(tr('Use this voice','使用此音色'),()=>{
  try{localStorage.setItem(setting,picker.value);voiceStatus=['Selection saved for English.','已保存中文音色。'];}
  catch{voiceStatus=['This browser could not save the selection.','此浏览器未能保存选择。'];}
  status.textContent=tr(...voiceStatus);
 },'block');
 const preview=btn(tr('Play sample','试读'),()=>{
  synth.cancel();voiceLine=new SpeechSynthesisUtterance(tr('That looks right. Hold the shelf steady, line up both slots, and lower it into place.','看起来正确。扶稳层板，对齐两个槽，再垂直插入。'));
  voiceLine.voice=voices.find(v=>v.voiceURI===picker.value)||voices[0];voiceLine.lang=voiceLine.voice.lang;
  voiceLine.onend=()=>{if(section==='voice'){voiceStatus=['Playback finished.','试读结束。'];status.textContent=tr(...voiceStatus);}};
  voiceLine.onerror=()=>{if(section==='voice'){voiceStatus=['Playback was interrupted or unavailable.','试读中断或不可用。'];status.textContent=tr(...voiceStatus);}};
  synth.speak(voiceLine);
 },'block');
 onSave.disabled=preview.disabled=picker.disabled=!voices.length;
 const status=text(...(voiceStatus||['','']),'hint');status.setAttribute('role','status');
 pane.append(text('Preview a locally installed browser voice. Choose English or Chinese at the top to set each language separately.','试读浏览器可用的设备本地音色，在顶部切换语言即可分别选择。'),picker,preview,onSave,status);
 if(!voices.length)pane.append(text('No local voice is available for this language in this browser. You can continue with written guidance.','当前浏览器没有此语言的本地音色，可继续使用文字指导。','hint'));
}
window.speechSynthesis?.addEventListener('voiceschanged',()=>{if(section==='voice')render();});
function levelSelect(value,changed){
 const select=el('select');select.ariaLabel=tr('Shelf layer','层板');const names=zh?['紫','蓝','肉色','黄','绿']:['Purple','Blue','Peach','Yellow','Green'];
 for(let i=1;i<=5;i++)select.append(new Option(tr('Layer ','层 ')+i+' · '+names[i-1],String(i)));select.value=String(value);select.onchange=()=>changed(Number(select.value));return select;
}
function pilot(){
 const saved=read('pilot',{});pane.append(text('One matched pair per layer. This earlier three-part rehearsal keeps its own practice record.','每层使用一对指定半件。这套早期三段预演单独保存体验记录。','hint'),
   levelSelect(layer,n=>{layer=n;write('pilot-layer',n);stage=0;found.clear();checked=false;render();}));
 const group=el('div','','pair'),names=tr('Half ','半件 ');
 for(let half=1;half<=2;half++){
  const b=btn(names+half,()=>{if(stage!==0)return;found.has(half)?found.delete(half):found.add(half);render();});
  b.setAttribute('aria-pressed',String(found.has(half)));b.disabled=stage!==0;
  const i=half===1?layer+2:layer+7,img=el('img');img.src='../guide/pieces/part-'+String(i).padStart(2,'0')+'.webp';img.alt='';b.prepend(img);group.append(b);
 }
 pane.append(el('h2',[tr('Find the two halves','找齐两片'),tr('Join the halves','拼合半件'),tr('Practice record','预演记录')][stage]),group);
 if(stage===0){
  pane.append(text('Tap each half to rehearse picking the pair.','点选两个半件，体验找齐配对。'));
  const next=btn(tr('View instruction','查看拼装指导'),()=>{stage++;render();},'primary block');next.disabled=found.size!==2;pane.append(next);
  if(saved[layer])pane.append(btn(tr('Open saved practice record','查看已保存的预演记录'),()=>{stage=2;render();},'block'));
 }else if(stage===1){
  pane.append(text('Set the two broad faces parallel. Align the matching tab and recess, keep both notches facing the same end, then join with a straight downward motion.','将两片大面摆平行，对齐凸榫与凹槽，缺口朝同一端，再垂直向下拼合。'));
  const label=el('label','','pick-line'),cb=el('input');cb.type='checkbox';cb.checked=checked;cb.onchange=()=>{checked=cb.checked;render();};
  label.append(cb,document.createTextNode(tr('I reviewed the move','我已查看动作')));pane.append(label);
  const next=btn(tr('Save practice confirmation','保存预演确认'),()=>{if(!write('pilot',{...read('pilot',{}),[layer]:true})){pane.append(text('Could not save the record.','保存未成功。'));return;}stage=2;render();},'primary block');next.disabled=!checked;pane.append(next);
 }else{
  pane.append(text('You confirmed this browser rehearsal.','已保存你对本次网页预演的确认。'),
   btn(tr('Repeat this layer','重练本层'),()=>{stage=0;found.clear();checked=false;render();},'block'),
   btn(tr('Open full assembly guide','打开完整装配指导'),()=>openRoute('guide'),'primary block'));
 }
}
function checklistPage(){
 const steps=[
  [tr('Identify the grey insertion pin','核对灰色插入销'),tr('Compare the grey part with its CAD envelope of approximately 61.8 × 13.2 × 15.4 mm, printed at 100%. These dimensions help identify the part.','核对灰色插入销。模型按毫米导出并按 100% 打印，外包尺寸约为 61.8 × 13.2 × 15.4 毫米，用于识别零件。')],
  [tr('Prepare the movement sequence','准备装配顺序'),tr('Use the 15 recorded disassembly moves in reverse. Check which pieces move together, where to leave clearance, and how to return each piece to its final position.','按记录的 15 个拆卸动作倒序查看。核对共同移动的零件、需要留空的位置，再确认各件如何回到最终位置。')]
 ];
 pane.append(el('small',tr('Preparation ','准备项 ')+(checklist+1)+' / 2'),el('h2',steps[checklist][0]),el('p',steps[checklist][1]),
  btn(checklist?tr('Previous item','上一项'):tr('Next item','下一项'),()=>{checklist=1-checklist;write('check',checklist);render();},'primary block'),
  btn(tr('Open the 15-step guide','打开 15 步动画'),()=>show('puzzle'),'block'));
}
function hardware(){
 pane.append(text('These tools use the installed Android demonstration build.','以下工具由现场安卓展示包提供。'));
 const list=el('ul','','hardware-list');for(const pair of [
  ['Huawei AR Engine measures tracking and anchors.','华为 AR Engine 提供空间跟踪与锚点。'],
  ['Native camera AR preview uses the device camera API.','原生相机 AR 预览使用设备相机接口。'],
  ['Calibration collects lens-specific camera frames and board observations.','相机标定采集指定镜头画面与标定板观测。'],
  ['Board measurement diagnostics compare camera calibration and registered geometry.','板卡测量诊断核对镜头标定与登记几何。']])list.append(el('li',tr(...pair)));
 pane.append(list,btn(tr('Open browser marker reader','使用浏览器识码'),()=>openRoute('field'),'block'));
}
function render(){
 document.documentElement.lang=zh?'zh-CN':'en';document.title=name(section);document.getElementById('heading').textContent=name(section);
 document.getElementById('back').ariaLabel=tr('Back','返回');
 if(active){active.language(zh?'zh':'en');return;}
 if(section==='puzzle' && pane.querySelector('iframe')){pane.querySelector('iframe').contentWindow?.ent207SetLanguage?.(zh?'zh':'en');return;}
 pane.replaceChildren();
 if(section==='menu')return menu();if(section==='pilot')return pilot();if(section==='native')return hardware();
 if(section==='check')return checklistPage();if(section==='marker')return marker();if(section==='voice')return voice();if(section==='screen')return screen();if(section==='network')return network();
 if(section==='puzzle'){const f=el('iframe','','screen-frame');f.title=name(section);f.src='player/index.html?'+new URLSearchParams({profile,lang:zh?'zh':'en'});pane.append(f);return;}
 if(section==='color'){
  const token=serial;import('./colour.js').then(({mountProbe})=>{if(serial===token && !active)active=mountProbe(pane,zh?'zh':'en',profile);}).catch(()=>{if(serial===token)pane.append(text('Could not load colour analysis.','颜色分析载入失败。'));});return;
 }
 if(section==='buttons'){const line=el('p',clicks?tr('Button presses: ','点击次数：')+clicks:tr('Hello, world!','你好，世界！'));line.setAttribute('role','status');
  pane.append(line,btn(tr('Test button','测试按钮'),()=>{clicks++;render();},'primary block'));}
}
function show(id){
 if(section==='menu')scrollPos=pane.scrollTop;
 if(section==='voice'){if(voiceLine)window.speechSynthesis?.cancel();voiceLine=null;voiceStatus=null;}
 active?.dispose();active=null;serial++;section=id;
 pane.replaceChildren();pane.scrollTop=0;render();
}
window.ent207SetLanguage=lang=>{zh=String(lang).startsWith('zh');if(section==='voice'){window.speechSynthesis?.cancel();voiceStatus=null;}render();};
window.addEventListener('pagehide',()=>{if(voiceLine)window.speechSynthesis?.cancel();active?.dispose();});
show(Object.hasOwn(titles,params.get('view'))?params.get('view'):'menu');
