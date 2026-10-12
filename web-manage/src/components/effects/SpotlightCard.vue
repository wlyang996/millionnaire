<script setup>
// Adapted from Inspira UI CardSpotlight (MIT, rahulv.dev). See /admin/licenses/inspira-ui.txt.
import { ref } from 'vue'
import { richMotion } from '../../admin/preferences.js'
const position = ref({ x: -1000, y: -1000 })
function move(event) { if (!richMotion.value) return; const rect = event.currentTarget.getBoundingClientRect(); position.value = { x: event.clientX - rect.left, y: event.clientY - rect.top } }
</script>
<template><div class="spotlight-card" @pointermove="move" @pointerleave="position = { x: -1000, y: -1000 }"><div class="spotlight-content"><slot /></div><div v-if="richMotion" class="spotlight-light" :style="{ background: `radial-gradient(280px circle at ${position.x}px ${position.y}px, var(--admin-glow), transparent 70%)` }" aria-hidden="true" /></div></template>
<style scoped>.spotlight-card { position: relative; overflow: hidden; border: 1px solid var(--admin-border); border-radius: 20px; background: var(--admin-card); transition: transform .28s, box-shadow .28s; }.spotlight-content { position: relative; z-index: 1; height: 100%; }.spotlight-light { position: absolute; inset: 0; pointer-events: none; }html:not([data-admin-motion=reduced]) .spotlight-card:hover { transform: translateY(-4px); box-shadow: 0 16px 36px var(--admin-glow); }</style>
