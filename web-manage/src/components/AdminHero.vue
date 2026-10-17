<script setup>
import { computed, ref, watch } from 'vue'
import { groups } from '../admin/navigation.js'

const props = defineProps({ page: { type: Object, required: true }, dirty: Boolean })
const artwork = import.meta.glob('../assets/admin-heroes/*.webp', { eager: true, query: '?url', import: 'default' })
const scene = computed(() => {
  const group = groups.find(group => group.pages.some(([id]) => id === props.page.id))?.id
  if (group === 'operations') return props.page.id === 'releases' ? 'versions' : props.page.id === 'media' ? 'characters' : 'overview'
  return group || 'system'
})
const source = computed(() => artwork[`../assets/admin-heroes/${scene.value}.webp`])
const loaded = ref(false)
watch(source, () => { loaded.value = false })
</script>

<template>
  <section class="admin-hero" :data-hero-scene="scene" aria-labelledby="admin-page-title">
    <div class="hero-copy">
      <span class="eyebrow">MILLIONNAIRE / {{ page.group }}</span>
      <h1 id="admin-page-title">{{ page.title }}</h1>
      <p>{{ page.description }}</p>
      <span class="hero-caption"><span class="status-dot" :class="{ clean: !dirty }" />{{ dirty ? '编辑中的修改，发布后才会生效' : '每一个小调整，都是下一局的新惊喜' }}</span>
    </div>
    <div class="hero-scene" aria-hidden="true">
      <img :key="source" :src="source" alt="" width="1536" height="1024" decoding="async" draggable="false" :class="{ ready: loaded }" @load="loaded = true" @error="loaded = false" />
    </div>
  </section>
</template>

<style scoped>
.admin-hero { position:relative; display:flex; align-items:center; min-height:204px; padding:26px 32px; border:1px solid var(--admin-border); border-radius:23px; overflow:hidden; isolation:isolate; color:var(--admin-text); background:linear-gradient(110deg,var(--admin-card) 45%,color-mix(in srgb,var(--control-accent) 5%,var(--admin-card))); box-shadow:var(--admin-shadow); }
.hero-copy { position:relative; z-index:1; width:64%; min-width:0; }
.eyebrow { color:var(--control-accent); font-size:10px; letter-spacing:1.6px; }
h1 { font-size:clamp(23px,2vw,30px); line-height:1.4; letter-spacing:-.5px; margin:12px 0 8px; font-weight:650; text-wrap:balance; }
p { font-size:12px; line-height:1.8; margin:0; color:var(--admin-muted); }
.hero-caption { display:flex; align-items:center; gap:8px; margin-top:18px; color:var(--admin-muted); font-size:10px; line-height:1.6; }
.hero-scene { position:absolute; inset:8px 12px 8px auto; width:35%; max-width:380px; pointer-events:none; }
.hero-scene img { display:block; width:100%; height:100%; object-fit:contain; opacity:0; }
.hero-scene img.ready { opacity:1; animation:scene-arrive .6s cubic-bezier(.22,1,.36,1) both; }
@keyframes scene-arrive { from { opacity:0; transform:translate(8px,5px) rotate(-.6deg); } to { opacity:1; transform:none; } }
html[data-admin-motion=standard] .hero-scene img.ready { animation:scene-fade .24s ease-out both; }
@keyframes scene-fade { from { opacity:0; } to { opacity:1; } }
html[data-admin-theme=dark] .admin-hero { background:linear-gradient(110deg,var(--admin-card) 45%,color-mix(in srgb,var(--control-accent) 8%,var(--admin-card))); }
@media(max-width:1100px) { .admin-hero { padding:24px; } .hero-copy { width:70%; } .hero-scene { width:28%; right:8px; } h1 { font-size:25px; } }
@media(max-width:760px) { .admin-hero { min-height:180px; padding:22px; } h1 { font-size:23px; } .eyebrow { font-size:9px; } .hero-caption { margin-top:14px; } }
@media(max-width:520px) { .admin-hero { min-height:0; padding:22px 20px; border-radius:18px; } .hero-copy { width:100%; } .hero-scene { display:none; } h1 { font-size:23px; } }
</style>
