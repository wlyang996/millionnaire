<script setup>
// Adapted from Inspira UI BorderBeam (MIT, rahulv.dev). See /admin/licenses/inspira-ui.txt.
import { richMotion } from '../../admin/preferences.js'
</script>
<template><div v-if="richMotion" class="border-beam" aria-hidden="true" /></template>
<style scoped>.border-beam { position: absolute; inset: 0; border-radius: inherit; pointer-events: none; border: 1.5px solid transparent; mask: linear-gradient(transparent,transparent),linear-gradient(white,white); mask-clip: padding-box,border-box; mask-composite: intersect; }.border-beam:after { content: ''; position: absolute; width: 140px; aspect-ratio: 1; background: linear-gradient(to left,#ffd296,#b593ff,transparent); offset-path: rect(0 auto auto 0 round 20px); offset-anchor: 90% 50%; animation: beam 8s linear infinite; }@keyframes beam { to { offset-distance: 100%; } }</style>
