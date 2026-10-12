import test from 'node:test'
import assert from 'node:assert/strict'
import { probabilityPools, probabilityPool, cityScenario, cashRange, rewardAmount } from '../src/admin/ruleVisuals.js'

test('four independent probability pools normalize their own denominators and preserve labels', () => {
  const settings = { eventWeights: { CASH_REWARD: 80, CASH_FINE: 20 }, cardWeights: { ROADBLOCK: 120, RENT_WAIVER: 880 }, lucky: [{ kind: 'CASH_REWARD', label: '红包', weight: 25, amount: 200, unlucky: false }, { kind: 'CARD', label: '礼物', weight: 75, unlucky: false }, { kind: 'CASH_FINE', label: '罚款', weight: 5, amount: 100, unlucky: true }, { kind: 'JAIL', label: '入狱', weight: 5, unlucky: true }] }
  const pools = probabilityPools(settings, { cards: { ROADBLOCK: '路障' } })
  assert.equal(pools.length, 4)
  assert.deepEqual(pools.map(pool => pool.total), [100, 1000, 100, 10])
  assert.equal(pools[1].rows[0].percent, 12)
  assert.equal(pools[1].rows[0].name, '路障')
  assert.deepEqual(pools[2].rows.map(row => row.percent), [25, 75])
  assert.deepEqual(pools[3].rows.map(row => row.percent), [50, 50])
  assert.ok(pools.every(pool => pool.valid))
  assert.equal(pools[2].rows[0].amount, 200)
  assert.equal(settings.lucky[0].weight, 25)
})

test('probability previews flag invalid totals and weights without producing NaN percentages', () => {
  for (const weights of [[0, 0], [-1, 10], [NaN, 10], [101, 1], [1.5, 20]]) {
    const pool = probabilityPool('lucky', '幸运', weights.map((weight, index) => ({ key: String(index), weight })))
    assert.equal(pool.valid, false)
    assert.ok(pool.rows.every(row => Number.isFinite(row.percent)))
  }
  assert.equal(probabilityPool('events', '事件', [{ key: 'CARD', weight: 99 }], 100).valid, false)
  assert.equal(probabilityPool('cards', '道具', [{ key: 'BUILD', weight: 999 }], 1000).valid, false)
  assert.equal(probabilityPools({ lucky: [{ kind: 'CARD', weight: null }] })[2].valid, false)
})

test('reward visualization uses integer rounding after the personal cap and honors disabled rewards', () => {
  const config = { enabled: true, rewardPercent: 7, perPlayerCap: 999, roundingUnit: 50 }
  assert.equal(rewardAmount(config, 8010), 550)
  assert.equal(rewardAmount(config, 100000), 950)
  assert.equal(rewardAmount({ ...config, enabled: false }, 8010), 0)
  assert.equal(rewardAmount({ ...config, roundingUnit: 0 }, 8010), 0)
})

test('city timeline ends at the first round after the active interval and excludes disabled weight', () => {
  const event = { kind: 'PROPERTY_RENT', enabled: true, weight: 1, durationRounds: 3 }
  const config = { enabled: true, allowedModes: ['TIME_LIMIT'], firstCheckRound: 5, advanceRounds: 2, triggerPercent: 30, pool: [event, { kind: 'STATION_RENT', enabled: true, weight: 3 }, { kind: 'UPGRADE_DISCOUNT', enabled: false, weight: 100 }] }
  assert.deepEqual(cityScenario(config, event), { available: true, total: 4, share: 25, check: 5, start: 7, last: 9, end: 10 })
  assert.equal(cityScenario({ ...config, triggerPercent: 0 }, event).available, false)
  assert.equal(cityScenario({ ...config, allowedModes: [] }, event).available, false)
  assert.equal(cityScenario(config, config.pool[2]).share, 0)
})

test('cash visualization bounds display size without changing the denominator of the full range', () => {
  const range = cashRange(100, 100000, 100)
  assert.equal(range.count, 1000)
  assert.equal(range.values.length, 24)
  assert.equal(range.values.at(-1), 2400)
  assert.equal(cashRange(100, 100, 10).count, 1)
  for (const values of [[100, 250, 100], [100, 0, 10], [100, 200, 0]]) assert.equal(cashRange(...values).valid, false)
})
