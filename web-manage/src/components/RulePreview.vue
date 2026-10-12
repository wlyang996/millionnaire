<script setup>
import { computed, defineAsyncComponent } from 'vue'
const AdminChart = defineAsyncComponent(() => import('./AdminChart.vue'))
const props = defineProps({ section: String, settings: Object, original: Object, labels: Object, rentSteps: Array })
const tiers = computed(() => (props.settings?.tiers || []).map(value => ({ key: value.tier, label: props.labels?.tiers?.[value.tier] || value.tier, ...value })))
const tierOption = computed(() => ({ legend: { bottom: 0 }, grid: { top: 35, left: 55, right: 25, bottom: 55 }, xAxis: { type: 'category', name: '等级', data: ['未升级','一级','二级','三级'] }, yAxis: { type: 'value', name: '租金（金额）' }, series: tiers.value.map(tier => ({ name: tier.label, type: 'line', data: tier.rent || tier.rents || [], symbolSize: 8, smooth: false })) }))
const probabilityOption = computed(() => ({ legend: { type: 'scroll', bottom: 0 }, series: [{ name: '事件权重', type: 'pie', radius: ['42%','68%'], center: ['50%','43%'], label: { show: false }, data: Object.entries(props.settings?.eventWeights || {}).map(([key,value]) => ({ name: props.labels?.events?.[key] || key, value })) }] }))
const rentOption = computed(() => ({ grid: { top: 35, left: 55, right: 25, bottom: 55 }, xAxis: { type: 'category', name: '轮次', data: ['1', ...(props.rentSteps || []).map(step => String(step.from))] }, yAxis: { type: 'value', name: '倍率（%）', min: 100 }, series: [{ name: '破产模式租金倍率', type: 'line', step: 'start', data: [100,...(props.rentSteps || []).map(step=>step.pct)] }] }))
const sectionLabels = { room:'开局预览',startPick:'出发选择',roundReward:'轮次奖励',cityEvents:'城市事件时间线',funTitles:'结算称号',series:'系列赛积分',team:'组队关系',timing:'操作节奏',money:'金额与费用' }
const badges = computed(() => {
  const s = props.settings
  if (!s) return []
  if (props.section === 'room') return [['初始现金', s.room?.defaultInitialCash],['结束模式',s.room?.defaultEndMode === 'BANKRUPTCY' ? '破产' : '限时'],['默认时长',`${s.room?.defaultTimeLimitMinutes ?? '—'} 分钟`]]
  if (props.section === 'startPick') return Object.entries(s.startPick || {}).map(([key,value])=>[({cashWeight:'现金权重',cardWeight:'道具权重',cashMin:'现金最小值',cashMax:'现金最大值',cashStep:'现金步长'})[key] || '选项', value])
  if (props.section === 'roundReward') return [['状态',s.roundReward?.enabled?'启用':'关闭'],['限时 / 破产奖励轮次',`${s.roundReward?.timeLimitRewardRound} / ${s.roundReward?.bankruptcyRewardRound}`],['收入比例',`${s.roundReward?.rewardPercent}%`],['个人上限',s.roundReward?.perPlayerCap]]
  if (props.section === 'cityEvents') return (s.cityEvents?.pool || []).map(event=>[event.name, event.enabled?`持续 ${event.durationRounds} 轮 · ${event.multiplierPercent}%`:'关闭'])
  if (props.section === 'funTitles') return (s.funTitles?.titles || []).map(title=>[title.name,title.enabled?'启用':'关闭'])
  if (props.section === 'series') return (s.series?.rankPoints || []).map((points,index)=>[`第 ${index+1} 名`,`${points} 分`])
  if (props.section === 'team') return [['组队模式',s.team?.enabled?'开放':'关闭'],['队友免租',s.team?.allyRentWaiver?'开放':'关闭'],['主动援助',s.team?.aidEnabled?'开放':'关闭'],['单笔上限',s.team?.aidMaxAmount],['欠款援助',s.team?.debtAidEnabled?'开放':'关闭']]
  if (props.section === 'money') return [['起点奖励',s.fees?.startReward],['小游戏奖励',s.fees?.miniGameWinReward],['出狱费用',s.fees?.bailCost],['事件金额区间',`${s.eventCash?.min} ～ ${s.eventCash?.max}`]]
  if (props.section === 'timing') return [['选择窗口',`${s.timing?.decisionSeconds} 秒`],['交易回应',`${s.timing?.tradeSeconds} 秒`],['拍卖最长',`${s.timing?.auctionMaxSeconds} 秒`],['每格动画',`${s.timing?.animPerStepMs} 毫秒`]]
  return []
})
</script>
<template>
  <section v-if="section === 'prices'" class="surface rule-preview"><h3>升级租金阶梯</h3><AdminChart :option="tierOption" label="各档地产在不同等级的租金金额" :height="260" /><div class="preview-chips"><span v-for="tier in tiers" :key="tier.key">{{ tier.label }} · 购买 {{ tier.basePrice }}<small v-if="original?.tiers?.find(item => item.tier === tier.key)?.basePrice !== tier.basePrice">原 {{ original?.tiers?.find(item => item.tier === tier.key)?.basePrice }}</small></span></div></section>
  <section v-else-if="section === 'events'" class="surface rule-preview"><h3>事件结果分布</h3><AdminChart :option="probabilityOption" label="当前事件权重与占比" :height="260" /><p class="muted">实际权重合计与合法范围由服务端校验；图表按当前值显示占比。</p></section>
  <section v-else-if="section === 'rentRise'" class="surface rule-preview"><h3>破产模式 · 轮次与倍率</h3><AdminChart :option="rentOption" label="破产模式下轮次对应租金倍率百分比" :height="260" /><p class="muted">限时模式恒为 100%。预览不替代服务端校验。</p></section>
  <section v-else-if="badges.length" class="surface rule-preview"><h3>{{ sectionLabels[section] }}</h3><div class="preview-chips"><span v-for="[name,value] in badges" :key="name"><small>{{ name }}</small><strong>{{ value ?? '未下发' }}</strong></span></div></section>
</template>
