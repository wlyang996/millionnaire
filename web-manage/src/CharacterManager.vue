<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { call, token, ApiError } from './api.js'
import { Plus, RefreshCw, ArrowUpRight, Upload, Check, Image, ChevronRight } from '@lucide/vue'
import { motion } from 'motion-v'
import { motionEnabled } from './admin/preferences.js'
import { characterImage, characterVisual } from './admin/characterAssets.js'
import CharacterVisual from './components/CharacterVisual.vue'
import CharacterMotionPreview from './components/CharacterMotionPreview.vue'
import { characterMetadata } from './admin/characterMetadata.js'
import SpotlightCard from './components/effects/SpotlightCard.vue'
import BorderBeam from './components/effects/BorderBeam.vue'
const props=defineProps({readOnly:Boolean,canRollback:Boolean})
const emit = defineEmits(['update:dirty','published','unauthorized'])
const selectedSlot = ref('portrait'), selectedCharacter = ref(null), uploadSlot = ref(''), uploadPercent = ref(0)
let pendingUpload = null
const isUnsaved = computed(() => !props.readOnly && !pack.value?.published && JSON.stringify(form.value) !== originalMetadata.value)
function slotVisual(slot) { return pack.value ? previews.value[slot] ? { src: previews.value[slot] } : null : characterVisual(selectedCharacter.value, slot) }
const stageVisual = computed(() => slotVisual(selectedSlot.value))
const displayedName = computed(() => form.value.name || pack.value?.character.name || selectedCharacter.value?.name || '人物预览')
function markSaved() { originalMetadata.value = JSON.stringify(form.value) }
function uploadRequest(url, body) {
  return new Promise((resolve,reject) => {
    const xhr = new XMLHttpRequest(); pendingUpload = xhr
    xhr.open('POST',url); xhr.setRequestHeader('Authorization','Bearer '+token())
    xhr.upload.onprogress = event => { if(event.lengthComputable) uploadPercent.value = Math.round(event.loaded/event.total*100) }
    xhr.onload = () => { pendingUpload=null; let data; try {data=JSON.parse(xhr.responseText)} catch {reject(new Error('后台返回无法读取的上传结果'));return} if(xhr.status>=200&&xhr.status<300)resolve(data); else reject(new ApiError(xhr.status,data.code,data.message)) }
    xhr.onerror = () => {pendingUpload=null;reject(new Error('上传连接失败，请重试'))}
    xhr.onabort = () => {pendingUpload=null;reject(new Error('上传已取消'))}
    xhr.send(body)
  })
}
async function chooseCharacter(c) {
  if(isUnsaved.value) {try {await ElMessageBox.confirm('切换人物将放弃未保存的资料，继续？','切换人物',{type:'warning'})} catch{return}}
  reset(); selectedCharacter.value=c; form.value=characterMetadata(c); markSaved(); selectedSlot.value='portrait'
}
async function newCharacter() { if(isUnsaved.value){try {await ElMessageBox.confirm('新建人物将放弃未保存的资料，继续？','新建人物',{type:'warning'})}catch{return}} reset(); selectedCharacter.value=null }
async function choosePack(p) {if(isUnsaved.value){try{await ElMessageBox.confirm('切换草稿将放弃未保存的资料，继续？','切换草稿',{type:'warning'})}catch{return}} selectedCharacter.value=null; await select(p)}


const labels = { avatar: '普通头像', pawn: '棋盘棋子', portrait: '半身人物', give: '递币', receive: '接币', worry: '欠款忧虑', celebrate: '托杯庆祝', pawncrouch:'移动·蓄力', pawnairborne:'移动·腾空', pawnland:'移动·落地', pawnready:'移动·准备' }
const slots = Object.keys(labels)
const basicSlots = slots.slice(0,7), motionSlots = slots.slice(7)
const state = ref(null), pack = ref(null), busy = ref(false), previews = ref({})
const fresh = () => ({ avatar: null, name: '', sort: 8, enabled: true, fallback: 0 })
const form = ref(fresh())
const originalMetadata = ref(JSON.stringify(form.value))
const hasMotion = computed(() => motionSlots.some(s => pack.value?.character.images[s]))
const requiredSlots = computed(() => hasMotion.value ? slots : basicSlots)
const complete = computed(() => requiredSlots.value.every(s => pack.value?.character.images[s]) && (!hasMotion.value || motionSlots.every(s => pack.value.character.images[s].width===pack.value.character.images.pawn.width && pack.value.character.images[s].height===pack.value.character.images.pawn.height)))
const choices = computed(() => state.value?.active.characters || [])
const progress = computed(() => requiredSlots.value.filter(s => pack.value?.character.images[s]).length)
const motionImages = computed(() => Object.fromEntries(motionSlots.map(s=>[s,slotVisual(s)])))
const dirty = computed(() => {
  const c = pack.value?.character
  return !!c && (form.value.name !== c.name || form.value.sort !== c.sort || form.value.enabled !== c.enabled || form.value.fallback !== c.fallback)
})
function fail(e) { if(e.status===401)emit('unauthorized'); ElMessage.error(e.message || '人物素材操作失败') }
async function checkStorage() {
  busy.value = true
  try { const r = await call('POST', '/admin-api/v1/characters/storage/check', {}); ElMessage.success(r.message) }
  catch (e) { fail(e) } finally { busy.value = false }
}
function clearPreviews() { Object.values(previews.value).forEach(URL.revokeObjectURL); previews.value = {} }
async function load() {
  busy.value = true
  try { state.value = await call('GET', '/admin-api/v1/characters'+(props.readOnly?'/published':'')) } catch (e) { fail(e) }
  finally { busy.value = false }
}
function reset() { clearPreviews(); pack.value = null; form.value = fresh(); markSaved() }
async function select(p) {
  clearPreviews(); pack.value = p
  form.value = characterMetadata(p.character)
  markSaved()
  for (const slot of slots) if (p.character.images[slot]) {
    try {
      const r = await fetch(`/admin-api/v1/characters/packs/${p.packId}/assets/${slot}.png`, { headers: { Authorization: 'Bearer ' + token() } })
      if (!r.ok) throw new ApiError(r.status, '', '预览读取失败')
      const url = URL.createObjectURL(await r.blob())
      if (pack.value?.packId !== p.packId) URL.revokeObjectURL(url)
      else previews.value[slot] = url
    } catch (e) { fail(e) }
  }
}
async function save() {
  if (busy.value) return
  busy.value = true
  try {
    const path = '/admin-api/v1/characters/packs' + (pack.value ? '/' + pack.value.packId : '')
    pack.value = await call('POST', path, { ...form.value, revision: pack.value?.revision })
    form.value = characterMetadata(pack.value.character)
    markSaved(); await load(); ElMessage.success('整套素材草稿已保存')
  } catch (e) { fail(e) } finally { busy.value = false }
}
async function upload(slot, event) {
  const file = event.target.files[0]; event.target.value = ''
  if (!file || !pack.value) return
  if (file.size > 5 * 1024 * 1024) return ElMessage.error('每张图片不可超过 5MB')
  busy.value = true; uploadSlot.value=slot; uploadPercent.value=0
  try {
    const body = new FormData(); body.append('file', file); body.append('revision', String(pack.value.revision))
    const data = await uploadRequest(`/admin-api/v1/characters/packs/${pack.value.packId}/assets/${slot}`, body)
    pack.value = data
    if (previews.value[slot]) URL.revokeObjectURL(previews.value[slot])
    previews.value[slot] = URL.createObjectURL(file)
    await load(); ElMessage.success(labels[slot] + '已上传到 COS')
  } catch (e) { fail(e) } finally { busy.value = false; uploadSlot.value='' }
}
async function publish() {
  if (dirty.value) return ElMessage.warning('请先保存人物资料，再发布整套素材')
  try { await ElMessageBox.confirm('发布完整人物素材后，新房间将使用新版本；进行中的房间保持原版本。', '发布整套素材') }
  catch { return }
  busy.value = true
  try {
    await call('POST', '/admin-api/v1/characters/publish', { packId: pack.value.packId, revision: pack.value.revision, expectedVersion: state.value.active.version })
    reset(); await load(); emit('published'); ElMessage.success('整套人物素材已发布')
  } catch (e) { fail(e) } finally { busy.value = false }
}
async function rollback(version) {
  if(!props.canRollback)return
  try { await ElMessageBox.confirm(`将人物目录回滚到版本 ${version}，已有房间不受影响。`, '回滚人物目录') }
  catch { return }
  busy.value = true
  try { await call('POST', '/admin-api/v1/characters/rollback', { version, expectedVersion: state.value.active.version }); await load(); ElMessage.success('已回滚') }
  catch (e) { fail(e) } finally { busy.value = false }
}
function replace(c) { reset(); form.value = { avatar: c.id, name: c.name, sort: c.sort, enabled: c.enabled, fallback: c.fallback } }
async function configure() {
  if (busy.value) return
  busy.value = true
  try {
    const catalog = await call('POST', '/admin-api/v1/characters/configure', { id: form.value.avatar, name: form.value.name, sort: form.value.sort, enabled: form.value.enabled, expectedVersion: state.value.active.version })
    const canonical = catalog.characters.find(character => character.id === form.value.avatar)
    if (canonical) { form.value = characterMetadata(canonical); selectedCharacter.value = canonical }
    markSaved(); await load(); emit('published'); ElMessage.success('名称、排序和选择状态已发布，整套图片保持原版本')
  }
  catch (e) { fail(e) } finally { busy.value = false }
}
markSaved()
watch(isUnsaved, value=>emit('update:dirty',value), {immediate:true})
onMounted(load)
onBeforeUnmount(()=>{pendingUpload?.abort();clearPreviews();emit('update:dirty',false)})
</script>

<template>
  <div class="character-studio" :class="{'character-readonly':readOnly}" v-loading="busy">
    <div class="section-toolbar"><span class="version-badge">人物目录 · {{ state?.active.version === 0 ? '内置默认' : '#' + (state?.active.version ?? '—') }}</span><el-button v-if="!readOnly" type="primary" @click="newCharacter"><Plus :size="15" />新建人物</el-button><el-button @click="load"><RefreshCw :size="14" />刷新目录</el-button><span class="muted">{{ readOnly?'生效人物与动作帧只读预览':'七张基础素材，可追加四张完整移动姿态' }}</span><el-tag v-if="readOnly" type="info">只读</el-tag></div>
    <el-alert v-if="!readOnly && state && !state.storageReady" class="gap" title="对象存储尚未配置，当前不能上传或发布素材" type="warning" :closable="false" />
    <el-alert v-if="!readOnly && state && !state.persistent" class="gap" title="当前目录仅存内存，后台重启后会丢失；正式使用需要数据库。" type="warning" :closable="false" />
    <div class="character-gallery"><SpotlightCard v-for="c in choices" :key="c.id" tilt><motion.button type="button" class="character-card" :class="{ selected: selectedCharacter?.id===c.id }" :aria-pressed="selectedCharacter?.id===c.id" :while-press="motionEnabled ? {scale:.97}:undefined" @click="chooseCharacter(c)"><div class="character-avatar"><img v-if="characterImage(c)" :src="characterImage(c)" :alt="c.name" loading="lazy" /><span v-else>{{ c.name.slice(0,1) }}</span></div><div><strong>{{ c.name }}</strong><small>{{ c.bundled?'客户端默认':'整套云素材' }}</small></div><span class="character-status" :class="{ disabled: !c.enabled }">{{ c.enabled?'可选':'停用' }}</span><ArrowUpRight :size="15" /></motion.button></SpotlightCard></div>
    <div class="character-editor-grid">
      <section class="character-stage surface"><div class="surface-heading"><h3>{{ displayedName }}</h3><span class="muted">{{ labels[selectedSlot] }}</span></div><div class="character-display"><div class="character-stage-glow" aria-hidden="true" /><Transition name="pose" mode="out-in"><CharacterVisual v-if="stageVisual" :key="`${selectedSlot}:${stageVisual.src}:${stageVisual.region?.x}:${stageVisual.region?.y}`" :asset="stageVisual" :label="labels[selectedSlot]" /><div v-else :key="selectedSlot" class="empty-pose"><Image :size="36" /><span>{{ pack || selectedCharacter ? '此姿态暂无素材' : readOnly ? '选择人物查看素材与移动姿态' : '选择人物，或创建一个新角色' }}</span></div></Transition></div><div class="pose-picker"><button v-for="slot in slots" :key="slot" type="button" :class="{ selected: selectedSlot===slot }" :aria-pressed="selectedSlot===slot" @click="selectedSlot=slot">{{ labels[slot] }}</button></div><BorderBeam /></section>
      <section v-if="!readOnly" class="surface character-metadata"><div class="surface-heading"><h3>{{ pack ? '编辑整套草稿' : form.avatar!==null ? '配置人物资料' : '新建人物' }}</h3><el-tag v-if="pack?.published" type="success">已发布 · 只读</el-tag><el-tag v-else-if="isUnsaved" type="warning">未保存</el-tag></div><el-form label-width="120px" class="character-form">
      <el-form-item label="人物 ID"><span>{{ pack?.character.id ?? form.avatar ?? '保存草稿后自动分配' }}</span></el-form-item>
      <el-form-item label="名称"><el-input v-model="form.name" maxlength="20" :disabled="pack?.published || busy" /></el-form-item>
      <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="9999" :disabled="pack?.published || busy" /></el-form-item>
      <el-form-item label="可供选择"><el-switch v-model="form.enabled" :disabled="pack?.published || busy" /><span class="character-note">停用仅隐藏新选择，不改变已有账号人物。</span></el-form-item>
      <el-form-item label="本地备用人物"><el-select v-model="form.fallback" :disabled="pack?.published || busy"><el-option v-for="(name,id) in ['糖糖','可可','阿杰','奶茶','阿凯','圆圆','豆豆','毛毛']" :key="id" :value="id" :label="name" /></el-select><span class="character-note">下载失败时整套回退，避免混用人物。</span></el-form-item>
      <el-form-item><el-button type="primary" :disabled="pack?.published || busy" @click="save">{{ pack ? '保存资料' : '创建整套草稿' }}</el-button><el-button v-if="!pack && form.avatar !== null" @click="configure">仅发布名称、排序、启停</el-button></el-form-item>
    </el-form></section>
    </div>
    <section v-if="!readOnly" class="surface"><div class="surface-heading"><h3>基础素材与移动动作</h3><span class="muted">单张 PNG ≤ 5MB · 64～2048px</span></div><el-progress :percentage="Math.round(progress/requiredSlots.length*100)" :format="()=>progress+' / '+requiredSlots.length+' 已上传'" :status="complete?'success':undefined" /><div class="character-images"><div v-for="slot in slots" :key="slot" class="character-image" :class="{ complete: previews[slot], uploading: uploadSlot===slot }"><span class="asset-label">{{ labels[slot] }}<Check v-if="previews[slot]" :size="14" /></span><button type="button" class="character-preview asset-preview-button" :aria-label="'预览'+labels[slot]" :disabled="!slotVisual(slot)" @click="selectedSlot=slot"><CharacterVisual v-if="slotVisual(slot)" :asset="slotVisual(slot)" :label="labels[slot]" :height="140" /><div v-else class="asset-empty"><Upload :size="22" /><span>{{ !pack?'先创建或选择草稿':'选择 PNG 上传' }}</span></div></button><small v-if="!pack && selectedCharacter?.bundled">客户端内置参考 · 未上传</small><small v-if="pack?.character.images[slot]">{{ pack.character.images[slot].width }} × {{ pack.character.images[slot].height }}</small><el-progress v-if="uploadSlot===slot" :percentage="uploadPercent" :stroke-width="4" /><input type="file" accept="image/png" :aria-label="'上传'+labels[slot]" :disabled="busy || !pack || pack.published || !state?.storageReady" @change="upload(slot,$event)" /></div></div><div class="section-toolbar"><el-button type="primary" :disabled="!complete || !pack || pack.published || dirty || !state?.storageReady || busy" @click="publish">发布完整人物素材</el-button><span class="muted">{{ pack?.published ? '已发布，不可覆盖' : dirty ? '资料有未保存修改，请先保存' : '基础七张必需；追加移动动作时四张须齐全，画布与棋子相同，脚底位置一致。其余图须透明背景。' }}</span></div></section>
    <CharacterMotionPreview :images="motionImages" />
    <section v-if="!readOnly" class="surface"><h3>素材草稿与版本</h3><el-table :data="state?.packs || []"><el-table-column label="人物"><template #default="{row}">{{ row.character.name }} · ID {{ row.character.id }}</template></el-table-column><el-table-column label="完整度" min-width="100"><template #default="{row}">{{ Object.keys(row.character.images).length }} / {{ motionSlots.some(s=>row.character.images[s]) ? 11 : 7 }}</template></el-table-column><el-table-column label="状态" min-width="100"><template #default="{row}"><el-tag :type="row.published?'success':'info'">{{ row.published?'已发布':'草稿' }}</el-tag></template></el-table-column><el-table-column label="操作" min-width="130"><template #default="{row}"><el-button @click="choosePack(row)">查看 / 编辑</el-button></template></el-table-column></el-table></section>
    <section class="surface"><h3>人物目录发布历史</h3><p v-for="v in state?.history || []" :key="v" class="muted">{{ v===0?'内置默认':'版本 #'+v }} · {{ v===0?'系统':state?.publishers?.[v]?.displayName || '未记录' }}<span v-if="state?.publishers?.[v]">（{{ state.publishers[v].username }}）</span></p><div class="section-toolbar"><el-button v-if="canRollback" v-for="v in state?.history || []" :key="v" :disabled="v===state?.active.version || busy" @click="rollback(v)">回滚到 {{ v===0?'内置默认':'#'+v }}</el-button></div><el-collapse v-if="!readOnly"><el-collapse-item title="存储状态与接入说明" name="storage"><p class="muted">图片存放在微信云托管对象存储，后台自动刷新临时凭证。COS_BUCKET 与 COS_REGION 配齐只代表参数已配置，实际读写仍需检查。云调用需放行 /_/cos/getauth 和 /_/cos/metaid/encode。机器人编号 8 保留。</p><el-button :disabled="!state?.storageReady || busy" @click="checkStorage">检查临时凭证</el-button></el-collapse-item></el-collapse></section>
  </div>
</template>
