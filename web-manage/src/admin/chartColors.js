export const DEFAULT_CHART_COLORS = ['#8b5cf6', '#f97316', '#14b8a6', '#3b82f6', '#ec4899', '#eab308', '#06b6d4', '#ef4444', '#84cc16', '#6366f1', '#d946ef', '#10b981', '#f59e0b', '#64748b']

// Assign colors to API category keys, never to their current rank or translated label.
const categoryColors = {
  modes: { TIME_LIMIT: '#8b5cf6', BANKRUPTCY: '#f97316' },
  reasons: { ALL_AWAY: '#fb7185', LAST_SURVIVOR: '#3b82f6', TIME_UP: '#eab308', NO_PLAYERS: '#14b8a6', ALL_ELIMINATED: '#a855f7' },
  boards: { 'classic-30': '#06b6d4', 'classic-50': '#ec4899' },
  players: { '2人': '#3b82f6', '3人': '#f59e0b', '4人': '#10b981', '5人': '#ec4899', '6人': '#8b5cf6', '7人': '#06b6d4', '8人': '#f97316' },
  events: { CASH_REWARD: '#10b981', CASH_FINE: '#ef4444', CARD: '#a855f7', MOVE: '#3b82f6', JAIL: '#f97316', BUILD: '#06b6d4', DOWNGRADE: '#ec4899', TO_STATION: '#eab308', TO_START: '#84cc16' },
  cards: Object.fromEntries(['ROADBLOCK', 'RENT_WAIVER', 'BUILD', 'DOWNGRADE', 'FIXED_MOVE', 'JAIL_RELEASE', 'AUCTION', 'TRADE', 'REFUSE_PURCHASE', 'HOUSE_PROTECTION', 'QUERY', 'FORCED_PURCHASE', 'DEMOLISH', 'CLEAR_LAND'].map((key, index) => [key, DEFAULT_CHART_COLORS[index]])),
}

export function categoryColor(domain, key) {
  if (categoryColors[domain]?.[key]) return categoryColors[domain][key]
  // Future categories also keep their color when filters or the result order change.
  const hash = Array.from(`${domain}:${key}`).reduce((value, char) => (value * 31 + char.codePointAt(0)) >>> 0, 0)
  return DEFAULT_CHART_COLORS[hash % DEFAULT_CHART_COLORS.length]
}

export function distributionRows(domain, values, labels) {
  return Object.entries(values || {}).map(([key, value]) => ({ name: labels?.[key] || key, value, itemStyle: { color: categoryColor(domain, key) } })).sort((a, b) => b.value - a.value)
}
