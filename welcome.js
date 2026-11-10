export function mountWelcome({language, setLanguage, finish}) {
  const root = document.getElementById('welcome');
  const get = id => document.getElementById('welcome-' + id);
  const next = get('next'), skip = get('skip'), back = get('back');
  const title = get('title'), description = get('description'), android = get('android');
  const feedback=ShelfFeedback.attach(root),arrive=ShelfFeedback.navigation(root.querySelector('.welcome-card'));
  let step = 1, ticket = 0, waiting = false;
  const tr = (en, zh) => language() === 'zh' ? zh : en;

  function draw(focus = false) {
    root.dataset.step = String(step);
    arrive(step);feedback.show('welcome/'+step,'neutral');
    get('count').textContent = step + ' / 3';
    get('count').setAttribute('aria-label',tr('Step ' + step + ' of 3','第 ' + step + ' 页，共 3 页'));
    back.hidden = step === 1;
    back.setAttribute('aria-label',tr('Previous page','上一页'));
    get('languages').hidden = step !== 1;
    for (const choice of get('languages').children) {
      choice.setAttribute('aria-pressed', String(choice.dataset.language === language()));
    }
    title.lang = step === 1 ? 'en' : language();
    description.lang = step === 1 ? 'zh-CN' : language();
    android.hidden = step !== 2;
    if (step === 1) {
      title.textContent = 'Choose your language';
      description.textContent = '选择语言';
    } else if (step === 2) {
      title.textContent = tr('The web edition','这是网页体验版');
      description.textContent = tr('Try the whole shelf assembly here with a model, sample photos or your own parts.',
        '在这里可以用模型、样片或手里的板件走完整个装配流程。');
      android.textContent = tr('The full app, including AR tracking, is available on Android.', '完整功能和 AR 跟踪在 Android 应用中。');
    } else {
      title.textContent = tr('Use your camera?','要用相机找零件吗？');
      description.textContent = tr('Point the rear camera at the square tags to find each part. Live video is checked on this device. You can turn the camera on later in Guided assembly.',
        '把后置镜头对准板件上的方形标签，就能找到当前零件。实时画面在本机识别，稍后也可以在「装配指导」里开启相机。');
    }
    next.disabled = waiting;
    next.querySelector('span').textContent = step === 1 ? tr('Continue / 继续','继续 / Continue') :
      step === 2 ? tr('Continue','继续') : waiting ? tr('Waiting for camera…','等待相机权限…') :
      tr('Allow camera & go to home','允许相机，进入主页');
    skip.hidden = step !== 3;
    skip.textContent = tr('Go to home without camera','暂不开启，进入主页');
    if (focus) { root.scrollTop = 0; title.focus({preventScroll:true}); }
  }

  function cancelRequest() { ticket++; waiting = false; }
  for (const choice of get('languages').children) {
    choice.onclick = () => { setLanguage(choice.dataset.language); draw(); };
  }
  back.onclick = () => { cancelRequest(); if (step > 1) step--; draw(true); };
  skip.onclick = () => { cancelRequest(); finish(false); };
  next.onclick = async () => {
    if (step < 3) { step++; draw(true); return; }
    if (waiting) return;
    const attempt = ++ticket;
    waiting = true; draw();
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw Error('unavailable');
      const stream = await ShelfCamera.openPreferred(()=>attempt===ticket && !root.hidden);
      stream.getTracks().forEach(track => track.stop());
      if (attempt === ticket && !root.hidden) finish(true);
    } catch {
      if (attempt === ticket && !root.hidden) finish(false, true);
    }
  };
  window.addEventListener('pagehide', () => { cancelRequest(); if (!root.hidden) draw(); });
  draw();
  return () => { cancelRequest(); step = 1; root.hidden = false; draw(true); };
}
