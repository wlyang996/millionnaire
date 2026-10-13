<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { call } from '../api.js'
import { compareVersions, versionName } from '../admin/versionComparison.js'
import VersionDiff from './VersionDiff.vue'
import BorderBeam from './effects/BorderBeam.vue'
import { motionEnabled } from '../admin/preferences.js'
import { historyQuery, publisherName, publicationType, sourceName } from '../admin/versionHistory.js'

const props=defineProps({focusVersion:Number,canEdit:Boolean,canRollback:Boolean,active:Object,history:{type:Array,default:()=>[]},dirty:Boolean})
const emit=defineEmits(['view','rolledback','error'])
const beforeId=ref(null),afterId=ref(null),comparison=ref(null),compareBusy=ref(false),compareError=ref('')
const comparisonArea=ref(null)
const rollbackOpen=ref(false),rollbackBusy=ref(false),rollbackPreparing=ref(false),rollbackReview=ref(null),rollbackError=ref(''),rollbackStale=ref(false),rollbackTarget=ref(null)
let requestId=0
let historyRequestId=0, optionRequestId=0
const historyRows=ref([]),knownVersions=ref([]),suggestions=ref([]),historyTotal=ref(0),historyPage=ref(1),historySize=ref(20)
const historyBusy=ref(false),historyError=ref(''),historyActiveId=ref(props.active?.configId),optionBusy=ref(false)
const searchInput=ref(''),searchQuery=ref(''),historyType=ref('all'),historyStatus=ref('all')
const optionQuery=ref('')
const options=computed(()=>{
  const versions=new Map((optionQuery.value?[]:props.history).map(row=>[row.configId,row]))
  for (const row of [...(optionQuery.value?[]:historyRows.value),...knownVersions.value,...suggestions.value]) versions.set(row.configId,row)
  if (!optionQuery.value&&props.active) versions.set(props.active.configId,{...props.active,active:true})
  if (!optionQuery.value&&!versions.has(0)) versions.set(0,{configId:0,note:'内置默认配置'})
  return [...versions.values()].sort((a,b)=>b.configId-a.configId)
})
const groups=computed(()=>comparison.value?compareVersions(comparison.value.before.settings,comparison.value.after.settings):[])
const rollbackGroups=computed(()=>rollbackReview.value?compareVersions(rollbackReview.value.before.settings,rollbackReview.value.after.settings):[])
const selectedName=id=>versionName(options.value.find(row=>row.configId===id))
const optionLabel=row=>versionName(row)+(row.configId===historyActiveId.value?' · 生效中':'')
const time=ms=>ms?new Date(ms).toLocaleString('zh-CN',{hour12:false}):'—'
function report(error) { if (error.status===401) emit('error',error) }
async function loadHistory() {
  const ticket=++historyRequestId
  historyBusy.value=true; historyError.value=''; historyRows.value=[]
  try {
    const result=await call('GET',historyQuery({page:historyPage.value,size:historySize.value,q:searchQuery.value,type:historyType.value,status:historyStatus.value}))
    if (ticket!==historyRequestId) return
    historyRows.value=result.items; historyTotal.value=result.total; historyActiveId.value=result.activeId
    const last=Math.max(1,Math.ceil(result.total/historySize.value))
    if (historyPage.value>last) { historyPage.value=last; loadHistory() }
  } catch (error) { if (ticket===historyRequestId) { historyError.value=error.message||'读取发布记录失败'; historyTotal.value=0; report(error) } }
  finally { if (ticket===historyRequestId) historyBusy.value=false }
}
function filterHistory() { searchQuery.value=searchInput.value.trim(); historyPage.value=1; loadHistory() }
function resetHistory() { searchInput.value=''; searchQuery.value=''; historyType.value='all'; historyStatus.value='all'; historyPage.value=1; loadHistory() }
async function searchOptions(query) {
  const ticket=++optionRequestId; optionQuery.value=query.trim(); optionBusy.value=true
  try {
    const result=await call('GET',historyQuery({q:query,size:20}))
    if (ticket===optionRequestId) suggestions.value=result.items
  } catch (error) { if (ticket===optionRequestId) { suggestions.value=[]; ElMessage.error(error.message||'搜索版本失败'); report(error) } }
  finally { if (ticket===optionRequestId) optionBusy.value=false }
}
async function readComparison() {
  const ticket=++requestId
  comparison.value=null; compareError.value=''
  if (beforeId.value===null || afterId.value===null) { compareBusy.value=false; return }
  const left=beforeId.value,right=afterId.value
  compareBusy.value=true
  try {
    const before=await call('GET','/admin-api/v1/config/'+left)
    const after=left===right?before:await call('GET','/admin-api/v1/config/'+right)
    if (ticket===requestId) { comparison.value={before,after}; knownVersions.value=[before,after] }
  } catch (error) { if (ticket===requestId) { compareError.value=error.message||'读取版本失败'; report(error) } }
  finally { if (ticket===requestId) compareBusy.value=false }
}
function compareRow(row) { knownVersions.value=[...knownVersions.value,row]; beforeId.value=row.configId; afterId.value=historyActiveId.value ?? props.active.configId; readComparison(); comparisonArea.value?.scrollIntoView({block:'start',behavior:motionEnabled.value?'smooth':'auto'}) }
function compareSource(row) { knownVersions.value=[...knownVersions.value,row]; beforeId.value=row.sourceConfigId; afterId.value=row.configId; readComparison(); comparisonArea.value?.scrollIntoView({block:'start',behavior:motionEnabled.value?'smooth':'auto'}) }
function swapVersions() { const old=beforeId.value; beforeId.value=afterId.value; afterId.value=old; readComparison() }
function initialize() {
  historyActiveId.value=props.active?.configId
  beforeId.value=props.focusVersion ?? options.value.find(row=>row.configId!==props.active?.configId)?.configId ?? props.active?.configId ?? 0
  afterId.value=props.active?.configId ?? 0
  readComparison()
  historyPage.value=1; loadHistory()
}
watch([()=>props.active?.configId,()=>props.history,()=>props.focusVersion],initialize)
onMounted(initialize)
onBeforeUnmount(()=>{ requestId++; historyRequestId++; optionRequestId++ })
async function prepareRollback(row) {
  if(!props.canRollback)return
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
      <div class="version-selectors"><label>对比基准<el-select v-model="beforeId" filterable remote :remote-method="searchOptions" :loading="optionBusy" aria-label="对比基准版本" placeholder="输入版本号、说明或发布人" @change="readComparison"><el-option v-for="row in options" :key="row.configId" :label="optionLabel(row)" :value="row.configId" /></el-select></label><span class="version-arrow" aria-hidden="true">→</span><label>目标版本<el-select v-model="afterId" filterable remote :remote-method="searchOptions" :loading="optionBusy" aria-label="对比目标版本" placeholder="输入版本号、说明或发布人" @change="readComparison"><el-option v-for="row in options" :key="row.configId" :label="optionLabel(row)" :value="row.configId" /></el-select></label><el-button :disabled="compareBusy" @click="swapVersions">交换版本</el-button></div>
      <el-alert v-if="compareError" :title="compareError" type="error" :closable="false"><el-button size="small" @click="readComparison">重新读取</el-button></el-alert>
      <div v-if="compareBusy" class="version-loading" role="status">正在读取版本内容…</div>
      <VersionDiff v-else-if="comparison" :key="beforeId+':'+afterId" :groups="groups" :before-label="selectedName(beforeId)" :after-label="selectedName(afterId)" />
    </section>
    <div class="field-panel-title"><span>发布记录</span><small>回滚会以历史内容发布一个新版本</small></div>
    <div class="history-filters"><el-input v-model="searchInput" aria-label="搜索发布记录" placeholder="版本号、发布说明或发布人" maxlength="100" clearable @keyup.enter="filterHistory" @clear="filterHistory" /><el-select v-model="historyType" aria-label="发布类型" @change="filterHistory"><el-option label="全部发布类型" value="all" /><el-option label="普通发布" value="publish" /><el-option label="回滚发布" value="rollback" /><el-option label="内置默认" value="default" /></el-select><el-select v-model="historyStatus" aria-label="版本状态" @change="filterHistory"><el-option label="全部状态" value="all" /><el-option label="生效中" value="active" /><el-option label="历史版本" value="inactive" /></el-select><el-button @click="filterHistory">查询</el-button><el-button @click="resetHistory">重置筛选</el-button><el-button :loading="historyBusy" @click="loadHistory">刷新记录</el-button></div>
    <el-alert v-if="historyActiveId!==active?.configId" type="info" :closable="false" :title="'记录中的生效版本已变为 #'+historyActiveId+'；编辑区仍保留原基准，发布时会校验版本冲突。'" />
    <el-alert v-if="historyError" :title="historyError" type="error" :closable="false"><el-button size="small" @click="loadHistory">重试发布记录</el-button></el-alert>
    <div v-if="historyBusy" class="version-loading" role="status">正在读取发布记录…</div>
    <el-empty v-else-if="!historyRows.length&&!historyError" description="没有符合筛选条件的发布记录" :image-size="64" />
    <el-timeline v-else-if="!historyError" class="version-timeline"><el-timeline-item v-for="row in historyRows" :key="row.configId" :timestamp="time(row.createdAt)" placement="top" :type="row.active?'primary':'info'"><div class="version-card"><div class="surface-heading"><h3>{{ versionName(row) }}</h3><el-tag v-if="row.active" type="success">生效中</el-tag><el-tag v-else type="info">历史版本</el-tag></div><p>{{ row.note || '暂无版本说明' }}</p><dl class="version-metadata"><div><dt>发布人</dt><dd>{{ publisherName(row) }}</dd></div><div><dt>发布类型</dt><dd>{{ publicationType(row) }}</dd></div><div v-if="row.sourceConfigId!=null"><dt>回滚来源</dt><dd>{{ sourceName(row) }}</dd></div><div class="version-hash"><dt>规则标识</dt><dd>{{ row.ruleHash || '未记录' }}</dd></div></dl><div class="section-toolbar"><el-button @click="compareRow(row)">与生效版本对比</el-button><el-button v-if="row.sourceConfigId!=null" @click="compareSource(row)">对比回滚来源</el-button><el-button v-if="canEdit" @click="emit('view',row)">载入编辑</el-button><el-button v-if="canRollback && !row.active" type="warning" :disabled="rollbackPreparing||rollbackBusy" @click="prepareRollback(row)">回滚到此</el-button></div><BorderBeam v-if="row.active" /></div></el-timeline-item></el-timeline>
    <el-pagination v-if="!historyError" v-model:current-page="historyPage" v-model:page-size="historySize" class="history-pagination" :total="historyTotal" :page-sizes="[10,20,50]" :pager-count="5" layout="total, sizes, prev, pager, next" :disabled="historyBusy" @current-change="loadHistory" @size-change="historyPage=1; loadHistory()" />
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
