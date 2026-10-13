<script setup>
import { computed, onMounted, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Music2, Upload, Headphones, History, Volume2 } from '@lucide/vue'
import { call, token, ApiError, reportAccessFailure } from './api.js'
const props = defineProps({readOnly:Boolean,canPublish:Boolean,canRollback:Boolean})
const emit = defineEmits(['update:dirty','version','published','unauthorized'])
const sceneLabels = { lobby:'大厅',room:'好友房间 / 系列赛',board:'棋盘 / 观战',game:'小游戏',result:'结算' }
const effectLabels = { dice:'骰子投掷',move:'逐格移动',cashGain:'金钱增加',cashLoss:'金钱减少',card:'翻牌',purchase:'购买土地',upgrade:'升级土地',auction:'拍卖结果',jail:'入狱',release:'出狱',reward:'全员奖励' }
const state = ref(null), form = ref(null), baseline = ref(''), busy = ref(false), player = ref(null), previewUrl = ref(''), previewName = ref('')
const uploadName = ref(''), kind = ref('music'), input = ref(null), file = ref(null), progress = ref(0)
let pending = null, previewRequest = null
const dirty = computed(() => !props.readOnly && !!form.value && (JSON.stringify(form.value)!==baseline.value || JSON.stringify(form.value)!==JSON.stringify(state.value.current.settings)))
const unsaved = computed(() => !props.readOnly && !!form.value && JSON.stringify(form.value)!==baseline.value)
watch(dirty, v => emit('update:dirty',v))
const music = computed(() => state.value?.assets.filter(a=>a.kind==='music')||[])
const effects = computed(() => state.value?.assets.filter(a=>a.kind==='effect')||[])
function fail(e) { reportAccessFailure(e.status,e.code); if(e.status===401)emit('unauthorized'); ElMessage.error(e.message||'音频操作失败') }
function apply(s) { state.value=s; form.value=structuredClone(props.readOnly?s.current.settings:s.draft.settings); baseline.value=JSON.stringify(form.value);emit('version',s.current.version) }
const publisher=r=>r.version===0?'系统':state.value?.publishers?.[r.version]?.displayName||state.value?.publishers?.[r.version]?.username||'未记录'
async function load() {
  if(unsaved.value){try{await ElMessageBox.confirm('刷新会放弃本页尚未保存的编辑，继续？','刷新音频',{type:'warning'})}catch{return}}
  busy.value=true
  try{apply(await call('GET',props.readOnly?'/admin-api/v1/audio/published':'/admin-api/v1/audio'))}catch(e){fail(e)}finally{busy.value=false}
}
function stop() { previewRequest?.abort(); previewRequest=null; player.value?.pause(); if(previewUrl.value)URL.revokeObjectURL(previewUrl.value); previewUrl.value=''; previewName.value='' }
async function audition(a) {
  stop(); const request = new AbortController(); previewRequest=request
  try {
    const response=await fetch(`/admin-api/v1/audio/${props.readOnly?'published/':''}assets/${a.id}.wav`,{headers:{Authorization:'Bearer '+token()},signal:request.signal})
    if(!response.ok){let error;try{error=await response.json()}catch{};throw new ApiError(response.status,error?.code,error?.message)}
    const blob=await response.blob(); if(previewRequest!==request)return
    previewUrl.value=URL.createObjectURL(blob); previewName.value=a.name; previewRequest=null
    // The player controls require a user click; loading a preview never starts or publishes it.
  }catch(e){if(e.name!=='AbortError')fail(e)}
}
async function upload() {
  if(props.readOnly)return
  if(!file.value || !uploadName.value.trim())return ElMessage.warning('填写名称并选择 WAV 文件')
  if(file.value.size>10*1024*1024)return ElMessage.error('音频不可超过10MB')
  busy.value=true; progress.value=0
  const body=new FormData();body.append('name',uploadName.value);body.append('kind',kind.value);body.append('file',file.value)
  try {
    const result=await new Promise((resolve,reject)=>{
      const xhr=new XMLHttpRequest();pending=xhr;xhr.open('POST','/admin-api/v1/audio/assets');xhr.timeout=120000;xhr.setRequestHeader('Authorization','Bearer '+token())
      xhr.upload.onprogress=e=>{if(e.lengthComputable)progress.value=Math.round(e.loaded/e.total*100)}
      xhr.onload=()=>{pending=null;let data;try{data=JSON.parse(xhr.responseText)}catch{return reject(new Error('后台上传响应无法读取'))};xhr.status>=200&&xhr.status<300?resolve(data):reject(new ApiError(xhr.status,data.code,data.message))}
      xhr.onerror=()=>{pending=null;reject(new Error('上传连接失败'))};xhr.ontimeout=()=>{pending=null;reject(new Error('上传超时'))};xhr.onabort=()=>{pending=null;reject(new Error('上传已取消'))};xhr.send(body)
    })
    state.value.assets.push(result);file.value=null;input.value.value='';uploadName.value='';ElMessage.success('上传成功。试听并绑定后，保存草稿、发布才会生效。')
  }catch(e){fail(e)}finally{busy.value=false}
}
async function save() {
  if(props.readOnly)return
  state.value.draft=await call('POST','/admin-api/v1/audio/draft',{settings:form.value,revision:state.value.draft.revision,expectedVersion:state.value.current.version})
  baseline.value=JSON.stringify(form.value)
}
async function saveOnly() { busy.value=true;try{await save();ElMessage.success('音频草稿已保存，客户端尚未改变')}catch(e){fail(e)}finally{busy.value=false} }
async function publish() {
  if(props.readOnly||!props.canPublish)return
  try{await ElMessageBox.confirm('发布当前音乐、音效绑定及默认音量？玩家下次进入页面时读取；个人静音与音量不会被覆盖。','发布音频配置',{type:'info'})}catch{return}
  busy.value=true
  try{if(unsaved.value)await save();apply(await call('POST','/admin-api/v1/audio/publish',{revision:state.value.draft.revision,expectedVersion:state.value.current.version}));emit('published')}catch(e){fail(e)}finally{busy.value=false}
}
async function rollback(r) {
  if(!props.canRollback)return
  try{await ElMessageBox.confirm(`将音频配置回滚到${r.version?'版本 #'+r.version:'内置默认'}？当前草稿会替换，旧资源与历史继续保留。`,'回滚音频',{type:'warning'})}catch{return}
  busy.value=true
  try{apply(await call('POST','/admin-api/v1/audio/rollback',{version:r.version,revision:state.value.draft.revision,expectedVersion:state.value.current.version}));emit('published')}catch(e){fail(e)}finally{busy.value=false}
}
onMounted(load)
onDeactivated(stop)
onBeforeUnmount(()=>{stop();pending?.abort();emit('update:dirty',false)})
</script>
<template>
  <div v-loading="busy" class="audio-manager">
    <div class="audio-summary"><span class="audio-emblem"><Music2 :size="25" /></span><div><h3>小镇的声音</h3><p class="muted">当前{{state?.current.version?'音频版本 #'+state.current.version:'内置默认'}} · {{readOnly?'生效配置 · 只读试听':'上传、试听和发布各自独立'}}</p></div><el-button @click="load">刷新</el-button></div>
    <el-alert v-if="!readOnly && state && !state.persistent" type="warning" :closable="false" title="当前未连接数据库，上传目录与草稿在服务重启后丢失；正式发布请启用 db 配置。" />
    <el-alert v-if="!readOnly && state && !state.storageReady" type="info" :closable="false" title="已选音乐可直接使用。上传新音频需要微信云托管 COS_BUCKET、COS_REGION 和云调用权限。" />
    <section class="audio-card"><h3><Upload :size="19" />音频资源</h3><p class="muted">①晨光小镇、③好友开局已采用。②保留为历史候选，不进入资源目录。上传独立素材请先试听审核；曾被否定的混音录音不采用。</p>
      <div v-if="!readOnly" class="audio-upload"><el-input v-model="uploadName" maxlength="30" placeholder="资源名称" aria-label="音频资源名称" /><el-select v-model="kind" aria-label="资源用途"><el-option value="music" label="背景音乐" /><el-option value="effect" label="独立音效" /></el-select><input ref="input" type="file" accept=".wav,audio/wav" aria-label="选择音频文件" @change="file=$event.target.files[0]" /><el-button type="primary" :disabled="busy||!state?.storageReady" @click="upload">上传</el-button></div>
      <small v-if="!readOnly" class="muted">PCM WAV，16位，单/双声道，22050/44100/48000Hz；≤10MB；音乐≤120秒，音效≤10秒。MP3请先转换。文件不会覆盖旧版本。</small><el-progress v-if="busy&&progress" :percentage="progress" />
      <div class="audio-library"><article v-for="a in state?.assets" :key="a.id" class="audio-track"><Headphones :size="22" /><div><strong>{{a.name}}</strong><small>{{a.kind==='music'?'背景音乐':'独立音效'}} · {{a.info.seconds.toFixed(1)}}秒 · {{(a.info.bytes/1048576).toFixed(2)}}MB{{a.builtin?' · 已采用内置':''}}</small></div><el-button @click="audition(a)">试听</el-button></article></div>
      <div v-if="previewUrl" class="audio-audition"><strong>试听：{{previewName}}</strong><audio ref="player" :src="previewUrl" controls preload="metadata" /><el-button @click="stop">停止试听</el-button></div>
    </section>
    <template v-if="form"><section class="audio-card"><h3><Volume2 :size="19" />开关与默认音量</h3><div class="audio-defaults"><label>背景音乐<el-switch :disabled="readOnly" v-model="form.musicEnabled" aria-label="背景音乐总开关" /><div class="audio-volume-control"><el-slider :disabled="readOnly" v-model="form.musicVolume" aria-label="背景音乐默认音量" /><input class="audio-volume-value" type="number" :disabled="readOnly" v-model.number="form.musicVolume" min="0" max="100" step="1" aria-label="背景音乐默认音量（数值）" /></div></label><label>游戏音效<el-switch :disabled="readOnly" v-model="form.effectsEnabled" aria-label="音效总开关" /><div class="audio-volume-control"><el-slider :disabled="readOnly" v-model="form.effectsVolume" aria-label="音效默认音量" /><input class="audio-volume-value" type="number" :disabled="readOnly" v-model.number="form.effectsVolume" min="0" max="100" step="1" aria-label="音效默认音量（数值）" /></div></label><label>音乐切换淡入淡出（毫秒）<el-input-number :disabled="readOnly" v-model="form.fadeMs" :min="0" :max="3000" :step="100" /></label></div><p class="muted">这里只提供初次使用与“恢复默认”的音量。玩家本机偏好优先；后台关闭音频后，个人开关不能强行开启。</p></section>
    <section class="audio-card"><h3>场景选曲</h3><div v-for="(label,id) in sceneLabels" :key="id" class="audio-binding"><strong>{{label}}</strong><el-select :disabled="readOnly" :empty-values="[null, undefined]" v-model="form.scenes[id].assetId" :aria-label="label+'背景音乐'"><el-option value="" label="静音" /><el-option v-for="a in music" :key="a.id" :value="a.id" :label="a.name" /></el-select><el-checkbox :disabled="readOnly" v-model="form.scenes[id].loop">循环播放</el-checkbox></div></section>
    <section class="audio-card"><h3>独立音效</h3><p class="muted">当前内置音效为空，等待独立素材审核。空选项不会播放任何声音。</p><div class="audio-effects"><div v-for="(label,id) in effectLabels" :key="id" class="audio-binding"><strong>{{label}}</strong><el-select :disabled="readOnly" :empty-values="[null, undefined]" v-model="form.effects[id]" :aria-label="label+'音效'"><el-option value="" label="暂不播放" /><el-option v-for="a in effects" :key="a.id" :value="a.id" :label="a.name" /></el-select></div></div></section>
    <div v-if="!readOnly" class="audio-actions"><span>{{unsaved?'编辑尚未保存':dirty?'草稿待发布':'与生效版本一致'}}</span><el-button :disabled="busy||!unsaved" @click="saveOnly">保存草稿</el-button><el-button v-if="canPublish" type="primary" :disabled="busy" @click="publish">发布音频配置</el-button></div>
    </template>
    <section class="audio-card"><h3><History :size="19" />音频历史</h3><div v-for="r in state?.history" :key="r.version" class="audio-history"><span>{{r.version?'音频版本 #'+r.version:'内置默认'}} · {{r.createdAt?new Date(r.createdAt).toLocaleString():'两首已采用音乐'}} · 发布人：{{publisher(r)}}</span><el-button v-if="canRollback" :disabled="busy||r.version===state.current.version" @click="rollback(r)">回滚</el-button></div></section>
  </div>
</template>
<style scoped>
.audio-manager{display:grid;gap:20px}.audio-summary,.audio-track,.audio-binding,.audio-history,.audio-actions{display:flex;align-items:center;gap:16px}.audio-summary h3,.audio-track strong{margin:0}.audio-summary>div{flex:1}.audio-summary p{margin:6px 0}.audio-emblem{display:grid;place-items:center;width:52px;height:52px;border-radius:18px;background:var(--admin-brand-soft);color:var(--admin-brand)}.audio-card{padding:24px;border:1px solid var(--admin-border);border-radius:22px;background:var(--admin-card)}.audio-card h3{display:flex;align-items:center;gap:10px;margin:0 0 16px}.audio-upload{display:grid;grid-template-columns:1fr 140px 1fr auto;align-items:center;gap:12px;margin-bottom:10px}.audio-upload input{max-width:100%}.audio-library{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:12px;margin-top:20px}.audio-track{padding:16px;border:1px solid var(--admin-border);border-radius:16px;background:var(--admin-secondary)}.audio-track>div{flex:1}.audio-track small{display:block;margin-top:6px;color:var(--admin-muted)}.audio-audition{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-top:18px}.audio-defaults{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:28px}.audio-defaults label{display:grid;gap:12px}.audio-volume-control{display:grid;grid-template-columns:minmax(0,1fr) 88px;gap:20px;align-items:center}.audio-volume-value{width:100%;min-width:0;height:34px;box-sizing:border-box;padding:4px 10px;border:1px solid var(--admin-border);border-radius:8px;background:var(--admin-card);color:var(--admin-text);font:inherit;text-align:center}.audio-volume-value:focus{outline:2px solid var(--admin-brand);outline-offset:2px}.audio-volume-value:disabled{background:var(--admin-secondary);color:var(--admin-muted)}.audio-binding{margin:10px 0}.audio-binding strong{min-width:160px}.audio-binding .el-select{flex:1;min-width:120px}.audio-effects{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px}.audio-effects strong{min-width:90px}.audio-actions{padding:18px 24px;border:1px solid var(--admin-border);border-radius:18px;background:var(--admin-card)}.audio-actions span,.audio-history span{flex:1}.audio-history{padding:10px 0;border-bottom:1px solid var(--admin-border)}@media(max-width:900px){.audio-upload{grid-template-columns:1fr 1fr}.audio-defaults,.audio-effects{grid-template-columns:1fr}}@media(max-width:550px){.audio-upload{grid-template-columns:1fr}.audio-binding{flex-wrap:wrap}.audio-actions{flex-wrap:wrap}.audio-actions span{flex-basis:100%}.audio-card{padding:16px}}
</style>
