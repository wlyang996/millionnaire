<script setup>
import { computed, defineAsyncComponent } from 'vue'
import { CircleCheck, CircleAlert, Sparkles, Gem, Clover, CloudLightning } from '@lucide/vue'
import { probabilityPools } from '../admin/ruleVisuals.js'
const AdminChart = defineAsyncComponent(() => import('./AdminChart.vue'))
const props = defineProps({ settings: Object, labels: Object })
const pools = computed(() => probabilityPools(props.settings, props.labels))
const icons = { events: Sparkles, cards: Gem, lucky: Clover, unlucky: CloudLightning }
const poolTones={events:'cyan',cards:'violet',lucky:'green',unlucky:'rose'}
function option(pool) {
  return { legend: { show: false }, series: [{ name: pool.title, type: 'pie', radius: ['52%', '78%'], center: ['50%', '50%'], label: { show: false }, emphasis: { scaleSize: 5 }, data: pool.rows.filter(row => row.weight > 0).map(row => ({ name: row.name, value: row.weight, itemStyle: { color: row.color, borderRadius: 4 } })) }] }
}
</script>
<template>
  <section class="rule-preview probability-preview" aria-label="四组概率实时预览">
    <div class="preview-heading"><span class="eyebrow">LIVE PROBABILITY</span><h3>每一个奖池，独立计算</h3><p class="muted">修改权重即可联动；显示相对占比，发布前仍需通过服务端校验。</p></div>
    <div class="probability-grid">
      <article v-for="pool in pools" :key="pool.id" class="surface probability-card" :data-pool="pool.id" :data-control-tone="poolTones[pool.id]">
        <div class="surface-heading"><h3><component :is="icons[pool.id]" :size="18" />{{ pool.title }}</h3><span class="pool-state" :class="{ invalid: !pool.valid }"><CircleCheck v-if="pool.valid" :size="15" /><CircleAlert v-else :size="15" />合计 {{ pool.total }}</span></div>
        <div class="probability-ring"><AdminChart v-if="pool.total > 0" :option="option(pool)" :label="pool.title + '权重与占比'" :height="210" /><div v-else class="empty-ring" /><div class="ring-caption" aria-hidden="true"><strong>{{ pool.rows.filter(row => row.weight > 0).length }}</strong><small>种可抽结果</small></div></div>
        <p class="pool-message" :class="{ invalid: !pool.valid }">{{ pool.message }}</p>
        <ul class="probability-legend"><li v-for="row in pool.rows" :key="row.key" :class="{ 'zero-weight': row.weight === 0 }"><span class="color-dot" :style="{ background: row.color }" /><span>{{ row.name }}<small v-if="row.amount && ['CASH_REWARD','CASH_FINE'].includes(row.key)">{{ row.key === 'CASH_FINE' ? '−' : '+' }}{{ row.amount }}</small></span><strong>{{ row.percent.toFixed(1) }}%</strong><small>权重 {{ Number.isFinite(row.weight) ? row.weight : '未填写' }}</small></li></ul>
      </article>
    </div>
  </section>
</template>
