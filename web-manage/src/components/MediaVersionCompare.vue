<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { call } from '../api.js'
import { audioDifferences, characterDifferences } from '../admin/mediaComparison.js'
import { characterVisual } from '../admin/characterAssets.js'
import CharacterVisual from './CharacterVisual.vue'
const props=defineProps({kind:String,versions:Array,current:Number})
const emit=defineEmits(['error'])
const before=ref(0),after=ref(0),left=ref(null),right=ref(null),busy=ref(false),error=ref('')
let generation=0,disposed=false
const cache=new Map()
const label=id=>id===0?'内置默认':`版本 #${id}`
const rows=computed(()=>!left.value || !right.value?[]:props.kind==='audio'?audioDifferences(left.value,right.value):characterDifferences(left.value,right.value))
const values=computed(()=>rows.value.filter(row=>row.type!=='image')),images=computed(()=>rows.value.filter(row=>row.type==='image'))
function version(id){if(!cache.has(id)){const request=call('GET',`/admin-api/v1/${props.kind}/versions/${id}`).catch(e=>{cache.delete(id);throw e});cache.set(id,request)}return cache.get(id)}
async function load(){const request=++generation;busy.value=true;error.value='';left.value=null;right.value=null
  try{const result=await Promise.all([version(before.value),version(after.value)]);if(!disposed && request===generation){left.value=result[0];right.value=result[1]}}
  catch(e){if(!disposed && request===generation){error.value=e.message || '读取历史版本失败';emit('error',e)}}finally{if(request===generation)busy.value=false}
}
watch(()=>[props.current,props.versions?.join(',')],()=>{if(props.current==null || !props.versions?.length)return;before.value=props.versions.find(id=>id!==props.current) ?? props.current;after.value=props.current},{immediate:true})
watch([before,after],()=>{if(props.current!=null && props.versions?.length)load()},{immediate:true,flush:'post'})
onBeforeUnmount(()=>{disposed=true;generation++;cache.clear()})
</script>
<template>
  <section class="surface media-comparison"><div class="surface-heading"><h3>{{kind==='audio'?'音频':'人物'}}历史版本对比</h3><span class="muted">仅查看已发布内容，保留当前编辑草稿。</span></div><div class="compare-selectors"><el-select v-model="before" aria-label="旧版本"><el-option v-for="id in versions" :key="id" :value="id" :label="label(id)" /></el-select><span>→</span><el-select v-model="after" aria-label="新版本"><el-option v-for="id in versions" :key="id" :value="id" :label="label(id)" /></el-select><el-button :loading="busy" @click="load">对比</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><div v-loading="busy"><el-table v-if="values.length" :data="values"><el-table-column prop="label" label="变更项" min-width="200" /><el-table-column prop="before" :label="label(before)" min-width="150" /><el-table-column prop="after" :label="label(after)" min-width="150" /></el-table><div class="image-differences"><article v-for="row in images" :key="row.key" class="image-difference"><h4>{{row.label}}</h4><div class="image-pair"><div><span>{{label(before)}} · {{row.before}}</span><CharacterVisual v-if="characterVisual(row.beforeCharacter,row.slot)" :asset="characterVisual(row.beforeCharacter,row.slot)" :label="row.label+'旧版本'" :height="160" /><p v-else class="muted">此版本无该素材</p></div><div><span>{{label(after)}} · {{row.after}}</span><CharacterVisual v-if="characterVisual(row.afterCharacter,row.slot)" :asset="characterVisual(row.afterCharacter,row.slot)" :label="row.label+'新版本'" :height="160" /><p v-else class="muted">此版本无该素材</p></div></div></article></div><el-empty v-if="left && right && !rows.length && !busy" description="所选两个版本没有内容差异" :image-size="60" /></div></section>
</template>
<style scoped>.compare-selectors{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:20px}.compare-selectors .el-select{width:190px}.image-differences{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:16px;margin-top:20px}.image-difference{border:1px solid var(--admin-border);border-radius:16px;padding:18px;background:var(--admin-secondary)}.image-difference h4{margin:0 0 14px;font-size:13px}.image-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.image-pair span{font-size:10px;color:var(--admin-muted)}@media(max-width:550px){.image-differences{grid-template-columns:1fr}.compare-selectors .el-select{width:100%}}</style>
