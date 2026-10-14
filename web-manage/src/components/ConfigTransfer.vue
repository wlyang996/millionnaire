<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { call } from '../api.js'
import { readBackup, backupConfig, downloadJson, MAX_CONFIG_BYTES } from '../admin/configFiles.js'
import { compareVersions } from '../admin/versionComparison.js'
import VersionDiff from './VersionDiff.vue'
const props=defineProps({settings:Object,referenceId:Number,canEdit:Boolean,candidate:Object,storageStatus:String,dirty:Boolean})
const emit=defineEmits(['apply','discard-local','prepare-export','error'])
const input=ref(null),open=ref(false),pending=ref(null),busy=ref(false),origin=ref(''),validation=ref([])
const diff=computed(()=>compareVersions(props.settings || {},pending.value?.settings || {}))
const inputNames={initialCashOptions:'初始现金选项',timeLimitMinutesOptions:'限时时长选项',rollSecondsOptions:'投骰时限选项',teamQuickAmounts:'援助快捷金额'}
const pendingInputs=computed(()=>Object.entries(pending.value?.inputs || {}).map(([key,value])=>({name:inputNames[key],value})))
let generation=0
async function preview(value,label) {
  const request=++generation
  pending.value=value;origin.value=label;validation.value=[];open.value=true
  if(!props.canEdit)return
  busy.value=true
  try{const result=await call('POST','/admin-api/v1/config/validate',value.settings);if(request===generation)validation.value=result.errors || []}catch(error){if(request===generation){validation.value=[error.message || '暂时无法校验配置'];emit('error',error)}}finally{if(request===generation)busy.value=false}
}
async function fileSelected(event) {
  const file=event.target.files?.[0];event.target.value='';if(!file)return
  try{if(file.size>MAX_CONFIG_BYTES)throw new Error('配置文件不可超过2MB');await preview(readBackup(await file.text()),'导入：'+file.name)}catch(error){ElMessage.error(error.message)}
}
function apply(){if(!props.canEdit || busy.value || validation.value.length || !pending.value)return;emit('apply',pending.value);open.value=false}
function exportRecovered(){if(props.candidate)downloadJson(backupConfig(props.candidate.settings,props.candidate.baseVersion,'恢复用的本机草稿；未发布输入框文字另由本机草稿保存'),`rule-local-draft-${props.candidate.baseVersion}.json`)}
onBeforeUnmount(()=>{generation++})
defineExpose({exportSettings(settings){downloadJson(backupConfig(settings,props.referenceId,props.dirty?'编辑草稿':'生效配置'),`rule-${props.referenceId ?? 'draft'}-${new Date().toISOString().slice(0,10)}.json`)}})
</script>
<template>
  <section class="surface config-transfer"><div class="surface-heading"><h3>规则草稿与备份</h3><div class="transfer-actions"><el-button @click="emit('prepare-export')">导出{{dirty?'编辑草稿':'当前配置'}}</el-button><el-button v-if="canEdit" @click="input.click()">导入配置为草稿</el-button><input ref="input" hidden type="file" accept="application/json,.json" @change="fileSelected" /></div></div><p class="muted">{{ storageStatus || (canEdit?'编辑自动保存到本机，按后台账号隔离；导入与恢复不会直接发布。':'当前账号可导出生效规则，不可导入或恢复编辑草稿。') }}</p><el-alert v-if="candidate && canEdit" type="warning" :closable="false"><template #title>发现 {{new Date(candidate.updatedAt).toLocaleString('zh-CN')}} 的本机草稿 · 参考版本 #{{candidate.baseVersion}}{{candidate.baseVersion!==referenceId?'，当前版本已变化，恢复前请核对差异。':''}}</template><div class="transfer-actions"><el-button @click="preview(candidate,'恢复本机草稿')">比较并恢复</el-button><el-button @click="exportRecovered">导出本机草稿</el-button><el-button @click="emit('discard-local')">删除本机草稿</el-button></div></el-alert></section>
  <el-dialog v-model="open" :title="origin" width="min(960px,94vw)" append-to-body :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy"><p>以下差异相对当前编辑区。确认后只替换编辑草稿，仍需经过原有发布流程。</p><template v-if="pendingInputs.length"><el-alert title="草稿还包含未提交的输入文字，将原样恢复到输入框。下方配置差异与校验只针对已提交的数值；发布前请先完成输入并校验。" type="info" :closable="false" /><el-table :data="pendingInputs"><el-table-column prop="name" label="输入项" /><el-table-column prop="value" label="待恢复的原始文字" /></el-table></template><VersionDiff :groups="diff" before-label="当前编辑区" after-label="待载入草稿" /><el-alert v-if="validation.length" type="error" :closable="false" title="配置校验未通过"><ul><li v-for="error in validation" :key="error">{{error}}</li></ul></el-alert><template #footer><el-button :disabled="busy" @click="open=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!canEdit || !!validation.length" @click="apply">确认载入草稿</el-button></template></el-dialog>
</template>
<style scoped>.transfer-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.config-transfer .el-alert{margin-top:12px}.config-transfer .el-alert .transfer-actions{margin-top:12px}</style>
