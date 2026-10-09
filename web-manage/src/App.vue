<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { call, setToken, token } from './api.js'

const TIER_LABEL = { LOW: '低价地产', MID: '中价地产', HIGH: '高价地产' }
const LEVELS = ['未升级', '一级', '二级', '三级']
const EVENT_LABEL = {
  CASH_REWARD: '现金奖励', CASH_FINE: '现金罚款', CARD: '获得道具', MOVE: '随机移动', JAIL: '进监狱',
  BUILD: '免费升级一块地', DOWNGRADE: '自己一块地降级', TO_STATION: '前往随机车站', TO_START: '回到起点',
}
const CARD_LABEL = {
  ROADBLOCK: '路障', RENT_WAIVER: '免租', BUILD: '建造', DOWNGRADE: '降级', FIXED_MOVE: '定点移动',
  JAIL_RELEASE: '出狱', AUCTION: '拍卖', TRADE: '交易', REFUSE_PURCHASE: '拒绝购买', HOUSE_PROTECTION: '房屋保护',
  QUERY: '查询', FORCED_PURCHASE: '强制购房', DEMOLISH: '拆楼', CLEAR_LAND: '清地',
}
const TILE_LABEL = {
  START: '起点', STATION: '车站', EVENT: '事件', FIXED_EVENT: '幸运', UNLUCKY_EVENT: '不幸', BANK: '银行', JAIL: '监狱', REST: '休息', GAME_ZONE: '小游戏',
}
const BOARD_LABEL = { 'classic-30': '30 格地图', 'classic-50': '50 格地图' }

const loggedIn = ref(!!token())
const password = ref('')
const loading = ref(false)
const tab = ref('names')
const boards = ref([])
const boardId = ref('classic-30')
const active = ref(null)       // 当前生效版本 {configId, note, createdAt}
const form = ref(null)         // 正在编辑的参数（GameSettings）
const saved = ref('')          // 载入时的 JSON，用于判断是否有未发布的修改
const errors = ref([])
const history = ref([])

const dirty = computed(() => form.value && JSON.stringify(form.value) !== saved.value)
const eventTotal = computed(() => sum(form.value?.eventWeights))
const cardTotal = computed(() => sum(form.value?.cardWeights))
function poolTotal(unlucky) {
  return (form.value?.lucky || []).filter((l) => !!l.unlucky === unlucky).reduce((a, l) => a + (Number(l.weight) || 0), 0)
}
const cashChoices = computed(() => {
  const c = form.value?.eventCash
  if (!c || c.step <= 0 || c.max < c.min) return []
  const out = []
  for (let v = c.min; v <= c.max && out.length < 50; v += c.step) out.push(v)
  return out
})
// 建房选项：逗号分隔的数字列表 ⇄ 数组（中文逗号、空格也认）
function listText(key) {
  return (form.value?.room?.[key] || []).join(', ')
}
function setList(key, text) {
  form.value.room[key] = String(text).split(/[,，\s]+/).filter((x) => x !== '').map(Number)
}
// 输入时先存草稿，失焦 / 回车再换成数组（否则敲逗号会被立刻吃掉）
const drafts = reactive({})
function draft(key) {
  return drafts[key] ?? listText(key)
}
function commit(key) {
  if (drafts[key] === undefined) return
  setList(key, drafts[key])
  delete drafts[key]
}

// 同组地产：某格同组的其他地名
function groupMates(index) {
  const g = form.value?.sets?.groups?.[boardId.value]
  if (!g || !g[index]) return '不分组'
  const names = form.value.tileNames[boardId.value]
  const mates = g.map((v, i) => (v === g[index] && i !== index ? names[i] : null)).filter((x) => x)
  return mates.length ? '同组：' + mates.join('、') : '同组只有这一块（至少 2 块）'
}

// 租金上涨预览：列出倍率变化的轮次（与引擎 RentInflation.percent 一致）
const rentSteps = computed(() => {
  const r = form.value?.rentRise
  if (!r || r.stepPercent <= 0 || r.everyRounds < 1 || r.capPercent < 100) return []
  const out = []
  for (let k = 1; out.length < 12; k++) {
    const pct = Math.min(r.capPercent, 100 + k * r.stepPercent)
    out.push({ from: r.freeRounds + (k - 1) * r.everyRounds + 1, pct })
    if (pct >= r.capPercent) break
  }
  return out
})
const tiles = computed(() => {
  const b = boards.value.find((x) => x.id === boardId.value)
  const names = form.value?.tileNames?.[boardId.value] || []
  return names.map((_, i) => ({ index: i, tile: b ? b.tiles[i] : null }))
})

function sum(m) {
  return m ? Object.values(m).reduce((a, b) => a + (Number(b) || 0), 0) : 0
}

function tileType(t) {
  if (!t) return ''
  if (t.type === 'PROPERTY') return (TIER_LABEL[t.tier] || '地产') + (t.auctionDesignated ? '（拍卖地）' : '')
  return TILE_LABEL[t.type] || t.type
}

function time(ms) {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function fail(e) {
  if (e.status === 401) {
    loggedIn.value = false
    ElMessage.error('登录已过期，请重新登录')
    return
  }
  ElMessage.error(e.message || '请求失败')
}

async function login() {
  loading.value = true
  try {
    const r = await call('POST', '/admin-api/v1/auth/login', { password: password.value })
    setToken(r.token)
    password.value = ''
    loggedIn.value = true
    await load()
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}

function logout() {
  setToken(null)
  loggedIn.value = false
}

const startPickHint = computed(() => {
  const p = form.value?.startPick
  if (!p) return ''
  const sum = p.cashWeight + p.cardWeight
  return sum > 0 ? '每张牌 ' + Math.round((p.cashWeight * 100) / sum) + '% 现金、' + Math.round((p.cardWeight * 100) / sum) + '% 道具' : '已关闭'
})

// 旧版本没有公告：补一个关闭的公告，免得编辑区判为"有修改"
function fill(s) {
  if (s && !s.announcement) s.announcement = { enabled: false, title: '', text: '' }
  return s
}

// 操作时限的编辑项：[字段, 名称, 最小, 最大, 单位, 说明]
const TIMING_FIELDS = [
  ['decisionSeconds', '买地 / 升级等选择', 5, 120, '秒', '买地、升级、银行等弹窗的倒计时，超时视为放弃'],
  ['responseSeconds', '免租等响应卡询问', 5, 60, '秒', '被收租或被攻击时询问是否用卡'],
  ['discardSeconds', '弃牌', 5, 60, '秒', '手牌超过上限时选择弃哪张，超时放弃新卡'],
  ['tradeSeconds', '交易回应', 5, 60, '秒', '买家决定是否接受交易'],
  ['toothSeconds', '拔牙每次选择', 3, 60, '秒', '虎口拔牙轮到时的选择时间，超时随机代选'],
  ['auctionSeconds', '拍卖时长', 5, 120, '秒', ''],
  ['auctionExtendSeconds', '拍卖末尾出价顺延', 1, 30, '秒', '最后几秒有人出价，剩余时间恢复到这么多'],
  ['auctionMaxSeconds', '拍卖最长', 5, 300, '秒', '含顺延，不能小于拍卖时长'],
  ['debtSegmentSeconds', '欠款每段', 10, 120, '秒', '现金不足时应急抵押的时间，共两段'],
  ['animDiceMs', '投骰动画留时', 0, 5000, '毫秒', '投骰后等这么久再开下一步（给动画）'],
  ['animPerStepMs', '每走一格留时', 0, 2000, '毫秒', ''],
  ['autoActDelayMs', '托管代操作等待', 1, 10000, '毫秒', '托管 / 掉线时系统代为操作前的等待'],
]

// 数据看板
const dashDays = ref(14)
const dash = ref(null)
const dashLoading = ref(false)
const EVENT_NAMES = { ...EVENT_LABEL }
const REASON_LABEL = { TIME_UP: '时间到', NO_PLAYERS: '无人可继续', LAST_SURVIVOR: '只剩一人', ALL_ELIMINATED: '全部出局', ALL_AWAY: '全员挂机' }
const MODE_LABEL = { TIME_LIMIT: '限时', BANKRUPTCY: '破产' }
async function loadDash() {
  dashLoading.value = true
  try {
    dash.value = await call('GET', '/admin-api/v1/dashboard?days=' + dashDays.value)
  } catch (e) {
    fail(e)
  } finally {
    dashLoading.value = false
  }
}
watch(tab, (t) => { if (t === 'dashboard' && !dash.value) loadDash() })
const dashMax = computed(() => Math.max(1, ...((dash.value?.daily || []).map((d) => d.games))))
// 分布表：{键: 次数} → [{name, n, pct}]，按次数从多到少
function dist(m, names) {
  const rows = Object.entries(m || {}).map(([k, n]) => ({ name: (names && names[k]) || k, n }))
  const total = rows.reduce((a, r) => a + r.n, 0) || 1
  return rows.sort((a, b) => b.n - a.n).map((r) => ({ ...r, pct: Math.round((r.n * 1000) / total) / 10 }))
}
const MISC_LABEL = [['rentPaid', '收租次数'], ['rentAmount', '租金总额'], ['propertiesBought', '买地次数'], ['upgrades', '升级次数'],
  ['auctionsSold', '拍卖成交'], ['auctionsPassed', '拍卖流拍'], ['tradesDone', '交易成交'], ['minigames', '虎口拔牙'],
  ['jailed', '入狱次数'], ['bankrupt', '破产出局'], ['surrendered', '认输出局']]

async function load() {
  loading.value = true
  try {
    if (!boards.value.length) boards.value = await call('GET', '/api/boards')
    const a = await call('GET', '/admin-api/v1/config/active')
    active.value = a
    form.value = fill(a.settings)
    saved.value = JSON.stringify(a.settings)
    errors.value = []
    history.value = await call('GET', '/admin-api/v1/config/history')
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}

async function discard() {
  if (dirty.value) {
    await ElMessageBox.confirm('放弃所有未发布的修改，恢复为当前生效版本？', '放弃修改', { type: 'warning' })
  }
  await load()
}

async function useDefaults() {
  await ElMessageBox.confirm('把编辑区填成内置默认值（发布后才生效）？', '填入默认值', { type: 'warning' })
  try {
    form.value = fill(await call('GET', '/admin-api/v1/config/defaults'))
    errors.value = []
  } catch (e) {
    fail(e)
  }
}

async function validate() {
  try {
    const r = await call('POST', '/admin-api/v1/config/validate', form.value)
    errors.value = r.errors
    if (!r.errors.length) ElMessage.success('校验通过')
    return r.errors.length === 0
  } catch (e) {
    fail(e)
    return false
  }
}

async function publish() {
  if (!(await validate())) {
    ElMessage.error('有 ' + errors.value.length + ' 处需要修改，见页面顶部')
    return
  }
  let note
  try {
    const r = await ElMessageBox.prompt('发布后新开的房间立即使用这套参数，进行中的对局不受影响。', '发布', {
      inputPlaceholder: '这次改了什么（选填，最多 200 字）',
      confirmButtonText: '发布',
      inputValidator: (v) => !v || v.length <= 200 || '最多 200 字',
    })
    note = r.value || ''
  } catch {
    return
  }
  try {
    const r = await call('POST', '/admin-api/v1/config/publish', {
      settings: form.value, note, expectedActiveId: active.value.configId,
    })
    ElMessage.success('已发布版本 #' + r.configId)
    await load()
  } catch (e) {
    if (e.status === 409) ElMessage.error(e.message + '（你的修改还在编辑区，可以先记下再刷新）')
    else fail(e)
  }
}

async function rollback(row) {
  const label = row.configId === 0 ? '内置默认配置' : '版本 #' + row.configId
  try {
    await ElMessageBox.confirm('以「' + label + '」的内容发布一个新版本？新开的房间立即生效。'
      + (dirty.value ? '编辑区未发布的修改会丢失。' : ''), '回滚', { type: 'warning' })
  } catch {
    return
  }
  try {
    const r = await call('POST', '/admin-api/v1/config/rollback', {
      configId: row.configId, expectedActiveId: active.value.configId,
    })
    ElMessage.success('已回滚，生效版本 #' + r.configId)
    await load()
  } catch (e) {
    fail(e)
  }
}

async function view(row) {
  try {
    const r = await call('GET', '/admin-api/v1/config/' + row.configId)
    form.value = fill(r.settings)
    errors.value = []
    tab.value = 'prices'
    ElMessage.info('编辑区已载入版本 #' + row.configId + ' 的内容（未发布）')
  } catch (e) {
    fail(e)
  }
}

onMounted(() => {
  if (loggedIn.value) load()
})
</script>

<template>
  <div v-if="!loggedIn" class="login">
    <el-card class="login-card">
      <h2>大富翁参数管理</h2>
      <el-form @submit.prevent="login">
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="管理员密码" show-password autofocus />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">登录</el-button>
      </el-form>
    </el-card>
  </div>

  <div v-else class="page" v-loading="loading">
    <header class="bar">
      <div class="title">
        <strong>大富翁参数管理</strong>
        <span v-if="active" class="muted">
          当前生效：{{ active.configId === 0 ? '内置默认配置' : '版本 #' + active.configId }}
          <span v-if="active.createdAt">（{{ time(active.createdAt) }}）</span>
        </span>
        <el-tag v-if="dirty" type="warning" size="small">有未发布的修改</el-tag>
      </div>
      <div class="actions">
        <el-button @click="useDefaults">填入默认值</el-button>
        <el-button @click="discard">放弃修改</el-button>
        <el-button @click="validate">校验</el-button>
        <el-button type="primary" @click="publish">发布</el-button>
        <el-button text @click="logout">退出</el-button>
      </div>
    </header>

    <el-alert v-if="errors.length" type="error" :closable="false" class="errors" title="需要修改后才能发布">
      <ul><li v-for="e in errors" :key="e">{{ e }}</li></ul>
    </el-alert>

    <el-tabs v-if="form" v-model="tab" class="tabs">
      <el-tab-pane label="土地命名" name="names">
        <el-radio-group v-model="boardId" class="gap">
          <el-radio-button v-for="(_, id) in form.tileNames" :key="id" :value="id">{{ BOARD_LABEL[id] || id }}</el-radio-button>
        </el-radio-group>
        <p class="muted">每格最多 8 个字。格子类型与顺序由地图决定，这里只改显示名称。</p>
        <div class="names">
          <div v-for="t in tiles" :key="boardId + t.index" class="name-row">
            <span class="idx">{{ t.index }}</span>
            <span class="type">{{ tileType(t.tile) }}</span>
            <el-input v-model="form.tileNames[boardId][t.index]" maxlength="8" />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="同组地产" name="sets">
        <el-form v-if="form.sets" label-width="160px" class="narrow">
          <p class="muted">同一组的普通地产全部归同一个人、且都没有抵押时，组内每块地的租金按倍率上涨（填 100% 即关闭）。组号填 0 表示不分组；同一组号的地就是一组，每组至少 2 块。车站与功能格不能分组。</p>
          <el-form-item label="集齐后租金倍率（%）"><el-input-number v-model="form.sets.rentPercent" :min="100" :max="500" :step="10" /></el-form-item>
        </el-form>
        <el-radio-group v-model="boardId" class="gap">
          <el-radio-button v-for="(_, id) in form.tileNames" :key="id" :value="id">{{ BOARD_LABEL[id] || id }}</el-radio-button>
        </el-radio-group>
        <div v-if="form.sets && form.sets.groups[boardId]" class="names">
          <div v-for="t in tiles.filter((x) => x.tile && x.tile.type === 'PROPERTY')" :key="'g' + boardId + t.index" class="name-row">
            <span class="idx">{{ t.index }}</span>
            <span class="type">{{ form.tileNames[boardId][t.index] }}</span>
            <el-input-number v-model="form.sets.groups[boardId][t.index]" :min="0" :max="99" size="small" />
            <span class="muted">{{ groupMates(t.index) }}</span>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="价格与租金" name="prices">
        <el-table :data="form.tiers" border class="gap">
          <el-table-column label="档位" width="110">
            <template #default="{ row }">{{ TIER_LABEL[row.tier] }}</template>
          </el-table-column>
          <el-table-column label="购买价" min-width="130">
            <template #default="{ row }"><el-input-number v-model="row.basePrice" :min="1" :step="50" :controls="false" class="cell-num" /></template>
          </el-table-column>
          <el-table-column label="每级升级费" min-width="130">
            <template #default="{ row }"><el-input-number v-model="row.upgradeCost" :min="1" :step="50" :controls="false" class="cell-num" /></template>
          </el-table-column>
          <el-table-column v-for="(lv, i) in LEVELS" :key="lv" :label="lv + '租金'" min-width="130">
            <template #default="{ row }"><el-input-number v-model="row.rents[i]" :min="1" :step="50" :controls="false" class="cell-num" /></template>
          </el-table-column>
        </el-table>
        <el-form label-width="140px" class="narrow">
          <h3>车站</h3>
          <el-form-item label="购买价"><el-input-number v-model="form.station.price" :min="1" :step="50" /></el-form-item>
          <el-form-item label="每座车站租金">
            <el-input-number v-model="form.station.rentPerStation" :min="1" :step="50" />
            <span class="hint">落到别人车站时：租金 × 对方拥有的车站数</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="事件概率" name="events">
        <h3>事件格结果（合计 100）<el-tag :type="eventTotal === 100 ? 'success' : 'danger'" size="small">当前 {{ eventTotal }}</el-tag></h3>
        <el-form label-width="140px" class="narrow">
          <el-form-item v-for="(label, k) in EVENT_LABEL" :key="k" :label="label">
            <el-input-number v-model="form.eventWeights[k]" :min="0" :max="100" />
            <span class="hint">%</span>
          </el-form-item>
        </el-form>
        <h3>道具持有上限</h3>
        <el-form label-width="140px" class="narrow">
          <el-form-item label="每人最多持有">
            <el-input-number v-model="form.handLimit" :min="2" :max="8" />
            <span class="hint">张；超出时要弃一张。房间"开局道具"最多也只能选这么多（2～8）</span>
          </el-form-item>
        </el-form>
        <h3>抽到各种道具（合计 1000）<el-tag :type="cardTotal === 1000 ? 'success' : 'danger'" size="small">当前 {{ cardTotal }}</el-tag></h3>
        <p class="muted">事件格结果为"获得道具"时，按下列权重抽一张（千分比，120 = 12%）。</p>
        <div class="cards">
          <div v-for="(label, k) in CARD_LABEL" :key="k" class="card-row">
            <span>{{ label }}</span>
            <el-input-number v-model="form.cardWeights[k]" :min="0" :max="1000" size="small" />
            <span class="muted">{{ ((form.cardWeights[k] || 0) / 10).toFixed(1) }}%</span>
          </div>
        </div>
        <template v-for="grp in [{ unlucky: false, title: '幸运格（全是好事）' }, { unlucky: true, title: '不幸格（全是坏事）' }]" :key="grp.title">
          <h3>{{ grp.title }} · 踩到自动抽一张<el-tag size="small">合计 {{ poolTotal(grp.unlucky) }}</el-tag></h3>
          <el-form label-width="140px" class="narrow">
            <el-form-item v-for="l in (form.lucky || []).filter((x) => !!x.unlucky === grp.unlucky)" :key="l.kind + grp.unlucky" :label="l.label">
              <el-input-number v-model="l.weight" :min="0" :max="100" />
              <span class="hint">{{ poolTotal(grp.unlucky) ? Math.round((l.weight || 0) * 1000 / poolTotal(grp.unlucky)) / 10 : 0 }}%</span>
              <template v-if="l.kind === 'CASH_REWARD' || l.kind === 'CASH_FINE'">
                <span class="hint">{{ l.kind === 'CASH_REWARD' ? '奖励' : '罚款' }}金额</span>
                <el-input-number v-model="l.amount" :min="1" :step="50" style="margin-left: 8px" />
              </template>
            </el-form-item>
          </el-form>
        </template>
        <p class="muted">两张地图共用。按权重抽取（概率 = 本项 ÷ 本组合计），填 0 表示不出现。奖励 / 罚款金额须在「金额与固定费用」里事件现金的范围内、并符合步长。"迷路倒退"后退 1～3 格。</p>
      </el-tab-pane>

      <el-tab-pane label="金额与固定费用" name="money">
        <el-form label-width="160px" class="narrow">
          <h3>事件格现金</h3>
          <p class="muted">"现金奖励"和"现金罚款"共用这个范围：从最小值到最大值按步长等概率取一个数。</p>
          <el-form-item label="最小值"><el-input-number v-model="form.eventCash.min" :min="1" :step="50" /></el-form-item>
          <el-form-item label="最大值"><el-input-number v-model="form.eventCash.max" :min="1" :step="50" /></el-form-item>
          <el-form-item label="步长"><el-input-number v-model="form.eventCash.step" :min="1" :step="10" /></el-form-item>
          <el-form-item label="可能取到">
            <span v-if="cashChoices.length" class="muted">{{ cashChoices.join('、') }}{{ cashChoices.length >= 50 ? '…' : '' }}</span>
            <span v-else class="danger">范围或步长不正确</span>
          </el-form-item>
          <h3>固定费用</h3>
          <el-form-item label="经过起点奖励"><el-input-number v-model="form.fees.startReward" :min="1" :step="50" /></el-form-item>
          <el-form-item label="小游戏获胜奖励"><el-input-number v-model="form.fees.miniGameWinReward" :min="1" :step="50" /></el-form-item>
          <el-form-item label="出狱费用"><el-input-number v-model="form.fees.bailCost" :min="1" :step="50" /></el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="建房选项" name="room">
        <el-form v-if="form.room" label-width="160px" class="narrow">
          <p class="muted">房主建房时能选的项与默认值。每组 1～5 个，用逗号分隔，保存时按从小到大排列；默认值必须是其中一个。只影响发布之后新建的房间。</p>
          <h3>初始现金</h3>
          <el-form-item label="可选金额"><el-input :model-value="draft('initialCashOptions')" @update:model-value="(v) => (drafts['initialCashOptions'] = v)" @change="commit('initialCashOptions')" placeholder="如 2000, 3000, 5000" /></el-form-item>
          <el-form-item label="默认"><el-select v-model="form.room.defaultInitialCash"><el-option v-for="v in form.room.initialCashOptions" :key="v" :label="v" :value="v" /></el-select></el-form-item>
          <h3>结束模式</h3>
          <el-form-item label="默认结束模式"><el-radio-group v-model="form.room.defaultEndMode"><el-radio value="TIME_LIMIT">限时</el-radio><el-radio value="BANKRUPTCY">破产</el-radio></el-radio-group></el-form-item>
          <el-form-item label="限时可选（分钟）"><el-input :model-value="draft('timeLimitMinutesOptions')" @update:model-value="(v) => (drafts['timeLimitMinutesOptions'] = v)" @change="commit('timeLimitMinutesOptions')" placeholder="如 15, 30, 60（5～240）" /></el-form-item>
          <el-form-item label="限时默认"><el-select v-model="form.room.defaultTimeLimitMinutes"><el-option v-for="v in form.room.timeLimitMinutesOptions" :key="v" :label="v + ' 分钟'" :value="v" /></el-select></el-form-item>
          <el-form-item label="破产模式最长（分钟）"><el-input-number v-model="form.room.bankruptcyCapMinutes" :min="10" :max="600" :step="10" /><span class="hint">到时按净资产排名结束</span></el-form-item>
          <h3>投骰时间</h3>
          <el-form-item label="可选（秒）"><el-input :model-value="draft('rollSecondsOptions')" @update:model-value="(v) => (drafts['rollSecondsOptions'] = v)" @change="commit('rollSecondsOptions')" placeholder="如 15, 30, 45, 60（5～120）" /></el-form-item>
          <el-form-item label="默认"><el-select v-model="form.room.defaultRollSeconds"><el-option v-for="v in form.room.rollSecondsOptions" :key="v" :label="v + ' 秒'" :value="v" /></el-select></el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="租金上涨" name="rentRise">
        <el-form v-if="form.rentRise" label-width="160px" class="narrow">
          <p class="muted">只在破产模式生效，防止对局拖太久：所有存活玩家各走一次算一轮，前几轮按原价，之后每隔几轮租金倍率上涨一次，到封顶为止。地产和车站租金都涨，按基础租金乘倍率、向下取整到 10；买地、升级、抵押价与起点奖励不变。</p>
          <el-form-item label="原价轮数"><el-input-number v-model="form.rentRise.freeRounds" :min="0" :max="1000" /><span class="hint">轮内不涨价</span></el-form-item>
          <el-form-item label="上涨间隔（轮）"><el-input-number v-model="form.rentRise.everyRounds" :min="1" :max="100" /></el-form-item>
          <el-form-item label="每次涨幅（%）"><el-input-number v-model="form.rentRise.stepPercent" :min="0" :max="100" :step="5" /><span class="hint">0 表示不上涨</span></el-form-item>
          <el-form-item label="封顶倍率（%）"><el-input-number v-model="form.rentRise.capPercent" :min="100" :max="1000" :step="50" /></el-form-item>
          <el-form-item label="效果预览">
            <span v-if="rentSteps.length" class="muted">第 1～{{ form.rentRise.freeRounds }} 轮原价；{{ rentSteps.map((s) => '第 ' + s.from + ' 轮起 ×' + s.pct / 100).join('，') }}{{ rentSteps.length >= 12 ? '…' : '' }}</span>
            <span v-else class="muted">租金不上涨</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="起点三选一" name="startPick">
        <el-form v-if="form.startPick" label-width="160px" class="narrow">
          <p class="muted">玩家前进经过或停在起点时，先在起点暂停，从三张背面朝上的牌里选一张，抽完再继续剩余步数。每张牌按权重决定是现金还是道具，现金在范围内按步长随机，道具按「道具概率」抽取。两个权重都为 0 时关闭；关闭时经过起点只发起点奖励。后退经过起点不抽卡。</p>
          <el-form-item label="现金权重"><el-input-number v-model="form.startPick.cashWeight" :min="0" :max="1000" /></el-form-item>
          <el-form-item label="道具权重"><el-input-number v-model="form.startPick.cardWeight" :min="0" :max="1000" /><span class="hint">{{ startPickHint }}</span></el-form-item>
          <el-form-item label="现金最小值"><el-input-number v-model="form.startPick.cashMin" :min="1" :max="1000000" :step="100" /></el-form-item>
          <el-form-item label="现金最大值"><el-input-number v-model="form.startPick.cashMax" :min="1" :max="1000000" :step="100" /></el-form-item>
          <el-form-item label="现金步长"><el-input-number v-model="form.startPick.cashStep" :min="1" :max="1000000" :step="50" /><span class="hint">须整除（最大值 − 最小值）</span></el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="操作时限" name="timing">
        <el-form v-if="form.timing" label-width="170px" class="narrow">
          <p class="muted">各类弹窗的倒计时与动画留时。投骰时间在「建房选项」里配置。只影响发布之后新建的房间。</p>
          <el-form-item v-for="f in TIMING_FIELDS" :key="f[0]" :label="f[1]">
            <el-input-number v-model="form.timing[f[0]]" :min="f[2]" :max="f[3]" :step="f[4] === '秒' ? 1 : 50" />
            <span class="hint">{{ f[4] }}{{ f[5] ? ' · ' + f[5] : '' }}</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="公告" name="announcement">
        <el-form v-if="form.announcement" label-width="120px" class="narrow">
          <p class="muted">发布后，大厅顶部显示这条公告（玩家可以关掉，本次打开不再显示）。用于维护通知、活动说明等。</p>
          <el-form-item label="显示公告"><el-switch v-model="form.announcement.enabled" /></el-form-item>
          <el-form-item label="标题"><el-input v-model="form.announcement.title" maxlength="20" show-word-limit placeholder="如：周末活动" /></el-form-item>
          <el-form-item label="内容"><el-input v-model="form.announcement.text" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="如：本周末经过起点奖励翻倍！" /></el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="数据看板" name="dashboard">
        <div class="dash-bar">
          <el-radio-group v-model="dashDays" @change="loadDash">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="14">近 14 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
          </el-radio-group>
          <el-button :loading="dashLoading" @click="loadDash">刷新</el-button>
          <span v-if="dash" class="muted">{{ dash.from }} ～ {{ dash.to }}（北京时间）</span>
        </div>
        <p v-if="dash && !dash.dbEnabled" class="danger">后台没有连接数据库，没有可统计的数据。</p>
        <template v-if="dash && dash.dbEnabled">
          <div class="tiles">
            <div class="tile"><div class="tile-v">{{ dash.totals.games }}</div><div class="tile-k">对局数</div></div>
            <div class="tile"><div class="tile-v">{{ dash.totals.players }}</div><div class="tile-k">参与玩家</div></div>
            <div class="tile"><div class="tile-v">{{ dash.totals.newUsers }}</div><div class="tile-k">新用户</div></div>
            <div class="tile"><div class="tile-v">{{ dash.totals.avgMinutes ?? '—' }}</div><div class="tile-k">平均时长（分钟）</div></div>
            <div class="tile"><div class="tile-v">{{ dash.totals.avgPlayers ?? '—' }}</div><div class="tile-k">平均人数</div></div>
          </div>
          <h3>每天对局数</h3>
          <div class="chart" role="img" :aria-label="'每天对局数，最多 ' + dashMax + ' 局'">
            <div v-for="d in dash.daily" :key="d.date" class="col" :title="d.date + '：' + d.games + ' 局，' + d.players + ' 人参与，新用户 ' + d.newUsers + (d.avgMinutes != null ? '，平均 ' + d.avgMinutes + ' 分钟' : '')">
              <div class="col-v">{{ d.games || '' }}</div>
              <div class="col-bar" :style="{ height: (d.games / dashMax) * 140 + 'px' }"></div>
              <div class="col-k">{{ d.date.slice(5) }}</div>
            </div>
          </div>
          <el-table :data="dash.daily" border size="small" class="gap" max-height="320">
            <el-table-column prop="date" label="日期" width="120" />
            <el-table-column prop="games" label="对局数" />
            <el-table-column prop="players" label="参与玩家" />
            <el-table-column prop="newUsers" label="新用户" />
            <el-table-column label="平均时长（分钟）"><template #default="{ row }">{{ row.avgMinutes ?? '—' }}</template></el-table-column>
          </el-table>
          <div class="dists">
            <div v-for="blk in [
              { t: '结束方式', rows: dist(dash.endModes, MODE_LABEL) },
              { t: '结束原因', rows: dist(dash.reasons, REASON_LABEL) },
              { t: '地图', rows: dist(dash.boards, BOARD_LABEL) },
              { t: '人数', rows: dist(dash.playerCounts) },
              { t: '抽卡事件结果', rows: dist(dash.events.drawnEvents, EVENT_NAMES) },
              { t: '幸运 / 不幸格结果', rows: dist(dash.events.luckyEvents, EVENT_NAMES) },
              { t: '道具使用', rows: dist(dash.events.cardsUsed, CARD_LABEL) },
            ]" :key="blk.t" class="dist">
              <h3>{{ blk.t }}</h3>
              <p v-if="!blk.rows.length" class="muted">暂无数据</p>
              <div v-for="r in blk.rows" :key="r.name" class="dist-row" :title="r.name + '：' + r.n + ' 次（' + r.pct + '%）'">
                <span class="dist-k">{{ r.name }}</span>
                <span class="dist-track"><span class="dist-fill" :style="{ width: r.pct + '%' }"></span></span>
                <span class="dist-v">{{ r.n }}</span>
              </div>
            </div>
            <div class="dist">
              <h3>其他次数</h3>
              <div v-for="m in MISC_LABEL" :key="m[0]" class="dist-row"><span class="dist-k">{{ m[1] }}</span><span class="dist-v">{{ dash.events[m[0]] }}</span></div>
              <p class="muted">事件类统计来自最近 {{ dash.events.logsScanned }} 局的对局日志（最多 500 局）。</p>
            </div>
          </div>
        </template>
      </el-tab-pane>

      <el-tab-pane label="发布记录" name="history">
        <el-table :data="history" border>
          <el-table-column label="版本" width="110">
            <template #default="{ row }">{{ row.configId === 0 ? '内置默认' : '#' + row.configId }}</template>
          </el-table-column>
          <el-table-column label="发布时间" width="190">
            <template #default="{ row }">{{ time(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="200" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag v-if="row.active" type="success" size="small">生效中</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button size="small" @click="view(row)">载入编辑</el-button>
              <el-button v-if="!row.active" size="small" type="warning" @click="rollback(row)">回滚到此</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style>
body { margin: 0; background: #f5f7fa; font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif; }
.login { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box; }
.login-card { width: 340px; max-width: 100%; }
.login-card h2 { margin: 0 0 20px; text-align: center; font-weight: 600; }
.page { max-width: 1200px; margin: 0 auto; padding: 16px; }
.bar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; background: #fff;
  padding: 12px 16px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.title { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.actions { display: flex; flex-wrap: wrap; gap: 4px; }
.muted { color: #909399; font-size: 13px; }
.hint { color: #909399; font-size: 13px; margin-left: 10px; }
.danger { color: #f56c6c; }
.errors { margin-top: 12px; }
.errors ul { margin: 4px 0 0; padding-left: 18px; }
.tabs { margin-top: 12px; background: #fff; padding: 8px 16px 16px; border-radius: 8px; }
.gap { margin-bottom: 12px; }
.cell-num { width: 100% !important; }
.narrow { max-width: 640px; }
h3 { font-size: 15px; margin: 18px 0 10px; display: flex; gap: 8px; align-items: center; }
.names { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px 16px; }
.name-row { display: flex; align-items: center; gap: 8px; }
.name-row .idx { width: 24px; text-align: right; color: #909399; font-size: 12px; }
.name-row .type { width: 96px; font-size: 12px; color: #606266; flex-shrink: 0; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 8px 16px; max-width: 900px; }
.card-row { display: flex; align-items: center; gap: 8px; }
.card-row > span:first-child { width: 64px; }
.dash-bar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 12px; }
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
.tile { background: #f5f7fa; border-radius: 8px; padding: 12px 16px; }
.tile-v { font-size: 26px; font-weight: 600; color: #303133; }
.tile-k { font-size: 13px; color: #909399; margin-top: 4px; }
.chart { display: flex; align-items: flex-end; gap: 2px; height: 190px; overflow-x: auto; border-bottom: 1px solid #dcdfe6; margin-bottom: 12px; }
.col { flex: 1 0 22px; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; height: 100%; cursor: default; }
.col:hover .col-bar { background: #337ecc; }
.col-v { font-size: 11px; color: #606266; min-height: 14px; }
.col-bar { width: 70%; max-width: 28px; background: #409eff; border-radius: 4px 4px 0 0; min-height: 0; }
.col-k { font-size: 11px; color: #909399; padding: 4px 0; white-space: nowrap; }
.dists { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 0 24px; }
.dist-row { display: flex; align-items: center; gap: 8px; font-size: 13px; margin: 4px 0; }
.dist-k { width: 96px; color: #606266; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dist-track { flex: 1; height: 10px; background: #f0f2f5; border-radius: 5px; overflow: hidden; }
.dist-fill { display: block; height: 100%; background: #409eff; border-radius: 5px; }
.dist-v { width: 56px; text-align: right; color: #303133; }
</style>
