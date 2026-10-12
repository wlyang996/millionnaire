<script setup>
// Adapted from Inspira UI CardSpotlight (MIT, rahulv.dev). See /admin/licenses/inspira-ui.txt.
import { computed, ref, watch } from 'vue'
import { richMotion } from '../../admin/preferences.js'
const position = ref({ x: -1000, y: -1000 })
const props = defineProps({ tilt: Boolean })
const rotation = ref(null)
const tiltStyle = computed(() => props.tilt && richMotion.value && rotation.value ? { transform: `perspective(900px) rotateX(${rotation.value.x}deg) rotateY(${rotation.value.y}deg) translateY(-4px)` } : undefined)
function reset() { position.value = { x: -1000, y: -1000 }; rotation.value = null }
function move(event) {
  if (!richMotion.value || event.pointerType !== 'mouse' || !matchMedia('(hover:hover) and (pointer:fine)').matches) return
  const rect = event.currentTarget.getBoundingClientRect()
  const x = event.clientX - rect.left, y = event.clientY - rect.top
  position.value = { x, y }
  if (props.tilt) rotation.value = { x: Math.max(-5, Math.min(5, (0.5 - y / rect.height) * 10)), y: Math.max(-5, Math.min(5, (x / rect.width - 0.5) * 10)) }
}
watch(richMotion, reset)
</script>
<template><div class="spotlight-card" :class="{ 'tilt-card': tilt }" :style="tiltStyle" @pointermove="move" @pointerleave="reset"><div class="spotlight-content"><slot /></div><div v-if="richMotion" class="spotlight-light" :style="{ background: `radial-gradient(280px circle at ${position.x}px ${position.y}px, var(--admin-glow), transparent 70%)` }" aria-hidden="true" /></div></template>
<style scoped>.spotlight-card { position: relative; overflow: hidden; border: 1px solid var(--admin-border); border-radius: 20px; background: var(--admin-card); transition: transform .28s, box-shadow .28s; }.spotlight-content { position: relative; z-index: 1; height: 100%; }.spotlight-light { position: absolute; inset: 0; pointer-events: none; }html:not([data-admin-motion=reduced]) .spotlight-card:hover { transform: translateY(-4px); box-shadow: 0 16px 36px var(--admin-glow); }</style>
