import { computed, ref, watch } from 'vue'
import { adminIdentity } from './accessSession.js'
import { cleanPreferences, preferenceKey, visibleIds, recordVisit, moveFavorite } from './workbenchState.js'

const state = ref(cleanPreferences())
let key = null
watch(() => preferenceKey(adminIdentity.value), next => {
  key = next
  let stored
  try { stored = next ? JSON.parse(localStorage.getItem(next) || 'null') : null } catch { /* optional browser storage */ }
  state.value = cleanPreferences(stored)
}, { immediate: true })
function save(next) {
  state.value = cleanPreferences(next)
  try { if (key) localStorage.setItem(key, JSON.stringify(state.value)) } catch { /* in-memory preferences still work */ }
}
export function useWorkbench() {
  const favorites = computed(() => visibleIds(state.value.favorites, adminIdentity.value))
  const recent = computed(() => visibleIds(state.value.recent, adminIdentity.value))
  return {
    favorites, recent, layout: computed(() => state.value.layout),
    visit(id) { if (visibleIds([id], adminIdentity.value).length) save({ ...state.value, recent: recordVisit(state.value.recent, id) }) },
    toggle(id) {
      if (!visibleIds([id], adminIdentity.value).length) return
      const ids = favorites.value
      save({ ...state.value, favorites: ids.includes(id) ? ids.filter(item => item !== id) : [...ids, id].slice(-12) })
    },
    move(id, offset) { save({ ...state.value, favorites: moveFavorite(favorites.value, id, offset) }) },
    setLayout(layout) { save({ ...state.value, layout }) }
  }
}
