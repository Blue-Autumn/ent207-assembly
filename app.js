import {loadStudio,mergeCatalog,thumbnail,visibleActivity,studioKey} from './community/studio-store.js';
import './guide/work-surface.js?v=8';
import {mountWelcome} from './welcome.js?v=g26';
import {aiPhase,aiNotice} from './services/academic-year.js';
'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const navigateMotion=ShelfFeedback.navigation($('content'));
  const paths = {
    back:'M15 5l-7 7 7 7', next:'m9 5 7 7-7 7', arrow:'M3 12h17m-7-7 7 7-7 7',
    projects:'m3 7 9-4.5L21 7l-9 4.5zM3 12l9 4.5 9-4.5M3 17l9 4.5 9-4.5',
    community:'M3 15V6a2 2 0 0 1 2-2h11a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H9l-5 4v-4ZM18 8h2a1 1 0 0 1 1 1v12l-5-3h-5',
    person:'M16 7a4 4 0 1 1-8 0 4 4 0 1 1 8 0ZM3 22v-2a9 7 0 0 1 18 0v2',
    saved:'M6 3h12v18l-6-4-6 4z', heart:'M12 21 3.6 13a5.7 5.7 0 0 1 8.4-7.6A5.7 5.7 0 0 1 20.4 13Z',
    history:'M3 10a9 9 0 1 1 1 7M3 4v6h6M12 7v6l3 1.5',
    comments:'M4 4h16v12H10l-6 5zM8 8h8M8 12h5',
    language:'M21 12a9 9 0 1 1-18 0 9 9 0 1 1 18 0ZM3 12h18m-9-9a16 16 0 0 1 0 18 16 16 0 0 1 0-18Z',
    voice:'M3 9h4l5-5v16l-5-5H3zM16 8q3 4 0 8M19 4q6 8 0 16',
    camera:'M3 7h4l2-3h6l2 3h4v13H3zM16 13a4 4 0 1 1-8 0 4 4 0 1 1 8 0Z',
    tools:'M3 6h4m5 0h9M3 12h10m5 0h3M3 18h2m5 0h11M12 6a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0ZM18 12a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0ZM10 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0Z',
    tick:'m4 12 5 5L20 6',
    vote:'M4 20h16M6 16v-4m6 4V5m6 11V9',
    video:'M3 6h18v13H3zM10 9l5 3.5-5 3.5z',
    download:'M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4',
    external:'M6 18 18 6M7 6h11v11'
  };
  function icon(name) {
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
    svg.setAttribute('viewBox','0 0 24 24'); svg.setAttribute('class','icon'); svg.setAttribute('aria-hidden','true');
    const path=document.createElementNS(svg.namespaceURI,'path');
    path.setAttribute('d',paths[name]); svg.append(path); return svg;
  }
  function node(tag, cls='', text) {
    const el=document.createElement(tag);
    if (cls) el.className=cls;
    if (text !== undefined) el.textContent=text;
    return el;
  }
  function button(label, cls, click) {
    const el=node('button',cls,label); el.type='button'; el.onclick=click; return el;
  }
  function picture(src, eager=false) {
    const img=node('img'); img.alt='';img.decoding='async';
    img.loading=eager?'eager':'lazy'; img.src=src; return img;
  }
  const memory=new Map();
  function read(key, fallback=null) {
    try { return localStorage.getItem(key) ?? memory.get(key) ?? fallback; }
    catch { return memory.get(key) ?? fallback; }
  }
  function write(key, value) {
    memory.set(key,value);
    try { localStorage.setItem(key,value); return true; } catch { return false; }
  }
  function readJSON(key,fallback) {
    try { return JSON.parse(read(key)) ?? fallback; } catch { return fallback; }
  }
  const profilesKey='ent207.profiles.v2', activeKey='ent207.active.v2', languageKey='ent207.lang.v2', voiceKey='ent207.voice.v2';
  const queryLang=new URLSearchParams(location.search).get('lang');
  let lang=(queryLang || read(languageKey,'en')).startsWith('zh')?'zh':'en';
  const tr=(en,zh)=>lang==='zh'?zh:en;
  const resources={
    vote:{href:'https://cn.unoanalyzer.com/app/project/fr5u-fIXm7hR1IDNXCV7sb4hQzplenwSRcC3gL98Hoc',
      short:['Vote','UNO 投票'],name:['Vote for ENT207-G26','给 ENT207-G26 投票'],source:['UNO Analyzer','UNO Analyzer']},
    video:{href:'https://video.xjtlu.edu.cn/Mediasite/Play/bb27bd33388b4d85884a4c5ca99be59d1d',
      short:['Demo video','演示视频'],name:['Watch the demo','观看演示视频'],source:['XJTLU Mediasite','西浦 Mediasite']},
    download:{href:'https://pan.quark.cn/s/fd6e4a6732be?pwd=tfWZ&entry=webother',
      short:['Get APK','下载 APK'],name:['Download the Android app','下载 Android 安装包'],source:['Quark Drive · Code tfWZ','夸克网盘 · 提取码 tfWZ']}
  };
  function externalLink(key,cls) {
    const a=node('a',cls);a.href=resources[key].href;a.target='_blank';a.rel='noopener noreferrer';
    return a;
  }
  function resourceLink(key,expanded=false) {
    const resource=resources[key],a=externalLink(key,'resource-link resource-'+key);
    a.append(icon(key));
    if(expanded){
      const label=node('span','resource-label');
      label.append(node('strong','',tr(...resource.name)),node('small','',tr(...resource.source)));
      a.append(label,icon('external'));
    }else{
      a.append(node('span','',tr(...resource.short)));
    }
    return a;
  }
  function openResources() {
    const dialog=$('setting'),title=node('h2','',tr('Project links & downloads','项目链接与下载'));
    title.id='setting-title';dialog.setAttribute('aria-labelledby',title.id);
    const choices=node('div','resource-list');
    for(const key of Object.keys(resources))choices.append(resourceLink(key,true));
    $('setting-body').replaceChildren(title,choices,button(tr('Close','关闭'),'secondary',()=>dialog.close()));
    dialog.showModal();
  }
  $('edition-links').onclick=openResources;
  let welcomeDone=false, cameraPreferred=false, cameraUnavailable=false;
  function updateEdition() {
    document.documentElement.lang=lang==='zh'?'zh-CN':'en';
    document.title=tr('Assembly Assistant','装配助手')+' · ENT207';
    $('edition-label').textContent=tr('Web edition · Full app on Android','网页体验版 · 完整版在 Android');
    $('edition-links').textContent=tr('View links','查看下载');
    $('welcome-links').replaceChildren(...Object.keys(resources).map(key=>resourceLink(key)));
    for(const b of $('edition').querySelectorAll('[data-language]')) {
      b.setAttribute('aria-pressed',String(b.dataset.language===lang));
    }
  }
  function selectLanguage(value) {
    const changed=value!==lang;
    lang=value;write(languageKey,value);
    const url=new URL(location.href);
    if(url.searchParams.has('lang'))url.searchParams.set('lang',value);
    history.replaceState(history.state,'',url);
    updateEdition();
    if(!welcomeDone||!changed)return;
    if(frame){
      const modules=parkedGuide?[parkedGuide.frame,frame]:[frame];
      for(const child of modules){
        const api=child.contentWindow?.ent207SetLanguage;
        if(typeof api==='function'){api(value);continue;}
        const src=new URL(child.src);src.searchParams.set('lang',value);
        child.contentWindow.location.replace(src.href);
      }
      if(!$('tabs').hidden)showTabs(route);
    }else {
      const draft=$('nickname')?.value;
      rememberScroll();render();
      if(draft!==undefined && $('nickname'))$('nickname').value=draft;
    }
  }
  for(const control of $('edition').querySelectorAll('[data-language]')){
    control.onclick=()=>selectLanguage(control.dataset.language);
  }

  const profiles=()=>{ const all=readJSON(profilesKey,[]);return Array.isArray(all)?all.filter(p=>p&&typeof p.id==='string'&&typeof p.name==='string'):[]; };
  const current=()=>profiles().find(p=>p.id===read(activeKey));
  const owner=()=>current()?.id || 'guest';
  const conceptInfo=[
    ['stool','Small stool','一张小凳','#eee4d9'],['lamp','Desk lamp','桌边的一束光','#edf0de'],
    ['recordplayer','Record player','唱片机','#e8e4dd'],['bridge','Footbridge','木栈桥','#e1e9e4'],
    ['bookstand','Book stand','桌上书立','#e7e4ee'],['tray','Catch-all tray','随手收纳盘','#e4ece8'],
    ['xylophone','Xylophone','六音木琴','#f1e4d6'],['drawers','Mini drawers','小抽屉柜','#e7e9d9'],
    ['plantstand','Plant stand','窗边花架','#e9ebdf'],['organizer','Desk organizer','分格收纳架','#eee5dd']
  ];
  const builtIns=new Map(conceptInfo.map(([id,titleEn,titleZh,surface])=>[id,{id,titleEn,titleZh,surface}]));
  let baseCatalog=[],catalog=[],posts=new Map(builtIns);
  function refreshCatalog(){
    const studio=loadStudio(owner());
    catalog=mergeCatalog(baseCatalog,studio);
    posts=new Map([...builtIns,...catalog.map(p=>[p.id,p])]);
    for(const key of Object.keys(studio.hidden))posts.delete(key);
  }
  const activityKey=()=> 'ent207.community.v1.'+owner();
  function activity() {
    const raw=readJSON(activityKey(),{});
    return Object.fromEntries(['saved','liked','history','comments'].map(key=>[key,
      Array.isArray(raw[key])?raw[key]:key==='saved' && owner()==='guest'?[...builtIns.keys()]:[]]));
  }
  function rememberShelf() {
    const social=activity();
    social.history=['shelf',...social.history.filter(id=>id!=='shelf')].slice(0,100);
    write(activityKey(),JSON.stringify(social));
  }
  function progress() {
    const g=readJSON('ent207.guide.v2:'+owner(),null);
    return g && Array.isArray(g.done) ? Math.min(16,g.done.length) : null;
  }
  function progressText(done) {
    return done==null ? tr('Open to preview the finished shelf.','点开，看看完整的作品。') :
      tr(done+' of 16 steps confirmed','已确认 '+done+' / 16 步');
  }

  const home='projects', scrolls=new Map();
  let route='',scroll=null,frame=null,childPost=null,restorePost=false,parkedGuide=null;
  const roots=['projects','shelf','community','profile','account','guide','progress','workshop','field','studio','tools','review','voice','camera-test','wide-camera'];
  const collections=['favorites','liked','history','comments'];
  function parse() {
    const hash=location.hash.slice(1);
    if (roots.includes(hash)||/^post\/[a-zA-Z0-9_-]{1,90}$/.test(hash) || (hash.startsWith('library/') && collections.includes(hash.slice(8)))) return hash;
    return home;
  }
  function rememberScroll() {
    if(scroll && route) scrolls.set(owner()+'/'+route,scroll.scrollTop);
  }
  function navigate(next) {
    if(next===route) return;
    rememberScroll();
    if(next==='shelf') rememberShelf();
    const url=new URL(location.href); url.hash=next===home?'':next;
    history.pushState({depth:(Number(history.state?.depth)||0)+1},'',url);
    render();
  }
  function back() {
    if (history.state?.depth>0) history.back();
    else {
      const fallback=['guide','workshop'].includes(route)?'shelf':route==='account'||route==='progress'||route.startsWith('library/')?'profile':home;
      const url=new URL(location.href);url.hash=fallback===home?'':fallback;
      history.replaceState({depth:0},'',url);render();
    }
  }
  const communityList=()=>route==='community'||route.startsWith('library/');
  function childCommand(type,post) {
    frame?.contentWindow?.postMessage({type,post},location.origin);
  }
  addEventListener('popstate',()=>{
    if (parse()===route && frame && communityList()) {
      const target=history.state?.post || null;
      if(target!==childPost) childCommand(target?'ent207-open-post':'ent207-back',target);
      return;
    }
    rememberScroll();render();
  });
  addEventListener('keydown',e=>{
    if(e.key==='Escape' && welcomeDone && !$('setting').open && route!==home) {
      e.preventDefault();back();
    }
  });
  if(typeof history.state?.depth!=='number') history.replaceState({depth:0},'');
  function heading(title,sub=false) {
    const header=$('heading');header.hidden=false;
    if(sub) {
      const b=button('','back',back);b.setAttribute('aria-label',tr('Back','返回'));b.append(icon('back'));header.append(b);
    }
    header.append(node('h1',sub?'subheading':'',title));
  }
  function scroller(tabs=false) {
    scroll=node('div','scroller'+(tabs?' with-tabs':''));$('content').append(scroll);return scroll;
  }
  const tabsConfig=[['projects','projects','Projects','作品'],['community','community','Community','社区'],['profile','person','My space','我的']];
  function showTabs(selected) {
    const nav=$('tabs');nav.hidden=false;nav.setAttribute('aria-label',tr('Main navigation','主导航'));
    nav.style.setProperty('--selected',tabsConfig.findIndex(t=>t[0]===selected));
    nav.replaceChildren();
    for (const [id,symbol,en,zh] of tabsConfig) {
      const b=button('',selected===id?'active':'',()=>navigate(id));b.append(icon(symbol),node('span','',tr(en,zh)));
      if (selected===id) b.setAttribute('aria-current','page');nav.append(b);
    }
  }
  const shelfTitle=()=>tr('Five-tier shelf','五层隔板架');
  function makeGrid(ids,savedProjects=false) {
    const grid=node('div','tiles'),cols=[node('div','tile-col'),node('div','tile-col')];grid.append(...cols);
    ids.forEach((id,i)=>{
      const p=(savedProjects && builtIns.get(id)) || posts.get(id);if(!p) return;
      const title=tr(p.titleEn,p.titleZh);
      const b=button('','tile'+(i%3===0?' high':''),()=>navigate('post/'+id));b.style.backgroundColor=p.surface || '#e5e9df';
      b.append(picture(builtIns.has(id)?'concepts/'+id+'-poster.png':thumbnail(p)),node('span','',title));cols[i%2].append(b);
    });
    return grid;
  }
  function renderSaved(block) {
    const saved=activity().saved.filter(id=>posts.has(id));
    block.replaceChildren(node('h2','section-title',tr('Saved projects','已收藏的作品')));
    if(saved.length) {
      const set=new Set(saved);
      block.append(makeGrid([...builtIns.keys()].filter(k=>set.has(k)).concat(saved.filter(k=>!builtIns.has(k))),true));
    } else block.append(node('p','sub',tr('Save a design from the community to find it here.','在社区收藏作品，就能在这里看到。')),
      button(tr('Explore community','逛逛社区'),'secondary',()=>navigate('community')));
  }
  function projectsPage() {
    heading(tr('Assembly Assistant','装配助手'));
    const body=scroller(true);
    const intro=node('div','project-intro');
    intro.append(node('p','sub',tr('Start with a small project.','从一件小作品开始。')));
    body.append(node('h2','intro',tr('Piece by piece,\nmake it yours.','一点一点，\n变成喜欢的样子。')),intro);
    const card=button('','shelf-card',()=>navigate('shelf'));
    const img=picture('concepts/rack-poster.png',true);img.className='cover-art';img.fetchPriority='high';
    const copy=node('span','shelf-copy');copy.append(node('strong','',shelfTitle()),node('small','',progressText(progress())));
    const arrow=node('span','open-arrow');arrow.append(icon('arrow'));card.append(img,copy,arrow);
    const launches=node('div','quick-launch');
    const guide=button('','quick primary',()=>navigate('guide'));guide.append(icon('camera'),node('span','',tr('Guided assembly','装配指导')));
    launches.append(button(tr('Virtual assembly','虚拟试拼'),'quick',()=>navigate('workshop')),guide);
    const samples=button('','photo-link',()=>navigate('field'));
    samples.append(node('span','',tr('Try sample photos','体验实物样片')),icon('next'));
    intro.append(samples);
    if(cameraUnavailable){
      const notice=node('p','home-notice',tr('Camera is off. Try sample photos, or open the camera later in Guided assembly.',
        '相机未开启。可以先体验样片，也可以稍后在「装配指导」里开启相机。'));
      notice.setAttribute('role','status');body.append(notice);
    }
    const vote=externalLink('vote','vote-banner');
    const chart=node('span','vote-chart');chart.setAttribute('aria-hidden','true');
    chart.append(node('i'),node('i'),node('i'),node('i'));
    const voteLabel=node('span','vote-label');
    voteLabel.append(node('small','','UNO Analyzer'),node('strong','',tr('Vote for us','给我们投票')));
    vote.append(chart,voteLabel,icon('external'));
    body.append(card,launches,vote);
    if(aiPhase()!=='open')body.append(node('p','home-notice',aiNotice(tr)));
    const block=node('section');block.id='saved-projects';renderSaved(block);body.append(block);showTabs('projects');
  }
  function shelfPage() {
    heading(tr('Project preview','作品预览'),true);
    const body=scroller();
    const rack=node('iframe','rack-view');rack.src='workshop/rack.html';rack.title=tr('Five-tier shelf. Drag to rotate.','可拖动旋转的五层隔板架');
    body.append(rack,node('h2','detail-title',shelfTitle()));
    const done=progress();
    if(done!=null) {
      const bar=node('progress');bar.max=16;bar.value=done;bar.setAttribute('aria-label',progressText(done));
      body.append(node('p','sub',progressText(done)),bar);
    }
    body.append(resourceLink('video',true));
    const first=done===16?tr('Review my assembly','回看我的装配'):done?tr('Continue assembly','继续我的装配'):tr('Start building','开始这件作品');
    $('actions').append(button(first,'primary',()=>navigate(done===16?'progress':'guide')),
      button(tr('Virtual assembly','虚拟试拼'),'secondary',()=>navigate('workshop')));
  }
  function settingsRow(symbol,label,click) {
    const b=button('','setting-row',click);b.append(icon(symbol),node('span','',label),icon('next'));return b;
  }
  function populateIdeas(box) {
    box.replaceChildren(node('h2','section-title',tr('Recommended ideas','作品推荐')),makeGrid(catalog.map(p=>p.id)));
  }
  function profilePage() {
    heading(tr('My space','我的'));
    const body=scroller(true),account=current();
    const card=node('section','profile-card');
    card.append(icon('person'),node('h2','',account?.name || tr('Guest','本机试用')),
      node('p','',account?tr('Your assembly progress is saved in this browser.','你的装配进度保存在此浏览器中。'):tr('Guest progress stays in this browser. Sign in for a separate profile.','访客进度保存在此浏览器。登录后可单独记录。')),
      button(account?tr('Account','账号管理'):tr('Sign in or register','登录 / 注册'),'primary',()=>navigate('account')));
    body.append(card);
    const stats=node('div','shortcuts'),social=visibleActivity(activity(),loadStudio(owner()),posts);
    for (const [view,key,symbol,en,zh] of [
      ['favorites','saved','saved','Saved','收藏'],['liked','liked','heart','Likes','喜欢'],['history','history','history','History','浏览'],['comments','comments','comments','Comments','评论']
    ]) {
      const b=button('','',()=>navigate('library/'+view)), stat=node('span','stat');
      stat.append(icon(symbol),node('span','',String(social[key].length)));b.append(stat,node('span','',tr(en,zh)));stats.append(b);
    }
    body.append(stats,node('h2','group-label',tr('My assembly','我的装配')));
    const resume=button('','assembly-row',()=>navigate('progress'));
    resume.append(node('span','',shelfTitle()),node('small','',progress()==null?tr('Open your assembly steps.','查看这件作品的装配步骤。'):progressText(progress())));
    body.append(resume,node('h2','group-label',tr('Preferences','偏好设置')));
    const settings=node('div','settings');
    settings.append(settingsRow('download',tr('Project links & downloads','项目链接与下载'),openResources),
      settingsRow('language',tr('Language','语言'),()=>openSetting('lang')),
      settingsRow('voice',tr('Voice','语音'),()=>navigate('voice')),
      settingsRow('community',tr('Content studio','内容工作室'),()=>navigate('studio')),
      settingsRow('tools',tr('Tools and earlier demos','工具与旧演示'),()=>navigate('tools')));
    body.append(settings);const ideas=node('section');ideas.id='recommended';body.append(ideas);if(catalog.length) populateIdeas(ideas);
    showTabs('profile');
  }
  function openSetting(type) {
    const dialog=$('setting'),box=$('setting-body'),isLang=type==='lang', choices=node('div','choices');
    const title=node('h2','',isLang?tr('Language','语言'):tr('Spoken guidance','语音指引'));title.id='setting-title';
    dialog.setAttribute('aria-labelledby',title.id);
    box.replaceChildren(title);
    if(!isLang) box.append(node('p','sub',tr('Read the current instruction aloud during guided assembly.','装配时朗读当前步骤的提示。')));
    const options=isLang?[['en','English'],['zh','简体中文']]:[['on',tr('On','开启')],['off',tr('Off','关闭')]];
    for(const [value,label] of options) {
      const active=value===(isLang?lang:read(voiceKey,'off'));
      const b=button('','choice',()=>{
        dialog.close();
        if(isLang)selectLanguage(value);
        else write(voiceKey,value);
      });
      b.append(node('span','',label));b.setAttribute('aria-pressed',String(active));if(active)b.append(icon('tick'));choices.append(b);
    }
    box.append(choices,button(tr('Close','关闭'),'secondary',()=>dialog.close()));dialog.showModal();
  }
  function enterProfile(name) {
    const all=profiles();
    const normalized=name.trim().normalize('NFC');
    let p=all.find(item=>item.name.toLowerCase()===normalized.toLowerCase());
    if(!p) {
      const id=crypto.randomUUID?crypto.randomUUID():Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-');
      p={id,name:normalized || tr('Demo profile','体验档案')};
      all.push(p);write(profilesKey,JSON.stringify(all));
    }
    write(activeKey,p.id);
  }
  function accountPage() {
    heading(tr('Account','账号'),true);
    const account=current(),body=scroller(),hero=node('section','account-hero');
    hero.append(icon('projects'),node('h2','',account?.name || tr('Local sign-in','本地体验登录')),node('p','',
      account?tr('Your saved work stays in this browser.','你的记录保存在此浏览器中。'):tr('Use a nickname for this demo. Open the same name to return to your work.','输入昵称即可体验。下次使用相同昵称可继续装配。')));
    body.append(hero);
    const form=node('form','account-form');body.append(form);
    if(account) {
      form.append(button(tr('Sign out','退出当前档案'),'primary',()=>{write(activeKey,'guest');back();}));
    } else {
      const label=node('label','',tr('Nickname','昵称')),name=node('input');name.id='nickname';name.maxLength=40;name.name='nickname';name.autocomplete='off';name.autocapitalize='none'; name.placeholder=tr('Choose a display name','输入体验昵称');label.htmlFor=name.id;
      const submit=node('button','primary',tr('Continue','继续'));submit.type='submit'; form.append(label,name,submit);
      form.onsubmit=e=>{e.preventDefault();enterProfile(name.value);back();};
    }
  }
  function embed(path,title,args={}) {
    frame=node('iframe','module');
    if(['field/index.html','guide/index.html','review/index.html','tools/index.html','tools/wide-camera.html'].includes(path))frame.allow="camera 'self'";
    const url=new URL(path,document.baseURI);url.search=new URLSearchParams({lang,profile:owner(),...args}).toString();
    frame.title=title;frame.src=url.href;$('content').append(frame);
    navigateMotion(route,frame);
  }
  function render() {
    if(!welcomeDone)return;
    refreshCatalog();
    if($('setting').open)$('setting').close();
    const next=parse();
    if(next==='field' && (route==='guide'||route==='progress') && frame) {
      parkedGuide={route,frame};frame.hidden=true;route=next;
      embed('field/index.html',tr('Sample photos','实物样片'),{v:'8'});return;
    }
    if(parkedGuide && next===parkedGuide.route) {
      frame.remove();route=parkedGuide.route;frame=parkedGuide.frame;frame.hidden=false;
      navigateMotion(route);parkedGuide=null;return;
    }
    parkedGuide=null;
    route=parse();scroll=null;frame=null;childPost=null;restorePost=!!history.state?.post;
    document.documentElement.lang=lang==='en'?'en':'zh-CN';
    document.title=tr('Assembly Assistant','装配助手')+' · ENT207';
    for(const id of ['heading','content','actions']) $(id).replaceChildren();
    $('heading').hidden=true;$('tabs').hidden=true;
    switch(route) {
      case home:projectsPage();break;
      case 'shelf':shelfPage();break;
      case 'profile':profilePage();break;
      case 'account':accountPage();break;
      case 'guide':case 'progress':embed('guide/index.html',tr('Guided assembly','装配指导'),{page:route==='progress'?'progress':'work',camera:cameraPreferred?'auto':'manual',v:'8'});break;
      case 'workshop':embed('workshop/index.html',tr('Virtual assembly','虚拟试拼'));break;
      case 'field':embed('field/index.html',tr('Sample photos','实物样片'),{v:'8'});break;
      case 'studio':embed('studio/index.html',tr('Content studio','内容工作室'));break;
      case 'camera-test':embed('tools/camera-bench.html',tr('Recognition speed test','本机识别测速'));break;
      case 'wide-camera':embed('tools/wide-camera.html',tr('Check rear cameras','检查后置广角'),{v:'7'});break;
      case 'tools':case 'voice':embed('tools/index.html',tr('Tools and earlier demos','工具与旧演示'),route==='voice'?{view:'voice'}:{});break;
      case 'review':{
        const active=readJSON('ent207.guide.v2:'+owner(),{})?.active;
        embed('review/index.html',tr('AI photo review','AI 照片复核'),{task:typeof active==='string'?active:'prepare:左侧板',camera:cameraPreferred?'auto':'manual',v:'8'});break;
      }
      case 'community':embed('community/index.html',tr('Community','社区'));showTabs(route);break;
      default:{
        const [type,id]=route.split('/');
        embed('community/index.html',tr('Design community','设计社区'),type==='library'?{view:id}:{post:id});
      }
    }
    if(!frame)navigateMotion(route);
    if(scroll) scroll.scrollTop=scrolls.get(owner()+'/'+route)||0;
  }
  addEventListener('message',event=>{
    if(event.origin!==location.origin || !frame || event.source!==frame.contentWindow)return;
    switch(event.data?.type) {
      case 'ent207-close':back();break;
      case 'ent207-camera-choice':if(['guide','progress'].includes(route)){cameraPreferred=event.data.enabled===true;if(cameraPreferred)cameraUnavailable=false;}break;
      case 'ent207-open-shelf':navigate('shelf');break;
      case 'ent207-field':navigate('field');break;
      case 'ent207-studio':navigate('studio');break;
      case 'ent207-review-close':back();break;
      case 'ent207-tools-open':if(['field','guide','workshop','studio','review','camera-test','wide-camera'].includes(event.data.target)){
        if(route==='wide-camera' && event.data.target==='guide' && event.data.useCamera===true)cameraPreferred=true;
        navigate(event.data.target);
      }break;
      case 'ent207-studio-change':refreshCatalog();break;
      case 'ent207-community-nav': {
        if(!communityList())break;
        childPost=event.data.detail && typeof event.data.post==='string' ? event.data.post : null;
        if(route==='community')$('tabs').hidden=!!childPost;
        const target=history.state?.post || null;
        // A reloaded feed starts at its list. Restore the open detail once it is ready.
        if(restorePost && target && !childPost) {
          restorePost=false;childCommand('ent207-open-post',target);break;
        }
        restorePost=false;
        if(childPost && target!==childPost) {
          history.pushState({depth:(Number(history.state?.depth)||0)+1, post:childPost},'');
        } else if(!childPost && target) {
          history.back();
        }
        break;
      }
    }
  });
  addEventListener('storage',event=>{
    if(!event.key || [profilesKey,activeKey].includes(event.key) ||
      (['profile',home,'shelf'].includes(route) && [activityKey(),studioKey(owner()),'ent207.guide.v2:'+owner()].includes(event.key))) {
      rememberScroll();render();
    }
  });
  updateEdition();
  const reopenWelcome=mountWelcome({
    language:()=>lang,setLanguage:selectLanguage,
    finish:(granted,unavailable=false)=>{
      welcomeDone=true;cameraPreferred=granted;cameraUnavailable=unavailable;
      write(languageKey,lang);$('welcome').hidden=true;$('edition').hidden=false;
      const url=new URL(location.href);url.hash='';
      history.replaceState({depth:0},'',url);
      scrolls.delete(owner()+'/'+home);
      render();
      const heading=$('heading').querySelector('h1');
      if(heading){heading.tabIndex=-1;heading.focus({preventScroll:true});}
    }
  });
  addEventListener('pageshow',event=>{
    if(!event.persisted)return;
    welcomeDone=false;cameraPreferred=false;cameraUnavailable=false;
    frame=null;parkedGuide=null;route='';scroll=null;
    if($('setting').open)$('setting').close();
    for(const id of ['content','heading','actions','tabs'])$(id).replaceChildren();
    $('tabs').hidden=true;$('heading').hidden=true;$('edition').hidden=true;
    reopenWelcome();
  });
  fetch('community/catalog.json').then(r=>{if(!r.ok)throw Error(r.status);return r.json();}).then(data=>{
    if(!Array.isArray(data))return;
    baseCatalog=data.filter(p=>p && /^[a-zA-Z0-9_-]{1,90}$/.test(p.id));refreshCatalog();
    if($('recommended'))populateIdeas($('recommended'));
    if($('saved-projects'))renderSaved($('saved-projects'));
  }).catch(()=>{});
})();
