<script setup>
import { computed, defineAsyncComponent } from 'vue'
import { RefreshCw, ArrowUpRight, Gamepad2, UsersRound, UserPlus, Timer, Users } from '@lucide/vue'
const AdminChart = defineAsyncComponent(() => import('./AdminChart.vue'))
import SpotlightCard from './effects/SpotlightCard.vue'
import NumberTicker from './effects/NumberTicker.vue'
const props = defineProps({ data: Object, loading: Boolean, days: Number, labels: Object, miscLabels: Array })
const emit = defineEmits(['refresh', 'update:days'])
const metrics = [{ key: 'games', label: '对局数', unit: '局', icon: Gamepad2 }, { key: 'players', label: '参与玩家', unit: '人次', icon: UsersRound }, { key: 'newUsers', label: '新用户', unit: '人', icon: UserPlus }, { key: 'avgMinutes', label: '平均时长', unit: '分钟', icon: Timer, decimals: 1 }, { key: 'avgPlayers', label: '平均人数', unit: '人 / 局', icon: Users, decimals: 1 }]
const dailyOption = computed(() => ({ legend: { data: ['对局数', '参与玩家'], bottom: 0 }, grid: { top: 35, left: 50, right: 55, bottom: 55 }, xAxis: { type: 'category', name: '日期', data: (props.data?.daily || []).map(row => row.date.slice(5)) }, yAxis: [{ type: 'value', name: '对局（局）' }, { type: 'value', name: '参与（人次）', splitLine: { show: false } }], series: [{ name: '对局数', type: 'bar', barMaxWidth: 24, itemStyle: { borderRadius: [7,7,0,0] }, data: (props.data?.daily || []).map(row => row.games) }, { name: '参与玩家', type: 'line', smooth: true, yAxisIndex: 1, symbolSize: 7, data: (props.data?.daily || []).map(row => row.players) }] }))
const distributions = computed(() => [
  ['结束方式', props.data?.endModes, props.labels?.modes], ['结束原因', props.data?.reasons, props.labels?.reasons], ['地图', props.data?.boards, props.labels?.boards], ['人数', props.data?.playerCounts], ['抽卡事件结果', props.data?.events?.drawnEvents, props.labels?.events], ['幸运 / 不幸格结果', props.data?.events?.luckyEvents, props.labels?.events], ['道具使用', props.data?.events?.cardsUsed, props.labels?.cards],
].map(([title, values, labels]) => ({ title, rows: Object.entries(values || {}).map(([key, value]) => ({ name: labels?.[key] || key, value })).sort((a,b) => b.value-a.value) })))
function distributionOption(rows) { return { legend: { type: 'scroll', bottom: 0 }, series: [{ type: 'pie', radius: ['45%', '68%'], center: ['50%', '43%'], label: { show: false }, emphasis: { label: { show: true, formatter: '{b}\n{d}%' } }, data: rows }] } }
</script>
<template>
  <div class="dashboard-overview" v-loading="loading">
    <div class="section-toolbar"><el-radio-group :model-value="days" @update:model-value="emit('update:days', $event)"><el-radio-button v-for="n in [7,14,30]" :key="n" :value="n">近 {{ n }} 天</el-radio-button></el-radio-group><span class="muted" v-if="data">{{ data.from }} ～ {{ data.to }}（北京时间）</span><el-button :loading="loading" @click="emit('refresh')"><RefreshCw :size="14" />刷新</el-button></div>
    <el-alert v-if="data && !data.dbEnabled" title="后台未连接数据库，暂无可统计的数据" type="warning" :closable="false" />
    <el-empty v-else-if="!data && !loading" description="暂时无法读取统计数据，请点击刷新重试" />
    <template v-else-if="data?.dbEnabled">
      <div class="metric-grid"><SpotlightCard v-for="metric in metrics" :key="metric.key"><div class="metric-card"><div class="metric-label"><span>{{ metric.label }}</span><component :is="metric.icon" :size="18" /></div><div class="metric-value"><NumberTicker :value="data.totals[metric.key]" :decimals="metric.decimals || 0" /><small>{{ metric.unit }}</small></div><span class="metric-caption">所选时间范围<ArrowUpRight :size="13" /></span></div></SpotlightCard></div>
      <section class="surface"><div class="surface-heading"><h3>每天对局与玩家参与</h3><span class="muted">来源：后台统计接口</span></div><AdminChart v-if="data.daily?.length" :option="dailyOption" label="每天对局数和玩家参与人次" :height="340" /><el-empty v-else description="这个时间范围还没有对局" /><el-collapse><el-collapse-item title="查看每天的详细数据" name="daily"><el-table :data="data.daily" size="small" max-height="320"><el-table-column prop="date" label="日期" min-width="120" /><el-table-column prop="games" label="对局数" /><el-table-column prop="players" label="参与玩家" /><el-table-column prop="newUsers" label="新用户" /><el-table-column label="平均时长（分钟）"><template #default="{ row }">{{ row.avgMinutes ?? '—' }}</template></el-table-column></el-table></el-collapse-item></el-collapse></section>
      <div class="distribution-grid"><section v-for="block in distributions" :key="block.title" class="surface"><h3>{{ block.title }}</h3><AdminChart v-if="block.rows.length" :option="distributionOption(block.rows)" :label="block.title + '分布，次数与占比'" :height="260" /><el-empty v-else :image-size="65" description="暂无数据" /></section></div>
      <section class="surface"><h3>玩法行为统计</h3><div class="behavior-grid"><div v-for="[key,label] in miscLabels" :key="key" class="behavior-item"><span>{{ label }}</span><strong>{{ data.events?.[key] ?? '—' }}</strong></div></div><p class="muted">事件类统计来自最近 {{ data.events?.logsScanned }} 局日志（最多 500 局）。</p></section>
    </template>
  </div>
</template>
