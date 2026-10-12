<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import LoginMascots from './LoginMascots.vue'
import { createLoginAnimation } from './loginAnimation.js'
import { motionEnabled } from '../admin/preferences.js'
const props = defineProps({ busy: Boolean, error: Boolean, success: Number })
const root = ref(null)
let animation, observer, visible = true
function state() { animation?.setState(props.success ? 'success' : props.error ? 'sad' : props.busy ? 'typing' : 'idle') }
function restart() { animation?.dispose(); animation = null; if(document.hidden || !visible) return; animation = createLoginAnimation(root.value,{reducedMotion:!motionEnabled.value,celebrateConfetti:false}); state() }
watch([() => props.busy, () => props.error, () => props.success], state)
watch(motionEnabled,restart)
onMounted(() => {restart(); observer=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;restart()});observer.observe(root.value);document.addEventListener('visibilitychange',restart)})
onBeforeUnmount(()=>{animation?.dispose();observer?.disconnect();document.removeEventListener('visibilitychange',restart)})
</script>
<template><div ref="root" class="admin-mascot" aria-hidden="true"><LoginMascots /><canvas id="confettiCanvas" style="display:none" /></div></template>
<style scoped>.admin-mascot { width:155px; position:absolute; right:175px; bottom:15px; z-index:2; pointer-events:none; }.admin-mascot :deep(svg) { width:100%; height:auto; display:block; overflow:visible; }@media(max-width:1000px){.admin-mascot{width:115px;right:125px;bottom:16px;}}@media(max-width:760px){.admin-mascot{display:none;}}</style>
