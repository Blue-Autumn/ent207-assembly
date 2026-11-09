export function mountWelcome({language, setLanguage, opensGuide, finish}) {
  const root = document.getElementById('welcome');
  const get = id => document.getElementById('welcome-' + id);
  const next = get('next'), skip = get('skip'), back = get('back');
  const title = get('title'), description = get('description'), status = get('status');
  let step = 1, ticket = 0, waiting = false, issue = '';
  const tr = (en, zh) => language() === 'zh' ? zh : en;
  const issues = {
    NotAllowedError: ['Camera access is off. Allow it in your browser or phone settings, then retry. You can also use sample photos.', '相机权限未开启。可以在浏览器或手机设置里允许相机后重试，也可以先用样片。'],
    NotFoundError: ['No camera was found. Continue with sample photos, or connect a camera and retry.', '未找到摄像头。可以先用样片，也可以连接摄像头后重试。'],
    NotReadableError: ['The camera is busy. Close the other camera app and retry, or continue with samples.', '相机正被占用。请关闭正在使用相机的应用后重试，或先用样片。'],
    unavailable: ['This browser could not open the camera. Try your phone’s system browser or continue with samples.', '当前浏览器未能打开相机。可以用手机系统浏览器打开此链接，或先用样片。']
  };

  function draw(focus = false) {
    root.dataset.step = String(step);
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
    if (step === 1) {
      title.textContent = 'Choose your language';
      description.textContent = '选择语言';
    } else if (step === 2) {
      title.textContent = tr('The web edition','这是网页体验版');
      description.textContent = tr('Try the whole shelf assembly here with a model, sample photos or your own parts. The full app, including AR tracking, is available on Android.',
        '在这里可以用模型、样片或手里的板件走完整个装配流程。完整功能和 AR 跟踪在 Android 应用中。');
    } else {
      title.textContent = tr('Use your camera?','要用相机找零件吗？');
      description.textContent = tr('Point the rear camera at the square tags to find each part. Live video is checked on this device. You can turn the camera on later in Guided assembly.',
        '把后置镜头对准板件上的方形标签，就能找到当前零件。实时画面在本机识别，稍后也可以在「装配指导」里开启相机。');
    }
    status.hidden = !issue;
    status.textContent = issue ? tr(...(issues[issue] || issues.unavailable)) : '';
    next.disabled = waiting;
    next.querySelector('span').textContent = step === 1 ? tr('Continue / 继续','继续 / Continue') :
      step === 2 ? tr('Continue','继续') : waiting ? tr('Waiting for camera…','等待相机权限…') :
      issue ? tr('Try camera again','重新开启相机') : opensGuide() ?
        tr('Allow camera & start guide','允许相机，开始装配指导') : tr('Allow camera & continue','允许相机并继续');
    skip.hidden = step !== 3;
    skip.textContent = tr('Continue without camera','暂不开启，继续体验');
    if (focus) { root.scrollTop = 0; title.focus({preventScroll:true}); }
  }

  function cancelRequest() { ticket++; waiting = false; issue = ''; }
  for (const choice of get('languages').children) {
    choice.onclick = () => { setLanguage(choice.dataset.language); draw(); };
  }
  back.onclick = () => { cancelRequest(); if (step > 1) step--; draw(true); };
  skip.onclick = () => { cancelRequest(); finish(null); };
  next.onclick = async () => {
    if (step < 3) { step++; draw(true); return; }
    if (waiting) return;
    const attempt = ++ticket;
    waiting = true; issue = ''; draw();
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw Error('unavailable');
      const stream = await navigator.mediaDevices.getUserMedia({
        audio:false, video:{facingMode:{ideal:'environment'}, width:{ideal:1280}, height:{ideal:720}}
      });
      if (attempt !== ticket || root.hidden || document.hidden) {
        stream.getTracks().forEach(track => track.stop());
        if (attempt === ticket) { cancelRequest(); draw(); }
        return;
      }
      finish(stream);
    } catch (err) {
      if (attempt !== ticket) return;
      waiting = false;
      issue = Object.hasOwn(issues,err.name) ? err.name : 'unavailable';
      draw();
    }
  };
  window.addEventListener('pagehide', () => { cancelRequest(); if (!root.hidden) draw(); });
  draw();
  return () => { cancelRequest(); step = 1; root.hidden = false; draw(true); };
}
