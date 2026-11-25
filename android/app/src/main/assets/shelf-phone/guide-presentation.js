'use strict';
// Camera frames carry spatial coordinates; a presentation decision keeps only the instruction.
// This lets the small CAD demonstration keep playing when one corner briefly leaves the frame.
function createGuidePresentation() {
 const cameraGraceMs=1800, instructionGraceMs=1450, neutralSettleMs=420;
 const correctionGraceMs=2400, clearCorrectionMs=480, sampleGapMs=650;
 const plainTone=new Set(['direction_ok','install_base_ready','install_row_ok','install_oriented']);
 const adjustTone=new Set(['rotate','flip','wrong_part','install_turn','install_stand','install_base_flip','install_wrong_row','install_wrong_part']);
 let seenAt, decision, fallback, fallbackAt, pending, pendingAt, latest;
 let correction,readyRun,manual;
 function reset() {
  seenAt=-Infinity;decision=null;fallback=null;pending=null;pendingAt=0;fallbackAt=-Infinity;latest=null;
  correction=null;readyRun=null;manual=false;
 }
 reset();
 function observe(event,at) {
  if(['permission','unavailable'].includes(event.state)){
   const needsCheck=manual||!!correction;reset();manual=needsCheck;latest=event;return;
  }
  if(event.visible)seenAt=at;
  // Coordinates and current-frame approval are never retained as part of a held decision.
  const {state,title,message,part,angle,stable,installMotion,targetLabel}=event;
  const copy={state,title,message,titleEn:event.titleEn,messageEn:event.messageEn,part,angle,stable,installMotion,targetLabel,requiresPlacement:!!event.placement};
  latest=copy;
  const adjust=adjustTone.has(state)||(state==='install_notch_hint'&&installMotion==='turn_panel_upright');
  const tone=adjust?'adjust':plainTone.has(state)?'direction':'';
  if(event.visible && stable && adjust){
   correction={cue:copy,tone:'adjust',at};manual=true;readyRun=null;
   decision=correction;fallback=null;pending=null;return;
  }
  const preferredHeading=state==='install_base_heading';
  if(event.visible && stable && (plainTone.has(state)||preferredHeading)){
   if(readyRun?.state!==state || at-readyRun.last>sampleGapMs)readyRun={state,start:at,last:at};
   readyRun.last=at;
   // One positive frame amid a correction cannot arm the button or replace its instruction.
   if((correction||manual&&!preferredHeading) && at-readyRun.start<clearCorrectionMs)return;
   correction=null;manual=preferredHeading;
  }else if(readyRun && at-readyRun.last>sampleGapMs){readyRun=null}
  if(event.visible && stable && (tone || preferredHeading)){
   decision={cue:copy,tone,at};fallback=null;pending=null;return;
  }
  if(!pending || pending.state!==state || pending.title!==title || pending.message!==message){
   pending=copy;pendingAt=at;
  } else if(at-pendingAt>=neutralSettleMs){fallback=copy;fallbackAt=at}
 }
 function read(now,install) {
  const blocked=['permission','unavailable'].includes(latest?.state);
  const live=!blocked&&(install||now-seenAt<=cameraGraceMs);
  const locked=!!correction && now-correction.at<=correctionGraceMs;
  const held=locked?correction:live&&decision&&now-decision.at<=instructionGraceMs?decision:null;
  const calm=pending && now-pendingAt>=neutralSettleMs?pending:now-fallbackAt<instructionGraceMs?fallback:null;
  const instruction=blocked?latest:held?.cue||calm;
  return {live,tone:held?.tone||'',instruction,locked,manual:manual&&!locked,
   ready:!manual&&held?.tone==='direction',readySince:readyRun?.start??null};
 }
 function nextChange(now,install) {
  const times=[pending?pendingAt+neutralSettleMs:0,decision?decision.at+instructionGraceMs+1:0,
   correction?correction.at+correctionGraceMs+1:0,fallbackAt+instructionGraceMs,install?0:seenAt+cameraGraceMs+1];
  return Math.min(...times.filter(at=>at>now));
 }
 function viewportChanged(){
  const prior=correction,wasManual=manual;
  reset();correction=prior;manual=wasManual;
 }
 function suspend(){
  const needsCheck=manual;
  reset();manual=needsCheck;
 }
 function photoCorrection(){readyRun=null;manual=true}
 return {observe,read,nextChange,reset,viewportChanged,suspend,photoCorrection};
}
