import { categoryColor } from './chartColors.js'

export function probabilityPool(id, title, entries, expectedTotal) {
  const invalid = entries.some(row => !Number.isInteger(row.weight) || row.weight < 0 || row.weight > (expectedTotal ?? 100))
  const total = entries.reduce((sum, row) => sum + (Number.isFinite(row.weight) && row.weight > 0 ? row.weight : 0), 0)
  const valid = !invalid && total > 0 && (expectedTotal == null || total === expectedTotal)
  const rows = entries.map(row => ({ ...row, percent: total > 0 && row.weight > 0 ? row.weight * 100 / total : 0, color: categoryColor(id === 'cards' ? 'cards' : 'events', row.key) }))
  return { id, title, total, valid, expectedTotal, rows, message: invalid ? '存在无效权重，请修改后校验' : total === 0 ? '权重全为 0，无法抽取，请修改后校验' : expectedTotal != null && total !== expectedTotal ? `合计须为 ${expectedTotal}，当前为 ${total}` : '按当前权重计算相对占比' }
}

export function probabilityPools(settings, labels = {}) {
  const weights = (values, names) => Object.entries(values || {}).map(([key, weight]) => ({ key, name: names?.[key] || key, weight: weight == null ? NaN : Number(weight) }))
  const fixed = unlucky => (settings?.lucky || []).filter(row => !!row.unlucky === unlucky).map(row => ({ key: row.kind, name: row.label, weight: row.weight == null ? NaN : Number(row.weight), amount: row.amount }))
  return [probabilityPool('events', '事件格结果', weights(settings?.eventWeights, labels.events), 100), probabilityPool('cards', '道具抽取', weights(settings?.cardWeights, labels.cards), 1000), probabilityPool('lucky', '幸运格奖池', fixed(false)), probabilityPool('unlucky', '不幸格奖池', fixed(true))]
}

export function rewardAmount(config, income) {
  if (!config?.enabled || !Number.isFinite(income) || income < 0 || !Number.isFinite(config.rewardPercent) || config.rewardPercent < 0 || !Number.isFinite(config.perPlayerCap) || config.perPlayerCap < 0 || !(config.roundingUnit > 0)) return 0
  return Math.floor(Math.min(Math.floor(income * config.rewardPercent / 100), config.perPlayerCap) / config.roundingUnit) * config.roundingUnit
}

export function cityScenario(config, event) {
  const active = (config?.pool || []).filter(row => row.enabled && row.weight > 0)
  const total = active.reduce((sum, row) => sum + row.weight, 0)
  const available = !!config?.enabled && !!config.allowedModes?.length && config.triggerPercent > 0 && active.some(row => row.kind === event?.kind)
  const start = Number(config?.firstCheckRound) + Number(config?.advanceRounds)
  return { available, total, share: total && event?.enabled && event.weight > 0 ? event.weight * 100 / total : 0, check: config?.firstCheckRound, start, last: start + Number(event?.durationRounds) - 1, end: start + Number(event?.durationRounds) }
}

export function cashRange(min, max, step, limit = 24) {
  if (![min, max, step].every(Number.isFinite) || min <= 0 || max < min || step <= 0 || (max - min) % step !== 0) return { valid: false, values: [], count: 0 }
  const count = (max - min) / step + 1
  return { valid: true, count, values: Array.from({ length: Math.min(count, limit) }, (_, index) => min + index * step) }
}
