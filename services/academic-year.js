export const dailyLimit = 300;
export const limitedFrom = Date.parse('2027-01-16T00:00:00+08:00');
export const closesAt = Date.parse('2027-07-01T00:00:00+08:00');

export function aiPhase(now = Date.now()) {
  if (now >= closesAt) return 'closed';
  return now >= limitedFrom ? 'daily' : 'open';
}

export function aiNotice(tr, phase = aiPhase()) {
  if (phase === 'closed') return tr('Online AI for this academic year has ended. Local features are still available.',
    '本学年的在线 AI 服务已结束，仍可使用本机功能。');
  if (phase === 'daily') return tr('AI replies may be delayed. The site shares 300 requests a day through June 30. The allowance resets at midnight Beijing time.',
    'AI 回复可能延迟。至 6 月 30 日，全站共用每天 300 次额度，北京时间零点恢复。');
  return tr('AI replies may be delayed. From January 16, 2027, the site shares 300 requests a day.',
    'AI 回复可能延迟。2027 年 1 月 16 日起，全站共用每天 300 次额度。');
}

export const dailyLimitMessage = [
  'All 300 AI requests for today have been used. Try again after midnight Beijing time.',
  '今天全站的 300 次 AI 请求已用完，请在北京时间零点后再试。'
];
