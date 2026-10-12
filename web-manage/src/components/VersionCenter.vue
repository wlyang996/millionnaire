<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { call } from '../api.js'
import { compareVersions, versionName } from '../admin/versionComparison.js'
import VersionDiff from './VersionDiff.vue'
import BorderBeam from './effects/BorderBeam.vue'
import { motionEnabled } from '../admin/preferences.js'

const props=defineProps({active:Object,history:{type:Array,default:()=>[]},dirty:Boolean})
const emit=defineEmits(['view','rolledback','error'])
const beforeId=ref(null),afterId=ref(null),comparison=ref(null),compareBusy=ref(false),compareError=ref('')
const comparisonArea=ref(null)
const rollbackOpen=ref(false),rollbackBusy=ref(false),rollbackPreparing=ref(false),rollbackReview=ref(null),rollbackError=ref(''),rollbackStale=ref(false),rollbackTarget=ref(null)
let requestId=0
const options=computed(()=>{
  const versions=new Map(props.history.map(row=>[row.configId,row]))
  if (props.active) versions.set(props.active.configId,{...props.active,active:true})
  if (!versions.has(0)) versions.set(0,{configId:0,note:'内置默认配置'})
  return [...versions.values()].sort((a,b)=>b.configId-a.configId)
})
const groups=computed(()=>comparison.value?compareVersions(comparison.value.before.settings,comparison.value.after.settings):[])
const rollbackGroups=computed(()=>rollbackReview.value?compareVersions(rollbackReview.value.before.settings,rollbackReview.value.after.settings):[])
const selectedName=id=>versionName(options.value.find(row=>row.configId===id))
const optionLabel=row=>versionName(row)+(row.configId===props.active?.configId?' · 生效中':'')
const time=ms=>ms?new Date(ms).toLocaleString('zh-CN',{hour12:false}):'—'
function report(error) { if (error.status===401) emit('error',error) }
async function readComparison() {
  const ticket=++requestId
  comparison.value=null; compareError.value=''
  if (beforeId.value===null || afterId.value===null) { compareBusy.value=false; return }
  const left=beforeId.value,right=afterId.value
  compareBusy.value=true
  try {
    const before=await call('GET','/admin-api/v1/config/'+left)
    const after=left===right?before:await call('GET','/admin-api/v1/config/'+right)
    if (ticket===requestId) comparison.value={before,after}
  } catch (error) { if (ticket===requestId) { compareError.value=error.message||'读取版本失败'; report(error) } }
  finally { if (ticket===requestId) compareBusy.value=false }
}
function compareRow(row) { beforeId.value=row.configId; afterId.value=props.active.configId; readComparison(); comparisonArea.value?.scrollIntoView({block:'start',behavior:motionEnabled.value?'smooth':'auto'}) }
function swapVersions() { const old=beforeId.value; beforeId.value=afterId.value; afterId.value=old; readComparison() }
function initialize() {
  beforeId.value=options.value.find(row=>row.configId!==props.active?.configId)?.configId ?? props.active?.configId ?? 0
  afterId.value=props.active?.configId ?? 0
  readComparison()
}
watch([()=>props.active?.configId,()=>props.history],initialize)
onMounted(initialize)
onBeforeUnmount(()=>{ requestId++ })
async function prepareRollback(row) {
  if (rollbackBusy.value || rollbackPreparing.value) return
  rollbackTarget.value=row; rollbackReview.value=null; rollbackError.value=''; rollbackStale.value=false; rollbackPreparing.value=true; rollbackOpen.value=true
  try {
    const [before,after]=await Promise.all([call('GET','/admin-api/v1/config/active'),call('GET','/admin-api/v1/config/'+row.configId)])
    rollbackReview.value={before,after}
    if (before.configId===after.configId) { rollbackStale.value=true; rollbackError.value='该版本已经生效，无需回滚。' }
  } catch (error) { rollbackError.value=error.message||'读取回滚摘要失败'; report(error) }
  finally { rollbackPreparing.value=false }
}
async function confirmRollback() {
  if (!rollbackReview.value || rollbackBusy.value || rollbackPreparing.value || rollbackStale.value) return
  rollbackBusy.value=true; rollbackError.value=''
  try {
    const result=await call('POST','/admin-api/v1/config/rollback',{configId:rollbackReview.value.after.configId,expectedActiveId:rollbackReview.value.before.configId})
    rollbackOpen.value=false; ElMessage.success('已回滚，生效版本 #'+result.configId); emit('rolledback')
  } catch (error) { rollbackStale.value=error.status===409; rollbackError.value=(error.message||'回滚失败')+(rollbackStale.value?'，请重新读取摘要后确认。':'，未发布的修改仍保留，可重试。'); report(error) }
  finally { rollbackBusy.value=false }
}
</script>

<template>
  <div class="version-center">
    <section ref="comparisonArea" class="version-comparison"><div class="surface-heading"><div><span class="eyebrow">VERSION COMPARE</span><h3>历史版本对比</h3><p class="muted">左侧旧值 → 右侧新值；查看对比会保留编辑区草稿。</p></div></div>
      <div class="version-selectors"><label>对比基准<el-select v-model="beforeId" aria-label="对比基准版本" @change="readComparison"><el-option v-for="row in options" :key="row.configId" :label="optionLabel(row)" :value="row.configId" /></el-select></label><span class="version-arrow" aria-hidden="true">→</span><label>目标版本<el-select v-model="afterId" aria-label="对比目标版本" @change="readComparison"><el-option v-for="row in options" :key="row.configId" :label="optionLabel(row)" :value="row.configId" /></el-select></label><el-button :disabled="compareBusy" @click="swapVersions">交换版本</el-button></div>
      <el-alert v-if="compareError" :title="compareError" type="error" :closable="false"><el-button size="small" @click="readComparison">重新读取</el-button></el-alert>
      <div v-if="compareBusy" class="version-loading" role="status">正在读取版本内容…</div>
      <VersionDiff v-else-if="comparison" :key="beforeId+':'+afterId" :groups="groups" :before-label="selectedName(beforeId)" :after-label="selectedName(afterId)" />
    </section>
    <div class="field-panel-title"><span>发布记录</span><small>回滚会以历史内容发布一个新版本</small></div>
    <el-timeline class="version-timeline"><el-timeline-item v-for="row in options" :key="row.configId" :timestamp="time(row.createdAt)" placement="top" :type="row.configId===active?.configId?'primary':'info'"><div class="version-card"><div class="surface-heading"><h3>{{ versionName(row) }}</h3><el-tag v-if="row.configId===active?.configId" type="success">生效中</el-tag></div><p>{{ row.note || '暂无版本说明' }}</p><div class="section-toolbar"><el-button @click="compareRow(row)">与生效版本对比</el-button><el-button @click="emit('view',row)">载入编辑</el-button><el-button v-if="row.configId!==active?.configId" type="warning" :disabled="rollbackPreparing||rollbackBusy" @click="prepareRollback(row)">回滚到此</el-button></div><BorderBeam v-if="row.configId===active?.configId" /></div></el-timeline-item></el-timeline>
    <el-dialog v-model="rollbackOpen" title="回滚前确认变更" width="min(960px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!rollbackBusy&&!rollbackPreparing" :show-close="!rollbackBusy&&!rollbackPreparing" class="version-dialog">
      <p>将以「{{ versionName(rollbackTarget) }}」的内容发布一个新版本，新开的房间立即生效。</p>
      <el-alert v-if="dirty" type="warning" title="确认回滚后，编辑区未发布的修改会丢失；取消会保留草稿。" :closable="false" />
      <div v-if="rollbackPreparing" class="version-loading" role="status">正在读取当前生效版本与回滚目标…</div>
      <VersionDiff v-else-if="rollbackReview" :key="rollbackReview.before.configId+':'+rollbackReview.after.configId" :groups="rollbackGroups" :before-label="versionName(rollbackReview.before)+'（生效）'" :after-label="versionName(rollbackReview.after)+'（回滚目标）'" />
      <el-alert v-if="rollbackError" :title="rollbackError" type="error" :closable="false" />
      <template #footer><el-button :disabled="rollbackBusy||rollbackPreparing" @click="rollbackOpen=false">取消，保留草稿</el-button><el-button v-if="rollbackStale||!rollbackReview" :loading="rollbackPreparing" :disabled="rollbackBusy" @click="prepareRollback(rollbackTarget)">重新读取摘要</el-button><el-button type="warning" :loading="rollbackBusy" :disabled="!rollbackReview||rollbackPreparing||rollbackStale" @click="confirmRollback">确认回滚并发布新版本</el-button></template>
    </el-dialog>
  </div>
</template>
