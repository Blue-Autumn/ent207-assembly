// Paint the page as a single shape behind the controls so an interrupted tint always
// continues from the displayed colour. The hole uses the native preview's CSS bounds.
(() => {
 const phone=document.querySelector('.phone');
 const stage=document.getElementById('visual');
 const surface=document.getElementById('workSurface');
 const path=surface.querySelector('path');
 const motion=matchMedia('(prefers-reduced-motion: reduce)');
 let previous='', timer=0;

 function updateTint(){
  const tone=phone.matches('.problem,.ai-adjust,.local-adjust')?'adjust':
    phone.matches('.found,.ai-ok,.local-direction')?'ok':'neutral';
  const style=document.documentElement.dataset.feedbackStyle;
  const next=[tone,style,motion.matches,phone.classList.contains('work-page')].join('/');
  if(next===previous)return;
  previous=next;
  clearTimeout(timer);
  delete surface.dataset.settling;
  const pulse=phone.classList.contains('work-page') && tone!=='neutral' && style==='pulse' && !motion.matches;
  surface.toggleAttribute('data-peak',pulse);
  if(pulse){
   timer=setTimeout(()=>{
    surface.dataset.settling='';
    delete surface.dataset.peak;
   },1180);
  }
 }

 function resize(){
  if(!phone.classList.contains('work-page'))return;
  const bounds=phone.getBoundingClientRect(),rect=stage.getBoundingClientRect();
  // Overlap the rounded native clip by one CSS pixel so fractional device-pixel
  // rounding cannot expose the camera's unpainted perimeter.
  const seal=1;
  const left=rect.left-bounds.left+seal,top=rect.top-bounds.top+seal;
  const right=left+rect.width-2*seal,bottom=top+rect.height-2*seal;
  const radius=Math.min(16-seal,(right-left)/2,(bottom-top)/2);
  const frame='M0 0H'+bounds.width+'V'+bounds.height+'H0Z';
  const arc='a'+radius+' '+radius+' 0 0 1 ';
  const aperture=radius<=0?'':`M${left+radius} ${top}H${right-radius}${arc}${radius} ${radius}V${bottom-radius}${arc}${-radius} ${radius}H${left+radius}${arc}${-radius} ${-radius}V${top+radius}${arc}${radius} ${-radius}Z`;
  path.setAttribute('d',frame+aperture);
 }
 const size=new ResizeObserver(resize);
 for(const node of [phone,stage,document.querySelector('.top'),document.getElementById('work')])size.observe(node);
 let work=false;
 new MutationObserver(()=>{
  const active=phone.classList.contains('work-page');
  if(work!==active){work=active;resize()}
  updateTint();
 }).observe(phone,{attributes:true,attributeFilter:['class']});
 new MutationObserver(updateTint).observe(document.documentElement,{attributes:true,attributeFilter:['data-feedback-style']});
 motion.addEventListener('change',updateTint);
 updateTint();
})();
