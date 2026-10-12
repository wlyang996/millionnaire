<script setup>
import { computed, defineAsyncComponent } from 'vue'
import ProbabilityPreview from './ProbabilityPreview.vue'
import GameplayPreview from './GameplayPreview.vue'
const AdminChart = defineAsyncComponent(() => import('./AdminChart.vue'))
const props = defineProps({ section: String, settings: Object, original: Object, labels: Object, rentSteps: Array, rewardIncome: Number, timingFields: Array })
const tiers = computed(() => (props.settings?.tiers || []).map(value => ({ key: value.tier, label: props.labels?.tiers?.[value.tier] || value.tier, ...value })))
const tierOption = computed(() => ({ legend: { bottom: 0 }, grid: { top: 35, left: 55, right: 25, bottom: 55 }, xAxis: { type: 'category', name: '等级', data: ['未升级','一级','二级','三级'] }, yAxis: { type: 'value', name: '租金（金额）' }, series: tiers.value.map(tier => ({ name: tier.label, type: 'line', data: tier.rent || tier.rents || [], symbolSize: 8, smooth: false })) }))
const rentOption = computed(() => ({ grid: { top: 35, left: 55, right: 25, bottom: 55 }, xAxis: { type: 'category', name: '轮次', data: ['1', ...(props.rentSteps || []).map(step => String(step.from))] }, yAxis: { type: 'value', name: '倍率（%）', min: 100 }, series: [{ name: '破产模式租金倍率', type: 'line', step: 'start', data: [100,...(props.rentSteps || []).map(step=>step.pct)] }] }))
</script>
<template>
  <section v-if="section === 'prices'" class="surface rule-preview"><h3>升级租金阶梯</h3><AdminChart :option="tierOption" label="各档地产在不同等级的租金金额" :height="260" /><div class="preview-chips"><span v-for="tier in tiers" :key="tier.key">{{ tier.label }} · 购买 {{ tier.basePrice }}<small v-if="original?.tiers?.find(item => item.tier === tier.key)?.basePrice !== tier.basePrice">原 {{ original?.tiers?.find(item => item.tier === tier.key)?.basePrice }}</small></span></div></section>
  <ProbabilityPreview v-else-if="section === 'events'" :settings="settings" :labels="labels" />
  <section v-else-if="section === 'rentRise'" class="surface rule-preview"><h3>破产模式 · 轮次与倍率</h3><AdminChart :option="rentOption" label="破产模式下轮次对应租金倍率百分比" :height="260" /><p class="muted">限时模式恒为 100%。预览不替代服务端校验。</p></section>
  <GameplayPreview v-else :section="section" :settings="settings" :labels="labels" :reward-income="rewardIncome" :timing-fields="timingFields" />
</template>
