<script setup>
import { computed, onMounted, onBeforeUnmount, onActivated, onDeactivated, ref } from 'vue'
import { motionEnabled } from '../admin/preferences.js'
const props=defineProps({images:Object})
const frames=['pawncrouch','pawnairborne','pawnland','pawnready']
const complete=computed(()=>frames.every(s=>props.images?.[s]?.src))
const paused=ref(false), labels=['蓄力','腾空','落地','准备']
const visibility=()=>{paused.value=document.hidden}
onMounted(()=>{document.addEventListener('visibilitychange',visibility);visibility()})
onBeforeUnmount(()=>document.removeEventListener('visibilitychange',visibility))
onActivated(visibility);onDeactivated(()=>{paused.value=true})
</script>
<template>
  <section v-if="complete" class="motion-preview surface" aria-label="人物移动动作预览">
    <div class="surface-heading"><h3>移动动作循环预览</h3><span class="muted">蓄力 → 腾空 → 落地 → 准备；实际速度取房间配置</span></div>
    <div class="motion-stage" :class="{'motion-static':!motionEnabled,'motion-paused':paused}"><img v-for="(slot,index) in frames" :key="slot" :src="images[slot].src" :style="{'--delay':index*.3+'s'}" :alt="labels[index]" class="motion-frame" /></div>
    <p class="muted">检查四张的脚底位置和人物大小是否一致。简化动效下显示静态蓄力帧。</p>
  </section>
</template>
<style scoped>
.motion-stage{position:relative;height:240px;background:radial-gradient(ellipse at 50% 90%,var(--admin-secondary),transparent 65%);overflow:hidden}.motion-frame{position:absolute;inset:0;margin:auto;max-width:100%;height:100%;object-fit:contain;opacity:0;animation:motion-frame 1.2s steps(1,end) infinite;animation-delay:var(--delay)}
@keyframes motion-frame{0%,24.99%{opacity:1}25%,100%{opacity:0}}
.motion-static .motion-frame{animation:none}.motion-static .motion-frame:first-child{opacity:1}
.motion-paused .motion-frame{animation-play-state:paused}
@media(prefers-reduced-motion:reduce){.motion-frame{animation:none}.motion-frame:first-child{opacity:1}}
</style>
