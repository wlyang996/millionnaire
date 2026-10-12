<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { motionEnabled } from '../../admin/preferences.js'
const props = defineProps({ value: Number, decimals: { type: Number, default: 0 } })
const shown = ref(0)
let frame = 0
watch([() => props.value, motionEnabled], ([value, enabled]) => {
  cancelAnimationFrame(frame)
  if (!Number.isFinite(value)) return
  if (!enabled) { shown.value = value; return }
  const startValue = shown.value, start = performance.now()
  const tick = time => { const t = Math.min(1, (time - start) / 650); shown.value = startValue + (value - startValue) * (1 - (1 - t) ** 3); if (t < 1) frame = requestAnimationFrame(tick) }
  frame = requestAnimationFrame(tick)
}, { immediate: true })
const text = computed(() => !Number.isFinite(props.value) ? '—' : shown.value.toLocaleString('zh-CN', { minimumFractionDigits: props.decimals, maximumFractionDigits: props.decimals }))
onBeforeUnmount(() => cancelAnimationFrame(frame))
</script>
<template><span class="number-ticker" :aria-label="Number.isFinite(value) ? String(value) : '暂无数据'">{{ text }}</span></template>
<style scoped>.number-ticker { font-variant-numeric: tabular-nums; }</style>
