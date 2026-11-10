'use strict';
window.sceneSurface = 'rgba(0,0,0,0)';
configureOverview();
// The embedding page shows the poster until the first full-size model frame is drawn.
const previewFrame = window.frameElement;
if (previewFrame?.classList.contains('rack-view')) {
  const firstPaint = new ResizeObserver(([entry]) => {
    if (!entry.contentRect.width || !entry.contentRect.height) return;
    drawScene();
    previewFrame.style.backgroundImage = 'none';
    firstPaint.disconnect();
  });
  firstPaint.observe(cv);
}
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
let animation = 0;
let previousTime = 0;
function orbit(now) {
  yaw += Math.min(64, now - previousTime) * Math.PI * 2 / 22000;
  previousTime = now;
  paintScene();
  animation = requestAnimationFrame(orbit);
}
function syncOrbit() {
  const play = !pointer && !reducedMotion.matches && !document.hidden;
  if (play && !animation) {
    previousTime = performance.now();
    animation = requestAnimationFrame(orbit);
  } else if (!play && animation) {
    cancelAnimationFrame(animation);
    animation = 0;
  }
}
for (const event of ['pointerdown','pointerup','pointercancel','lostpointercapture']) {
  cv.addEventListener(event, syncOrbit);
}
document.addEventListener('visibilitychange', syncOrbit);
reducedMotion.addEventListener('change', syncOrbit);
syncOrbit();
