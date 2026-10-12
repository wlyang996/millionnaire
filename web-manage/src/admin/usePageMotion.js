import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { motionEnabled, richMotion } from './preferences.js'

// Animate existing DOM so page changes keep form instances, focus and drafts intact.
export function usePageMotion(root, activePage) {
  const running = new Set()
  let observer, frame, seen = new WeakSet()
  const cancel = () => { running.forEach(animation => animation.cancel()); running.clear(); cancelAnimationFrame(frame) }
  function animate(element, keyframes, options) {
    if (!element?.animate || !motionEnabled.value || document.hidden) return
    const animation = element.animate(keyframes, options)
    running.add(animation)
    animation.finished.catch(() => {}).finally(() => running.delete(animation))
  }
  function enterCards() {
    if (!root.value || !motionEnabled.value || document.hidden) return
    const cards = [...root.value.querySelectorAll('.surface, .spotlight-card, .visual-card, .title-badge')].filter(element => element.getClientRects().length && !seen.has(element))
    cards.forEach((card, index) => {
      seen.add(card)
      animate(card, [{ opacity: 0, transform: 'translateY(14px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: richMotion.value ? 360 : 200, delay: richMotion.value ? Math.min(index * 35, 210) : 0, easing: 'cubic-bezier(.2,.8,.2,1)' })
    })
  }
  function schedule() { cancelAnimationFrame(frame); frame = requestAnimationFrame(enterCards) }
  async function enterPage() {
    cancel(); seen = new WeakSet(); await nextTick()
    animate(root.value, [{ opacity: .4, transform: 'translateX(12px)' }, { opacity: 1, transform: 'translateX(0)' }], { duration: richMotion.value ? 280 : 180, easing: 'ease-out' })
    schedule()
  }
  const visibility = () => { if (document.hidden) cancel() }
  watch(activePage, enterPage, { flush: 'post' })
  watch([motionEnabled, richMotion], () => { cancel(); if (motionEnabled.value) schedule() })
  onMounted(() => { observer = new MutationObserver(schedule); observer.observe(root.value, { childList: true, subtree: true }); document.addEventListener('visibilitychange', visibility); enterPage() })
  onBeforeUnmount(() => { cancel(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibility) })
}
