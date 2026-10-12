<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { motionEnabled, theme } from '../admin/preferences.js'
import { DEFAULT_CHART_COLORS } from '../admin/chartColors.js'
echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const props = defineProps({ option: Object, label: String, height: { type: Number, default: 300 } })
const container = ref(null)
let chart, observer
function update() {
  if (!chart) return
  const dark = theme.value === 'dark'
  chart.setOption({ ...props.option, animation: motionEnabled.value, animationDuration: 450, animationDurationUpdate: 450, backgroundColor: 'transparent', color: props.option?.color ?? DEFAULT_CHART_COLORS, textStyle: { color: dark ? '#aaa7c3' : '#7c7890', fontFamily: 'Microsoft YaHei, sans-serif' }, tooltip: { trigger: props.option?.series?.[0]?.type === 'pie' ? 'item' : 'axis', backgroundColor: dark ? '#28263c' : '#fff', borderColor: dark ? '#484259' : '#e4e0ef', textStyle: { color: dark ? '#ede9ff' : '#302b47' } } }, { notMerge: true })
}
watch([() => props.option, motionEnabled, theme], update, { deep: true })
onMounted(() => { chart = echarts.init(container.value); observer = new ResizeObserver(() => chart?.resize()); observer.observe(container.value); update() })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose(); chart = null })
const summary = computed(() => (props.option?.series || []).map(series => `${series.name || ''}: ${(series.data || []).map(item => typeof item === 'object' ? `${item.name || ''} ${item.value}` : item).join('、')}`).join('；'))
</script>
<template><div ref="container" class="admin-chart" :style="{ height: height + 'px' }" role="img" :aria-label="label + '。' + summary" /></template>
<style scoped>.admin-chart { width: 100%; min-width: 0; }</style>
