/** 只翻译明确的原字段值，不从正文推导能力或数字。 */
export function spellRangeLabel(range?: string | null) {
  return ({ Self: '自身', Touch: '接触' } as Record<string, string>)[range ?? ''] || range || '未注明';
}

export function spellDurationLabel(duration?: string | null) {
  return ({
    instantaneous: '即时',
    'up to 1 hour': '最长1小时',
    '1 hour': '1小时',
    '10 minutes': '10分钟',
  } as Record<string, string>)[duration ?? ''] || duration || '未注明';
}
