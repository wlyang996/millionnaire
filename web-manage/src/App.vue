<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
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

async function load() {
  loading.value = true
  try {
    if (!boards.value.length) boards.value = await call('GET', '/api/boards')
    const a = await call('GET', '/admin-api/v1/config/active')
    active.value = a
    form.value = a.settings
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
    form.value = await call('GET', '/admin-api/v1/config/defaults')
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
    form.value = r.settings
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
</style>
