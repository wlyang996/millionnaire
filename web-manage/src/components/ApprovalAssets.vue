<script setup>
import { ref,onMounted,onBeforeUnmount } from 'vue'
import { token,ApiError,reportAccessFailure } from '../api.js'
const props=defineProps({request:Object})
const emit=defineEmits(['error'])
const slots={portrait:'站姿',happy:'开心',sad:'失落',avatar:'头像',teeth:'小游戏',pawn:'棋盘棋子',roster:'名册',moveAnticipation:'移动蓄力',moveAir:'移动腾空',moveLand:'移动落地',moveReady:'移动准备'}
const rows=ref([]),controller=new AbortController()
async function load(row){row.busy=true;try{const response=await fetch(`/admin-api/v1/releases/${props.request.id}/preview/${encodeURIComponent(row.id)}`,{headers:{Authorization:'Bearer '+token()},signal:controller.signal});if(!response.ok){const data=await response.json();reportAccessFailure(response.status,data.code);throw new ApiError(response.status,data.code,data.message)}const blob=await response.blob();if(!controller.signal.aborted)row.url=URL.createObjectURL(blob)}catch(e){if(e.name!=='AbortError'){row.error=e.message;emit('error',e)}}finally{row.busy=false}}
onMounted(()=>{const request=props.request;if(request.module==='character'){rows.value=Object.keys(request.payload.pack.character.images).map(id=>({id,label:slots[id]||id,url:'',error:'',busy:false}));rows.value.forEach(load)}else{const s=request.payload.settings;const ids=[...new Set([...Object.values(s.scenes).map(v=>v.assetId),...Object.values(s.effects)])].filter(Boolean);rows.value=ids.map(id=>({id,label:id,url:'',error:'',busy:false}))}})
onBeforeUnmount(()=>{controller.abort();rows.value.forEach(row=>{if(row.url)URL.revokeObjectURL(row.url)})})
</script>
<template><section><h3>提交时的素材预览</h3><div class="ops-asset-list" :class="{'ops-approval-images':request.module==='character'}"><div v-for="row in rows" :key="row.id" v-loading="row.busy"><strong>{{row.label}}</strong><img v-if="request.module==='character'&&row.url" :src="row.url" :alt="row.label" /><template v-if="request.module==='audio'"><el-button v-if="!row.url" :disabled="row.busy" @click="load(row)">载入试听</el-button><audio v-else :src="row.url" controls preload="metadata" /></template><span v-if="row.error" role="status">{{row.error}}</span></div></div></section></template>
