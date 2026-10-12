import test from 'node:test'
import assert from 'node:assert/strict'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { SVGRenderer } from 'echarts/renderers'
import { categoryColor, distributionRows } from '../src/admin/chartColors.js'

test('category colors survive rank changes, missing categories and translated labels', () => {
  const first = distributionRows('reasons', { ALL_AWAY: 80, LAST_SURVIVOR: 20 }, { ALL_AWAY: '全员挂机', LAST_SURVIVOR: '只剩一人' })
  const changed = distributionRows('reasons', { LAST_SURVIVOR: 90, ALL_AWAY: 10 })
  assert.equal(first[0].name, '全员挂机')
  assert.equal(changed[0].name, 'LAST_SURVIVOR')
  assert.equal(first[0].itemStyle.color, changed[1].itemStyle.color)
  assert.equal(first[1].itemStyle.color, changed[0].itemStyle.color)
  assert.equal(distributionRows('reasons', { LAST_SURVIVOR: 1 })[0].itemStyle.color, first[1].itemStyle.color)
  assert.equal(categoryColor('events', 'NEW_EVENT'), distributionRows('events', { NEW_EVENT: 20 })[0].itemStyle.color)
  assert.deepEqual(distributionRows('events', null), [])
})

test('ECharts renders different chart domains in their assigned colors', () => {
  echarts.use([PieChart, SVGRenderer])
  const domains = [['modes', 'TIME_LIMIT'], ['reasons', 'ALL_AWAY'], ['boards', 'classic-30'], ['players', '2人']]
  const colors = domains.map(([domain, key]) => categoryColor(domain, key))
  assert.equal(new Set(colors).size, domains.length)
  for (const [domain, key] of domains) {
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 400, height: 260 })
    try {
      chart.setOption({ animation: false, series: [{ type: 'pie', radius: ['45%', '68%'], data: distributionRows(domain, { [key]: 100 }) }] })
      assert.ok(chart.renderToSVGString().includes(`fill="${categoryColor(domain, key)}"`))
    } finally { chart.dispose() }
  }
})
