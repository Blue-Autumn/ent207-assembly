// One transition owner per result surface. Repeated detections, DOM renders and
// language changes do not restart the pulse. CSS interpolates from the visible wash.
(() => {
  'use strict';
  const reduce=matchMedia('(prefers-reduced-motion: reduce)');
  const ns='http://www.w3.org/2000/svg';
  const strokes={ok:'m5 12 4.5 4.5L19 7',adjust:'m7 7 10 10 M17 7 7 17',check:'M8 8a4 4 0 0 1 8 0c0 3-4 3-4 6 M12 17h.01'};
  const positive=new Set(['direction_ok','install_base_ready','install_oriented','install_row_ok']);
  const correction=new Set(['flip','rotate','wrong_part','install_base_flip','install_turn','install_stand','install_wrong_row','install_wrong_part']);
  function liveTone(check){
    if(!check?.stable)return 'neutral';
    if(check.placement)return 'adjust';
    if(positive.has(check.status))return 'ok';
    if(correction.has(check.status)||check.installMotion)return 'adjust';
    return 'neutral';
  }
  function mark(label,tone){
    if(!label)return;
    let icon=label.querySelector('.feedback-icon');
    if(!strokes[tone]){icon?.remove();delete label.dataset.mark;return;}
    if(icon&&label.dataset.mark===tone)return;
    if(!icon){
      icon=document.createElementNS(ns,'svg');icon.setAttribute('class','feedback-icon');icon.setAttribute('viewBox','0 0 24 24');
      icon.setAttribute('aria-hidden','true');icon.setAttribute('focusable','false');
      icon.append(document.createElementNS(ns,'path'));label.append(icon);
    }
    label.dataset.mark=tone;icon.firstChild.setAttribute('d',strokes[tone]);
  }
  function attach(root){
    root.classList.add('feedback-surface');
    const wash=document.createElement('div');wash.className='feedback-wash';wash.setAttribute('aria-hidden','true');
    for(const tone of ['ok','adjust']){const layer=document.createElement('i');layer.className='wash-'+tone;wash.append(layer);}
    let scope='',outcome='',lastCue='',leftAt=-Infinity,timer=0,receiptTimer=0,receiptNode=null;
    function dismissReceipt(){clearTimeout(receiptTimer);if(receiptNode)receiptNode.hidden=true;receiptNode=null;}
    function acknowledge(node,text){
      dismissReceipt();receiptNode=node;node.textContent=text;mark(node,'ok');node.hidden=false;
      receiptTimer=setTimeout(dismissReceipt,1450);
    }
    function show(nextScope,tone='neutral',reason=tone){
      if(wash.parentNode!==root)root.prepend(wash);
      const visual=tone==='ok'||tone==='adjust'?tone:'neutral';
      const key=nextScope+'|'+visual+'|'+reason;
      if(key===outcome)return;
      const now=performance.now(),sameScope=scope===nextScope;
      if(!sameScope||visual!=='neutral')dismissReceipt();
      if(root.dataset.feedbackTone!=='neutral')leftAt=now;
      const repeat=sameScope&&key===lastCue&&now-leftAt<650;
      clearTimeout(timer);scope=nextScope;outcome=key;
      root.dataset.feedbackTone=visual;
      const pulse=visual!=='neutral'&&!repeat&&!reduce.matches&&!document.hidden;
      root.dataset.feedbackPhase=pulse?'peak':'rest';
      if(visual!=='neutral')lastCue=key;
      if(pulse)timer=setTimeout(()=>{root.dataset.feedbackPhase='rest';},760);
    }
    function clear(){clearTimeout(timer);dismissReceipt();scope=outcome=lastCue='';leftAt=-Infinity;root.dataset.feedbackTone='neutral';root.dataset.feedbackPhase='rest';}
    function steady(){clearTimeout(timer);dismissReceipt();root.dataset.feedbackPhase='rest';}
    document.addEventListener('visibilitychange',()=>{if(document.hidden)steady();});
    addEventListener('pagehide',steady);
    reduce.addEventListener('change',steady);
    return {show,clear,acknowledge};
  }
  // Only transitions between distinct screens or steps animate. Cancel from the
  // current opacity when navigation changes mid-transition. Camera frames never enter here.
  function navigation(element){
    let current=null,motion=null,waiting=null;
    return (key,frame)=>{
      if(key===current)return;
      const first=current===null;current=key;
      const from=motion?Number(getComputedStyle(element).opacity):.72;
      waiting?.();waiting=null;
      motion?.cancel();motion=null;
      if(first||reduce.matches||document.hidden||!element.animate)return;
      function reveal(){
        waiting=null;if(current!==key||document.hidden||reduce.matches)return;
        const active=element.animate([{opacity:Math.max(.45,Math.min(.98,from))},{opacity:1}],
          {duration:220,easing:'cubic-bezier(.2,.65,.3,1)'});
        motion=active;active.onfinish=()=>{if(motion===active)motion=null;};
      }
      if(frame){frame.addEventListener('load',reveal,{once:true});waiting=()=>frame.removeEventListener('load',reveal);}
      else reveal();
    };
  }
  window.ShelfFeedback=Object.freeze({attach,mark,liveTone,navigation});
})();
