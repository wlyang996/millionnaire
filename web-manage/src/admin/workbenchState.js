import { pages, sectionForField } from './navigation.js'
import { pageVisible } from './accessPolicy.js'
import { fieldLabels } from './fields.js'

const known = new Set(pages.map(page => page.id))
export const defaultFavorites = ['events', 'characters', 'audio', 'history']
export function preferenceKey(identity) {
  return identity?.user?.id == null ? null : `millionnaire-admin-workbench-v1:${identity.user.id}`
}
export function cleanPreferences(value) {
  const ids = list => [...new Set(Array.isArray(list) ? list.filter(id => known.has(id)) : [])].slice(0, 12)
  return { favorites: ids(value?.favorites ?? defaultFavorites), recent: ids(value?.recent).slice(0, 8), layout: value?.layout === 'focus' ? 'focus' : 'panorama' }
}
export function visibleIds(ids, identity) { return ids.filter(id => pageVisible(identity, id)) }
export function recordVisit(recent, id) { return known.has(id) ? [id, ...recent.filter(item => item !== id)].slice(0, 8) : recent }
export function moveFavorite(favorites, id, offset) {
  const next = [...favorites], from = next.indexOf(id), to = from + offset
  if (from < 0 || to < 0 || to >= next.length) return next
  next.splice(from, 1); next.splice(to, 0, id)
  return next
}
const terms = text => String(text || '').toLocaleLowerCase().trim().split(/\s+/).filter(Boolean)
export function commandResults(identity, query, searchIndex = {}, favorites = [], recent = [], actions = []) {
  if (!identity || identity.mustChangePassword) return []
  const words = terms(query)
  const available = pages.filter(page => pageVisible(identity, page.id))
  const items = available.map(page => ({ id: `page:${page.id}`, page: page.id, label: page.label, detail: page.group, kind: '页面', search: `${page.id} ${page.title} ${page.description} ${searchIndex[page.id] || ''}` }))
  if (words.length) for (const [path, label] of Object.entries(fieldLabels)) {
    const page = available.find(page => page.id === sectionForField(path))
    if (!page || /^(label|name|默认|最小值|最大值|步长|显示名称|启用)$/.test(label)) continue
    items.push({ id: `field:${path}`, page: page.id, path, label, detail: `${page.label} · ${path}`, kind: '参数', search: path })
  }
  items.push(...actions.map(action => ({ ...action, kind: '快捷操作', search: '' })))
  const rank = item => words.length ? (terms(item.label).join(' ').includes(words.join(' ')) ? 0 : item.kind === '参数' ? 1 : 2) : favorites.includes(item.page) ? -100 + favorites.indexOf(item.page) : recent.includes(item.page) ? -50 + recent.indexOf(item.page) : item.page === 'workbench' ? 0 : 1
  return items.filter(item => words.every(word => `${item.label} ${item.detail} ${item.search}`.toLocaleLowerCase().includes(word)))
    .sort((a, b) => rank(a) - rank(b)).slice(0, 60)
}
export function highlightParts(label, query) {
  const words = terms(query), text = String(label), lower = text.toLocaleLowerCase()
  const matches = Array.from(text, (_, index) => words.some(word => lower.startsWith(word, index)) ? words.filter(word => lower.startsWith(word, index)).reduce((end, word) => Math.max(end, index + word.length), index) : 0)
  const parts = []; let end = 0
  for (let i = 0; i < text.length; i++) {
    end = Math.max(end, matches[i] || 0)
    const marked = i < end, last = parts.at(-1)
    if (last && last.marked === marked) last.text += text[i]
    else parts.push({ text: text[i], marked })
  }
  return parts
}
