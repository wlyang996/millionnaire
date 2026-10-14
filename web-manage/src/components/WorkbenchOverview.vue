<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowUpRight, ArrowLeft, ArrowRight, Star, Gamepad2, UsersRound, Music2, SlidersHorizontal, RefreshCw, LayoutDashboard, List, Clock3, Sparkles } from '@lucide/vue'
import { call } from '../api.js'
import { can, pageVisible } from '../admin/accessPolicy.js'
import { pageById } from '../admin/navigation.js'
import { useWorkbench } from '../admin/useWorkbench.js'
import SpotlightCard from './effects/SpotlightCard.vue'
import NumberTicker from './effects/NumberTicker.vue'
import BorderBeam from './effects/BorderBeam.vue'
const props = defineProps({ identity:Object, active:Object, data:Object, days:Number, loading:Boolean, dirty:Boolean, characterDirty:Boolean, audioDirty:Boolean, changeCount:Number })
const emit = defineEmits(['navigate','refresh','error'])
const { favorites, recent, layout, toggle, move, setLayout } = useWorkbench()
const characters = ref(null), audio = ref(null), moduleErrors = ref({}), moduleLoading = ref(false)
const publishedConfig = ref(null), publishedHistory = ref(null)
const expanded = ref(false)
let generation = 0
async function loadModules() {
  const current = ++generation
  characters.value = null; audio.value = null; publishedConfig.value = null; publishedHistory.value = null; moduleErrors.value = {}; moduleLoading.value = true
  const jobs = [['characters','character:read','character:draft',characters], ['audio','audio:read','audio:draft',audio], ['room','config:read',null,publishedConfig,'/admin-api/v1/config/active'], ['history','config:read',null,publishedHistory,'/admin-api/v1/config/history']]
  await Promise.allSettled(jobs.map(async ([name,permission,draftPermission,target,path]) => {
    if (!can(props.identity, permission)) return
    try {
      const result = await call('GET', path || `/admin-api/v1/${name}${can(props.identity,draftPermission) ? '' : '/published'}`)
      if (current === generation) target.value = result
    } catch (error) {
      if (current !== generation) return
      moduleErrors.value = {...moduleErrors.value, [name]:error.message || '读取失败'}
      if ([401,403].includes(error.status)) emit('error', error)
    }
  }))
  if (current === generation) moduleLoading.value = false
}
watch(() => `${props.identity?.user?.id}:${props.identity?.permissions?.join(',')}`, loadModules, {immediate:true})
onBeforeUnmount(() => { generation++ })
function refresh() { emit('refresh'); loadModules() }
const version = id => id == null ? '尚未读取' : id === 0 ? '内置默认' : `版本 #${id}`
const modules = computed(() => [
  {id:'room', label:'玩法配置', permission:'config:read', icon:SlidersHorizontal, tone:'violet', version:publishedConfig.value?.configId, description:'地图、经济与玩法规则', status:publishedConfig.value && props.active && publishedConfig.value.configId!==props.active.configId ? '生效版本已更新，编辑区保留原草稿' : props.dirty ? `${props.changeCount} 项本次编辑待发布` : props.active ? '本次编辑与载入版本一致' : '等待读取配置'},
  {id:'characters', label:'人物工作室', permission:'character:read', icon:UsersRound, tone:'cyan', version:characters.value?.active?.version, description:`${characters.value?.active?.characters?.length ?? '—'} 位生效人物`, status:props.characterDirty ? '人物资料有未保存修改' : can(props.identity,'character:draft') && characters.value ? `${characters.value.packs?.filter(pack=>!pack.published).length ?? 0} 套服务端素材草稿` : characters.value ? '查看生效人物目录' : '等待读取目录'},
  {id:'audio', label:'声音工作室', permission:'audio:read', icon:Music2, tone:'rose', version:audio.value?.current?.version, description:'背景音乐与独立游戏音效', status:props.audioDirty ? '本次音频编辑待发布' : can(props.identity,'audio:draft') && audio.value && JSON.stringify(audio.value.draft?.settings)!==JSON.stringify(audio.value.current.settings) ? '服务端音频草稿待发布' : audio.value ? '查看生效声音配置' : '等待读取配置'}
].filter(module=>can(props.identity,module.permission)))
const shortcuts = computed(() => ['events','names','characters','audio','announcement','history','accounts','audit'].filter(id=>pageVisible(props.identity,id)))
const visits = computed(() => recent.value.filter(id=>id!=='workbench').slice(0,5))
const pending = computed(() => [[props.dirty,'room','玩法参数有编辑修改'],[props.characterDirty,'characters','人物资料有未保存修改'],[props.audioDirty,'audio','音频编辑尚未发布']].filter(([dirty,id])=>dirty && pageVisible(props.identity,id)))
const daily = computed(() => (props.data?.dbEnabled ? props.data.daily || [] : []).slice(-7))
const maxGames = computed(() => Math.max(1,...daily.value.map(day=>day.games || 0)))
const releaseHistory = computed(() => publishedHistory.value || [])
const metrics = [{key:'games',label:'对局',unit:'局'}, {key:'players',label:'参与玩家',unit:'人次'}, {key:'newUsers',label:'新用户',unit:'人'}]
function date(value) { return value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '时间未记录' }
</script>
<template>
  <div class="workbench" :class="`layout-${layout}`">
    <div class="workbench-toolbar"><span class="muted">{{ identity?.user?.displayName }}的工作空间 · 偏好保存在本机</span><div><el-radio-group :model-value="layout" aria-label="工作台布局" @update:model-value="setLayout"><el-radio-button value="panorama"><LayoutDashboard :size="14" />全景</el-radio-button><el-radio-button value="focus"><List :size="14" />聚焦</el-radio-button></el-radio-group><el-button :loading="loading || moduleLoading" @click="refresh"><RefreshCw :size="14" />刷新概览</el-button></div></div>
    <div class="workbench-grid">
      <section class="workbench-launch surface">
        <div class="launch-copy"><span class="eyebrow">YOUR NEXT MOVE</span><h3>把灵感，变成下一局。</h3><p>玩法、人物、声音，随时抵达。</p><div class="launch-actions"><button v-for="id in shortcuts.slice(0,4)" :key="id" type="button" @click="emit('navigate',id)">{{ pageById[id].label }}<ArrowUpRight :size="16" /></button></div></div>
        <div class="console-orbit" aria-hidden="true"><div class="orbit-track track-one" /><div class="orbit-track track-two" /><div class="orbit-core"><Gamepad2 :size="42" /></div><span class="orbit-node node-one"><SlidersHorizontal :size="18" /></span><span class="orbit-node node-two"><UsersRound :size="18" /></span><span class="orbit-node node-three"><Music2 :size="18" /></span></div><BorderBeam />
      </section>
      <section class="workbench-pending surface"><div class="surface-heading"><h3><Sparkles :size="18" />编辑提醒</h3><span class="workbench-count">{{ pending.length }}</span></div><template v-if="pending.length"><button v-for="[,id,label] in pending" :key="id" class="pending-link" @click="emit('navigate',id)"><span class="status-dot" />{{ label }}<ArrowUpRight :size="14" /></button></template><div v-else class="pending-clear"><span class="pending-check">✓</span><strong>当前会话没有编辑中的修改</strong><p>服务端素材草稿见下方工作室状态。</p></div></section>
      <SpotlightCard v-for="module in modules" :key="module.id" class="workbench-module" :class="`module-${module.tone}`" tilt><div class="module-body"><div class="module-heading"><span class="module-icon"><component :is="module.icon" :size="22" /></span><span class="module-version">{{ version(module.version) }}</span></div><h3>{{ module.label }}</h3><p>{{ module.description }}</p><p v-if="moduleErrors[module.id]" class="module-error" role="status">读取失败：{{ moduleErrors[module.id] }} <button @click="loadModules">重试</button></p><small v-else>{{ module.status }}</small><button class="module-enter" @click="emit('navigate',module.id)">进入工作室<ArrowUpRight :size="17" /></button></div></SpotlightCard>
      <section class="surface workbench-stats"><div class="surface-heading"><h3>近 {{ days }} 天 · 游戏脉搏</h3><button class="text-button" @click="emit('navigate','dashboard')">完整数据看板<ArrowUpRight :size="14" /></button></div><p class="muted" v-if="data">{{ data.from }} ～ {{ data.to }} · 北京时间</p><div class="pulse-metrics"><div v-for="metric in metrics" :key="metric.key"><small>{{ metric.label }}</small><strong><NumberTicker :value="data?.dbEnabled ? data.totals?.[metric.key] : undefined" /><span>{{ metric.unit }}</span></strong></div></div><div v-if="data?.dbEnabled && daily.length" class="pulse-chart" role="img" :aria-label="'最近七天每日对局：'+daily.map(day=>day.date+' '+day.games+'局').join('；')"><div v-for="day in daily" :key="day.date" class="pulse-day" :class="{peak:day.games>0 && day.games===maxGames}"><small>{{ day.games }}局</small><div class="pulse-bar-track"><span :style="{height:`${(day.games || 0)/maxGames*100}%`}" /></div><small>{{ day.date.slice(5) }}</small></div></div><p v-else class="muted" role="status">{{ loading ? '正在读取真实统计…' : data && !data.dbEnabled ? '未连接数据库，暂无可统计数据' : '统计暂不可用，请刷新重试' }}</p><p v-if="daily.length" class="pulse-caption">最近七天对局（局）· 金色为最高日 · 来源：后台统计接口</p></section>
      <section class="surface workbench-favorites"><div class="surface-heading"><h3><Star :size="18" />我的常用入口</h3><span class="muted">{{ favorites.length }} / 12</span></div><TransitionGroup name="favorite-order" tag="div" class="favorite-grid"><div v-for="(id,index) in favorites" :key="id" class="favorite-tile" :data-tone="pageById[id].icon"><button class="favorite-open" @click="emit('navigate',id)"><span>{{ pageById[id].group }}</span><strong>{{ pageById[id].label }}<ArrowUpRight :size="15" /></strong></button><div class="favorite-controls"><button :disabled="index===0" :aria-label="'前移'+pageById[id].label" @click="move(id,-1)"><ArrowLeft :size="13" /></button><button :disabled="index===favorites.length-1" :aria-label="'后移'+pageById[id].label" @click="move(id,1)"><ArrowRight :size="13" /></button><button :aria-label="'取消收藏'+pageById[id].label" @click="toggle(id)"><Star :size="13" fill="currentColor" /></button></div></div></TransitionGroup><p v-if="!favorites.length" class="muted">点击页面右上角的星标，添加常用入口。</p><details class="shortcut-picker"><summary>添加常用入口</summary><div><button v-for="id in shortcuts" :key="id" :aria-pressed="favorites.includes(id)" @click="toggle(id)"><Star :size="12" :fill="favorites.includes(id)?'currentColor':'none'" />{{ pageById[id].label }}</button></div></details></section>
      <section class="surface workbench-recent"><div class="surface-heading"><h3><Clock3 :size="18" />最近访问</h3></div><button v-for="id in visits" :key="id" class="recent-link" @click="emit('navigate',id)"><span>{{ pageById[id].label }}<small>{{ pageById[id].group }}</small></span><ArrowUpRight :size="16" /></button><p v-if="!visits.length" class="muted">开始访问页面后，会在这里留下快捷入口。</p><div class="workbench-keytip"><kbd>Ctrl K</kbd><span>随时打开全局命令面板<br />也支持 macOS 的 ⌘ K</span></div></section>
      <section v-if="can(identity,'config:read')" class="surface workbench-releases"><div class="surface-heading"><h3>玩法发布动态</h3><button class="text-button" :aria-expanded="expanded" @click="expanded=!expanded">{{ expanded ? '收起记录' : '展开记录' }}</button></div><ol class="release-feed"><li v-for="release in releaseHistory.slice(0,expanded?8:3)" :key="release.configId"><span class="release-dot" :class="{active:release.configId===publishedConfig?.configId}" /><div><strong>{{ version(release.configId) }}<small v-if="release.configId===publishedConfig?.configId">当前生效版本</small></strong><p>{{ release.note || '未填写发布说明' }}</p><small>{{ date(release.createdAt) }} · {{ release.createdBy || '发布人未记录' }}</small></div></li></ol><p v-if="!releaseHistory.length" class="muted">{{ moduleErrors.history ? '发布记录读取失败，请刷新重试' : '尚无发布记录' }}</p><button class="text-button" @click="emit('navigate','history')">进入历史版本对比<ArrowUpRight :size="14" /></button></section>
    </div>
  </div>
</template>
