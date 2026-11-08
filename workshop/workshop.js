'use strict';
(() => {
  const el = id => document.getElementById(id);
  const query = new URLSearchParams(location.search);
  const chinese = (query.get('lang') || 'en').toLowerCase().startsWith('zh');
  const tr = (en, zh) => chinese ? zh : en;
  const reduce = query.get('motion') === 'reduce' || matchMedia('(prefers-reduced-motion: reduce)').matches;
  const byId = new Map(WORKFLOW.tasks.map(task => [task.id, task]));
  const route = [...WORKFLOW.preparations, ...WORKFLOW.main].map(id => byId.get(id));
  const groups = WORKFLOW.preparations.map(id => byId.get(id));
  const parts = groups.flatMap(task => task.context);
  const names = {
    '左侧板': tr('Left frame', '左侧框'), '右侧板': tr('Right frame', '右侧框'), '背板': tr('Back panel', '背板'),
    '层1板': tr('Purple shelf', '紫色层板'), '层2板': tr('Blue shelf', '蓝色层板'),
    '层3板': tr('Peach shelf', '肉色层板'), '层4板': tr('Yellow shelf', '黄色层板'), '层5板': tr('Green shelf', '绿色层板')
  };
  const groupOf = id => id.replace(/-[12]$/, '');
  const pieceName = id => names[groupOf(id)] + tr(' · half ', ' · 半件 ') + id.slice(-1);
  const phaseName = phase => phase === 'prepare' ? tr('Join the halves', '拼好组件') : tr('Assemble the rack', '总装');
  const list = el('list');
  let tab = 'steps', step = -1, part = parts[0];
  let activeScroll = 0;

  document.documentElement.dataset.motion=reduce?'reduce':'auto';
  document.documentElement.lang = chinese ? 'zh-CN' : 'en';
  document.title = el('page-title').textContent = tr('Virtual assembly', '虚拟试拼');
  el('back').ariaLabel = tr('Back to project', '返回作品');
  el('cv').setAttribute('role','img');
  el('previous').ariaLabel = tr('Previous', '上一个');
  el('steps').textContent = tr('Assembly steps', '安装步骤');
  el('parts').textContent = tr('Parts', '单看零件');
  window.sceneSurface = 'rgba(0,0,0,0)';
  window.uiTable = false;

  const stage=createWorkshopStage(reduce);
  function showScene() {
    if (tab === 'parts') {
      const entry=partMap.get(part);
      stage.select({id:part,kind:'part',context:[part],members:[],motion:{axis:'Y',dist:0},
        workPose:{normalAxis:entry.axis,upSign:1}});
    } else {
      stage.select(route[step],step<0);
    }
  }
  function element(type, className, label) {
    const node = document.createElement(type); node.className = className;
    if (label !== undefined) node.textContent = label;
    return node;
  }
  function section(title) {
    list.append(element('h3','group-title',title));
    const node = element('div','group'); list.append(node); return node;
  }
  function displayName(task) {
    if (task.id === WORKFLOW.main[0]) return tr('Lay down the base frame','平放基准侧框');
    if (task.kind === 'prepare') return names[task.group];
    return tr('Fit the ','装入') + (chinese ? names[task.group] : names[task.group].toLowerCase());
  }
  function buildList() {
    list.replaceChildren();
    if (tab === 'parts') {
      const group = section(tr('Select a half to inspect', '选择要查看的半件'));
      for (const task of groups) {
        const row = element('div','row');
        row.append(element('span','part-name',names[task.group]));
        for (const id of task.context) {
          const button = element('button','chip',id.slice(-1)); button.dataset.part = id;
          button.ariaLabel = pieceName(id); row.append(button);
        }
        group.append(row);
      }
    } else {
      let group, phase = '';
      route.forEach((task, index) => {
        if (task.kind !== phase) { phase = task.kind; group = section(phaseName(phase)); }
        const row = element('button','row'); row.dataset.step = String(index);
        row.append(element('span','num',String(index + 1).padStart(2,'0')), element('span','',displayName(task)), element('span','arrow'));
        group.append(row);
      });
    }
  }
  function update(scrollToSelection) {
    const isPart = tab === 'parts', task = route[step];
    el('modes').classList.toggle('parts-selected',isPart);
    el('steps').setAttribute('aria-pressed',String(!isPart)); el('parts').setAttribute('aria-pressed',String(isPart));
    const isExploded = !isPart && step < 0;
    el('rack-view').dataset.action = isExploded ? 'assemble' : 'explode';
    el('rack-view').setAttribute('aria-label', isExploded ? tr('Assemble rack','合拢整架') : tr('Explode rack','展开整架'));
    const partIndex = parts.indexOf(part);
    el('kicker').textContent = isPart ? tr('Part ', '零件 ') + (partIndex+1) + ' / ' + parts.length :
      task ? phaseName(task.kind) + ' · ' + (step+1) + ' / ' + route.length : tr('Five-tier shelf', '五层隔板架');
    el('subject').textContent = isPart ? pieceName(part) : task ? displayName(task) :
      step < 0 ? tr('Exploded view', '分解视图') : tr('Finished rack', '完成整架');
    el('cv').ariaLabel = el('subject').textContent + tr('. Drag to rotate.', '，拖动旋转。');
    el('previous').disabled = isPart ? partIndex === 0 : step < 0;
    el('next').textContent = isPart ? (partIndex + 1 < parts.length ? tr('Next part','下一个零件') : tr('Back to steps','返回步骤')) :
      step < 0 ? tr('Start rehearsal', '开始试拼') : step === route.length ? tr('Start again','重新试拼') :
      step === route.length - 1 ? tr('View finished rack','看完整架子') : tr('Next step','下一步');
    let selected;
    for (const node of list.querySelectorAll('[data-step]')) {
      const active = Number(node.dataset.step) === step;
      if (active) { node.setAttribute('aria-current','step'); selected = node; }
      else node.removeAttribute('aria-current');
      node.lastElementChild.hidden = !active;
    }
    for (const node of list.querySelectorAll('[data-part]')) {
      node.setAttribute('aria-pressed',String(node.dataset.part === part));
      if (node.dataset.part === part) selected = node;
    }
    if (selected && scrollToSelection) selected.scrollIntoView({block:'nearest'});
    showScene();
  }
  function changeTab(nextTab) {
    if (tab === nextTab) return;
    if (nextTab === 'parts') {
      activeScroll = list.scrollTop;
      part = route[step]?.context.find(id => groupOf(id) === route[step].group) || part;
    }
    tab = nextTab; buildList(); update(tab === 'parts');
    if (tab === 'steps') list.scrollTop = activeScroll;
  }
  list.onclick = event => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.part){
      if(part===button.dataset.part)return;
      part=button.dataset.part;
    } else {
      const choice=Number(button.dataset.step);
      if(step===choice)return;
      step=choice;
    }
    update(false);
  };
  const modes=el('modes');
  let swipe=null,ignoreClick=false;
  modes.addEventListener('pointerdown',e=>{
    if(!e.isPrimary||e.button!==0)return;
    ignoreClick=false;swipe={id:e.pointerId,x:e.clientX,y:e.clientY};
  });
  modes.addEventListener('pointermove',e=>{
    if(swipe?.id!==e.pointerId)return;
    const dx=e.clientX-swipe.x,dy=e.clientY-swipe.y;
    if(Math.abs(dy)>15 && Math.abs(dy)>Math.abs(dx)){swipe=null;return;}
    if(Math.abs(dx)>10){
      modes.setPointerCapture(e.pointerId);ignoreClick=true;
      const fraction=Math.min(1,Math.max(0,(tab==='parts'?1:0)+dx/(modes.clientWidth/2)));
      modes.style.setProperty('--segment',fraction*100+'%');
    }
  });
  function finishSwipe(e){
    if(swipe?.id!==e.pointerId)return;
    const dx=e.clientX-swipe.x;swipe=null;
    if(e.type!=='pointercancel' && Math.abs(dx)>30)changeTab(dx>0?'parts':'steps');
    modes.style.removeProperty('--segment');
  }
  modes.addEventListener('pointerup',finishSwipe);modes.addEventListener('pointercancel',finishSwipe);
  modes.addEventListener('click',e=>{if(ignoreClick && e.detail){e.stopPropagation();ignoreClick=false;}},true);
  el('steps').onclick = () => changeTab('steps'); el('parts').onclick = () => changeTab('parts');
  el('previous').onclick = () => {
    if (tab === 'parts') part = parts[Math.max(0,parts.indexOf(part) - 1)];
    else step = Math.max(-1,step - 1);
    update(true);
  };
  el('next').onclick = () => {
    if (tab === 'parts') {
      if (part === parts.at(-1)) { changeTab('steps'); return; }
      part = parts[parts.indexOf(part) + 1];
    } else step = step === route.length ? 0 : step + 1;
    update(true);
  };
  el('rack-view').onclick = () => {
    const expanded=tab==='steps' && step<0;
    if(tab==='parts'){tab='steps';buildList()}
    step=expanded?route.length:-1;
    list.scrollTop=0;
    update(false);
  };
  el('back').onclick = event => {
    if (window.parent === window) return;
    event.preventDefault();
    window.parent.postMessage({type:'ent207-close'},location.origin);
  };
  document.body.hidden = false; buildList(); update(false);
})();
