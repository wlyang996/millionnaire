<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ asset: Object, label: String, height: { type: Number, default: 320 } })
const failed = ref(false)
watch(() => props.asset?.src, () => { failed.value = false })
const spriteStyle = computed(() => {
  const region = props.asset?.region, sheet = props.asset?.sheet
  if (!region || !sheet) return undefined
  return { width: `${sheet.width / region.w * 100}%`, height: `${sheet.height / region.h * 100}%`, left: `${-region.x / region.w * 100}%`, top: `${-region.y / region.h * 100}%` }
})
</script>
<template>
  <div class="character-visual" :style="{ '--visual-height': height+'px' }">
    <span v-if="failed" class="character-image-error" role="status">{{ label }}加载失败，请刷新重试</span>
    <div v-else-if="asset?.region" class="character-composition" :style="{ aspectRatio: asset.region.w*(asset.companion ? 1.24 : 1)+'/'+asset.region.h, '--sprite-ratio': asset.region.w / asset.region.h, '--composition-scale': asset.companion ? 1.24 : 1 }"><div class="character-sprite" :style="{ aspectRatio: asset.region.w+'/'+asset.region.h, width: asset.companion ? '80.645%' : '100%' }" :data-region="[asset.region.x,asset.region.y,asset.region.w,asset.region.h].join(',')"><img :src="asset.src" :alt="label" :style="spriteStyle" @error="failed=true" /></div><img v-if="asset.companion" class="character-companion" :src="asset.companion.src" :alt="asset.companion.label" :style="{ left:asset.companion.left,top:asset.companion.top,width:asset.companion.width }" /></div>
    <img v-else-if="asset?.src" class="character-single" :src="asset.src" :alt="label" @error="failed=true" />
  </div>
</template>
<style scoped>
.character-visual { width:100%; height:var(--visual-height); max-height:100%; display:flex; justify-content:center; align-items:center; position:relative; min-width:0; }
.character-single { max-width:100%; max-height:100%; object-fit:contain; filter:drop-shadow(0 12px 18px #6842a323); }
.character-composition { position:relative; flex-shrink:1; width:min(100%,calc(var(--visual-height) * var(--sprite-ratio) * var(--composition-scale))); }
.character-sprite { position:relative; overflow:hidden; }
.character-sprite img { position:absolute; max-width:none; max-height:none; }
.character-companion { position:absolute; height:auto; max-height:100%; object-fit:contain; }
.character-image-error { color:var(--admin-muted); text-align:center; padding:12px; font-size:11px; }
</style>
