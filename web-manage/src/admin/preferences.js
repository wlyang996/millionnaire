import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
function preference(key, fallback) { try { return localStorage.getItem(key) || fallback } catch { return fallback } }
export const theme = ref(preference('millionnaire-admin-theme', 'light'))
export const motionLevel = ref(preference('millionnaire-admin-motion', 'rich'))
export const systemReducedMotion = ref(false)
export const motionEnabled = computed(() => motionLevel.value !== 'reduced' && !systemReducedMotion.value)
export const richMotion = computed(() => motionEnabled.value && motionLevel.value === 'rich')
export function useAdminPreferences() {
  let media
  const update = () => { systemReducedMotion.value = media.matches }
  onMounted(() => { media = matchMedia('(prefers-reduced-motion: reduce)'); update(); media.addEventListener('change', update) })
  onBeforeUnmount(() => media?.removeEventListener('change', update))
  const apply = () => {
    document.documentElement.dataset.adminTheme = theme.value === 'dark' ? 'dark' : 'light'
    document.documentElement.dataset.adminMotion = motionEnabled.value ? motionLevel.value : 'reduced'
    try { localStorage.setItem('millionnaire-admin-theme', theme.value); localStorage.setItem('millionnaire-admin-motion', motionLevel.value) } catch { /* storage is optional */ }
  }
  watch([theme, motionLevel, systemReducedMotion], apply, { immediate: true })
  return { theme, motionLevel, motionEnabled, richMotion }
}
