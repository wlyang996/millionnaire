<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { call, setToken, token } from './api.js'
import CharacterManager from './CharacterManager.vue'
import AudioManager from './AudioManager.vue'
import AdminAccessManager from './components/AdminAccessManager.vue'
import PasswordChange from './components/PasswordChange.vue'
import ReadOnlyConfig from './components/ReadOnlyConfig.vue'
import { adminIdentity as identity,setIdentity } from './admin/accessSession.js'
import { can, pageVisible, accessPages } from './admin/accessPolicy.js'
import './admin/access.css'
import LoginPage from './components/LoginPage.vue'
import AdminShell from './components/AdminShell.vue'
import DashboardOverview from './components/DashboardOverview.vue'
import RulePreview from './components/RulePreview.vue'
import BoardPreview from './components/BoardPreview.vue'
import VersionCenter from './components/VersionCenter.vue'
import VersionDiff from './components/VersionDiff.vue'
import BorderBeam from './components/effects/BorderBeam.vue'
import { sectionForField } from './admin/navigation.js'
import { searchIndex } from './admin/fields.js'
import { compareVersions, versionName } from './admin/versionComparison.js'
import { createReleaseReview } from './admin/releaseReview.js'
import { motionEnabled } from './admin/preferences.js'
import { rewardAmount } from './admin/ruleVisuals.js'

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
const loginError = ref('')
const loginSuccess = ref(false)
const loading = ref(false)
const tab = ref('dashboard')
const boards = ref([])
const boardId = ref('classic-30')
const active = ref(null)       // 当前生效版本 {configId, note, createdAt}
const form = ref(null)         // 正在编辑的参数（GameSettings）
const saved = ref('')          // 载入时的 JSON，用于判断是否有未发布的修改
const errors = ref([])
const history = ref([])

const dirty = computed(() => !!form.value && (Object.keys(drafts).length > 0 || JSON.stringify(form.value) !== saved.value))
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

function commitTeamQuickAmounts() {
  if (drafts.teamQuickAmounts === undefined || !form.value?.team) return
  form.value.team.aidQuickAmounts = String(drafts.teamQuickAmounts).split(/[,，\s]+/).filter(Boolean).map(Number)
  delete drafts.teamQuickAmounts
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
    setToken(null)
    loggedIn.value = false
    ElMessage.error('登录已过期，请重新登录')
    return
  }
  ElMessage.error(e.message || '请求失败')
}

async function login(credentials) {
  if (loading.value || loginSuccess.value) return
  loginError.value = ''
  loading.value = true
  try {
    const r = await call('POST', '/admin-api/v1/auth/login', credentials)
    setToken(r.token)
    setIdentity(r)
    loginSuccess.value = true
  } catch (e) {
    loginError.value = !e.status ? '暂时无法连接后台，请稍后重试'
      : !e.message || e.message.startsWith('HTTP ') ? '后台暂时不可用，请稍后重试' : e.message
    if(e.retryAt)loginError.value+='（可重试时间：'+new Date(e.retryAt).toLocaleTimeString('zh-CN',{hour12:false})+'）'
  } finally {
    loading.value = false
  }
}

async function enterAdmin() {
  loggedIn.value = true
  loginSuccess.value = false
  try { await refreshIdentity(); if(!identity.value.mustChangePassword) await load() } catch (e) { fail(e) }
}

async function logout() {
  if (dirty.value || characterDirty.value || audioDirty.value) {
    try { await ElMessageBox.confirm('还有未保存或未发布的修改，确认退出？', '退出登录', { type: 'warning' }) } catch { return }
  }
  try { await call('POST','/admin-api/v1/auth/logout',{}) } catch(e) { if(e.status!==401){fail(e);return} }
  clearLogin()
  form.value = null; active.value = null; history.value = []; dash.value = null; characterDirty.value = false; audioDirty.value = false
  Object.keys(drafts).forEach(key => delete drafts[key])
}

function clearLogin() { setToken(null);setIdentity(null);loggedIn.value=false;loginSuccess.value=false }
async function refreshIdentity(){ const result=await call('GET','/admin-api/v1/auth/me');setIdentity(result);if(!result.mustChangePassword&&!pageVisible(result,tab.value))tab.value='dashboard' }
let identityRefresh
function handleAccessError(event){if(event.detail.status===401){clearLogin();return}if(!identityRefresh)identityRefresh=refreshIdentity().catch(fail).finally(()=>identityRefresh=null)}
function resumed(){if(!document.hidden&&loggedIn.value)refreshIdentity().catch(fail)}
const canEdit=computed(()=>can(identity.value,'config:write'))
const isAccessPage=computed(()=>accessPages.includes(tab.value))
const auditVersion=ref(null)
function signedout(){clearLogin();ElMessage.success('会话已撤销，请重新登录')}
function openAuditVersion(id){auditVersion.value=id;tab.value='history';ElMessage.info('已打开版本中心，可选择版本 #'+id+' 对比')}
const startPickHint = computed(() => {
  const p = form.value?.startPick
  if (!p) return ''
  const sum = p.cashWeight + p.cardWeight
  return sum > 0 ? '每张牌 ' + Math.round((p.cashWeight * 100) / sum) + '% 现金、' + Math.round((p.cardWeight * 100) / sum) + '% 道具' : '已关闭'
})

// 旧版本没有公告：补一个关闭的公告，免得编辑区判为"有修改"
function fill(s) {
  if (s && !s.series) s.series = { enabled: false, gameOptions: [3,5,7], defaultGames: 3, rankPoints: [10,6,4,3,2,1,0,0], tieMode: 'SHARED_RANK', allowBots: true, minPlayers: 2 }
  if (s && !s.announcement) s.announcement = { enabled: false, title: '', text: '' }
  if (s && !s.roundReward) s.roundReward = { enabled: false, name: '月度荣耀奖励', timeLimitRewardRound: 5, bankruptcyRewardRound: 5, rewardPercent: 5, perPlayerCap: 1000, roundingUnit: 10, incomeSources: Object.keys(INCOME_LABEL), presentationMs: 3000 }
  if (s && !s.funTitles) s.funTitles = { enabled: false, titles: Object.entries(TITLE_LABEL).map(([kind, name]) => ({ kind, name, enabled: true, minimum: kind === 'COMEBACK' ? 2 : 1 })) }
  if (s && !s.cityEvents) s.cityEvents = { enabled: false, allowedModes: ['TIME_LIMIT', 'BANKRUPTCY'], firstCheckRound: 5, checkEveryRounds: 5, triggerPercent: 30, advanceRounds: 1, announcementMs: 3000, activationMs: 1500, endMs: 1500, pool: Object.entries(CITY_LABEL).map(([kind, name]) => ({ kind, name, enabled: true, weight: 1, multiplierPercent: kind === 'STATION_RENT' ? 150 : 80, durationRounds: 2, artworkKey: CITY_ART[kind] })) }
  return s
}
const INCOME_LABEL = { RENT: '实际收租', START: '起点奖励', EVENT: '现金事件', MINIGAME: '小游戏奖励', COMMISSION: '拍卖佣金' }
const TITLE_LABEL = { RENT_KING: '收租王', PROPERTY_TYCOON: '地产大亨', LUCKY_STAR: '幸运之星', COMEBACK: '逆风翻盘' }
const CITY_LABEL = { UPGRADE_DISCOUNT: '建设节', STATION_RENT: '旅游旺季', PROPERTY_RENT: '安居日' }
const CITY_ART = { UPGRADE_DISCOUNT: 'city_construction', STATION_RENT: 'scene_station', PROPERTY_RENT: 'scene_property_low' }
const rewardIncome = ref(8000)
const rewardPreview = computed(() => rewardAmount(form.value?.roundReward, rewardIncome.value))
const CITY_FIELDS = [
  ['firstCheckRound', '首次检查：完成第几轮', 1, 1000], ['checkEveryRounds', '后续每几轮检查', 1, 100],
  ['triggerPercent', '触发概率（%）', 0, 100], ['advanceRounds', '提前轮数', 1, 10],
  ['announcementMs', '预告展示（毫秒）', 1000, 10000], ['activationMs', '生效提示（毫秒，0关闭）', 0, 10000], ['endMs', '结束提示（毫秒，0关闭）', 0, 10000],
]

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
  ['animPerStepMs', '普通每格移动时长', 0, 2000, '毫秒', '默认 550；人物动画与服务端等待时间一致，快速模式按房间配置比例缩短'],
  ['eventPresentationMs', '事件翻牌及结果展示', 0, 10000, '毫秒', '包含翻牌和结果停留，结束后再进行后续移动或开启下一位的投骰窗口'],
  ['eventCashPresentationMs', '现金事件展示', 0, 10000, '毫秒', '现金奖励 / 罚款的翻牌和结果停留总时长'],
  ['startPickPresentationMs', '起点抽卡结果展示', 0, 10000, '毫秒', '抽卡结果展示完再走剩余步数或开始下一回合'],
  ['jailPresentationMs', '入狱动画展示', 0, 10000, '毫秒', '展示结束后再开始下一回合'],
  ['allAwayTurns', '全员挂机 / 托管结束', 1, 100, '回合', '全员连续挂机或托管达到该回合数后结束；每名玩家的一次行动算一回合，恢复手动操作后清零'],
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
    form.value = fill(structuredClone(a.settings))
    Object.keys(drafts).forEach(key => delete drafts[key])
    saved.value = JSON.stringify(form.value)
    errors.value = []
    history.value = await call('GET', '/admin-api/v1/config/history')
    if (tab.value === 'dashboard' && !dash.value) await loadDash()
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
    Object.keys(drafts).forEach(key => delete drafts[key])
    errors.value = []
  } catch (e) {
    fail(e)
  }
}

async function validate() {
  Object.keys(drafts).filter(key => key !== 'teamQuickAmounts').forEach(commit)
  commitTeamQuickAmounts()
  try {
    const r = await call('POST', '/admin-api/v1/config/validate', form.value)
    errors.value = r.errors
    if (!r.errors.length) ElMessage.success('校验通过')
    else await focusValidation(r.errors[0])
    return r.errors.length === 0
  } catch (e) {
    fail(e)
    return false
  }
}

async function publish() {
  if (releaseBusy.value || !(await validate())) return
  releaseNote.value = ''; releaseStale.value=false; releaseReview.value=createReleaseReview(originalSettings.value,form.value,active.value.configId); releaseReviewKey.value++; releaseStage.value = 'review'; releaseOpen.value = true
}
async function confirmPublish() {
  if (releaseBusy.value || releaseStale.value || !releaseReview.value) return
  releaseBusy.value = true; releaseStage.value = 'validating'
  try {
    const validation = await call('POST','/admin-api/v1/config/validate',releaseReview.value.settings)
    errors.value=validation.errors
    if (validation.errors.length) { releaseStage.value = 'invalid'; return }
    releaseStage.value = 'publishing'
    const r = await call('POST', '/admin-api/v1/config/publish', { settings: releaseReview.value.settings, note: releaseNote.value, expectedActiveId: releaseReview.value.expectedActiveId })
    releaseStage.value = 'success'; celebrate(); ElMessage.success('已发布版本 #' + r.configId)
    await load()
  } catch (e) {
    releaseStage.value = 'failed'
    if (e.status === 409) { releaseStale.value=true; ElMessage.error(e.message + '（你的修改仍在编辑区）') }
    else fail(e)
  } finally { releaseBusy.value = false }
}

async function recheckRelease() {
  if (releaseBusy.value) return
  releaseBusy.value=true
  try {
    const current=await call('GET','/admin-api/v1/config/active')
    releaseReview.value=createReleaseReview(fill(structuredClone(current.settings)),form.value,current.configId)
    releaseReviewKey.value++; releaseStale.value=false; releaseStage.value='review'
  } catch (e) { fail(e) }
  finally { releaseBusy.value=false }
}

async function view(row) {
  try {
    const r = await call('GET', '/admin-api/v1/config/' + row.configId)
    if (dirty.value) { try { await ElMessageBox.confirm('载入历史版本会替换当前编辑内容，确认继续？', '载入版本', { type: 'warning' }) } catch { return } }
    form.value = fill(structuredClone(r.settings))
    Object.keys(drafts).forEach(key => delete drafts[key])
    errors.value = []
    tab.value = 'prices'
    ElMessage.info('编辑区已载入版本 #' + row.configId + ' 的内容（未发布）')
  } catch (e) {
    fail(e)
  }
}

const selectedTile = ref(0)
const characterDirty = ref(false)
const audioDirty = ref(false)
const audioVersion = ref(undefined)
const celebration = ref(0)
const releaseOpen = ref(false), releaseBusy = ref(false), releaseNote = ref(''), releaseStage = ref('review'), releaseReview = ref(null), releaseReviewKey = ref(0), releaseStale = ref(false)
let celebrationTimer, validationTimer
function celebrate() { celebration.value = Date.now(); clearTimeout(celebrationTimer); celebrationTimer = setTimeout(() => { celebration.value = 0 }, 1800) }
const originalSettings = computed(() => { try { return JSON.parse(saved.value || '{}') } catch { return {} } })
const changes = computed(() => compareVersions(originalSettings.value, form.value || {}).flatMap(group=>group.rows))
const isConfigPage = computed(() => !isAccessPage.value && !['dashboard','characters','audio','history'].includes(tab.value))
const isBoardPage = computed(() => ['names','sets'].includes(tab.value))
const parameterSearchIndex = computed(() => ({ ...searchIndex, events:searchIndex.events+' '+Object.values(EVENT_LABEL).join(' ')+' '+Object.values(CARD_LABEL).join(' '),timing:searchIndex.timing+' '+TIMING_FIELDS.map(field=>field[1]).join(' '),funTitles:searchIndex.funTitles+' '+Object.values(TITLE_LABEL).join(' '),cityEvents:searchIndex.cityEvents+' '+CITY_FIELDS.map(field=>field[1]).join(' ')+' '+Object.values(CITY_LABEL).join(' ') }))
async function navigate(id) { if(!pageVisible(identity.value,id))return; auditVersion.value=null;tab.value = id; await nextTick(); window.scrollTo({ top:0, behavior:motionEnabled.value?'smooth':'auto' }) }
async function focusTile(index) {
  selectedTile.value = index
  await nextTick()
  const row = document.querySelector('[data-rule-page="' + tab.value + '"] [data-tile-row="' + index + '"]')
  row?.scrollIntoView({ block: 'center', behavior: motionEnabled.value ? 'smooth' : 'auto' }); row?.querySelector('input')?.focus({ preventScroll: true })
}
async function focusValidation(error) {
  const routes = [['startPick',/起点三选一|起点抽卡/],['roundReward',/轮次奖励|月度荣耀|收入来源/],['cityEvents',/城市事件|预告|建设节|旅游旺季|安居日/],['funTitles',/称号/],['series',/系列赛|积分/],['team',/组队|援助|队友/],['rentRise',/租金上涨/],['sets',/同组|分组|组号/],['names',/地名|命名|逐格名称/],['events',/事件概率|道具概率|权重|道具上限|手牌上限|幸运|不幸/],['money',/事件金额|固定费|起点奖励|出狱费用|小游戏.*奖励/],['prices',/低价|中价|高价|车站|档位|地产.*租金/],['announcement',/公告/],['timing',/操作时限|动画留时|动画展示|托管|拍卖.*秒/],['room',/房间|快速模式|初始现金|投骰/]]
  const id = sectionForField(String(error).split(/[ ：:]/)[0]) || routes.find(([,expression]) => expression.test(error))?.[0]
  if (!id) return
  tab.value = id; await nextTick()
  const section = document.querySelector(`[data-rule-page="${id}"]`)
  const fields = [...(section?.querySelectorAll('.el-form-item') || [])]
  const target = fields.find(field => { const label = field.querySelector('.el-form-item__label')?.textContent?.trim(); return label && error.includes(label) }) || fields[0] || section
  document.querySelectorAll('.validation-focus').forEach(field => field.classList.remove('validation-focus'))
  target?.classList.add('validation-focus'); target?.scrollIntoView({ block:'center',behavior:motionEnabled.value?'smooth':'auto' }); target?.querySelector('input,textarea,button')?.focus({preventScroll:true})
  clearTimeout(validationTimer); validationTimer=setTimeout(()=>target?.classList.remove('validation-focus'),3000)
}
watch(boardId, () => { selectedTile.value = 0 })
function beforeLeave(event) { if (dirty.value || characterDirty.value || audioDirty.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeLeave))
onBeforeUnmount(() => { window.removeEventListener('admin-access-error',handleAccessError);document.removeEventListener('visibilitychange',resumed);window.removeEventListener('beforeunload', beforeLeave); clearTimeout(celebrationTimer); clearTimeout(validationTimer) })

onMounted(() => {
  window.addEventListener('admin-access-error',handleAccessError);document.addEventListener('visibilitychange',resumed)
  if (loggedIn.value) enterAdmin()
})
</script>

<template>
  <LoginPage v-if="!loggedIn" :loading="loading" :error="loginError" :success="loginSuccess" @submit="login" @enter="enterAdmin" @clear-error="loginError=''" />
  <PasswordChange v-else-if="identity?.mustChangePassword" mandatory @changed="signedout" @error="fail" @cancel="logout" />
  <AdminShell v-else :identity="identity" :active-page="tab" :version="tab==='audio'?audioVersion:active?.configId" :dirty="dirty || characterDirty || audioDirty" :search-index="parameterSearchIndex" :celebration="celebration" :busy="loading || dashLoading" :has-error="!!errors.length" @navigate="navigate" @logout="logout">
    <AdminAccessManager v-if="isAccessPage && identity" :page="tab" :identity="identity" @error="fail" @signedout="signedout" @compare="openAuditVersion" />
    <div v-show="!isAccessPage">
    <el-alert v-if="errors.length && !['characters','audio'].includes(tab)" class="validation-alert" type="error" :closable="false" title="请修改后再发布"><ul><li v-for="error in errors" :key="error"><button type="button" class="error-link" @click="focusValidation(error)">{{ error }}</button></li></ul></el-alert>
    <div class="rule-workspace" :class="{ 'with-live-preview': form && isConfigPage && !isBoardPage && tab!=='announcement' }">
    <div v-if="form && isConfigPage && !canEdit"><ReadOnlyConfig :section="tab" :settings="form" /><template v-if="isBoardPage"><el-radio-group v-model="boardId" class="gap"><el-radio-button v-for="(_,id) in form.tileNames" :key="id" :value="id">{{ BOARD_LABEL[id] || id }}</el-radio-button></el-radio-group><BoardPreview :tiles="boards.find(board=>board.id===boardId)?.tiles || []" :names="form.tileNames?.[boardId]" :groups="form.sets?.groups?.[boardId]" :selected="selectedTile" :group-mode="tab==='sets'" :board-id="boardId" @select="selectedTile=$event" /></template></div>
    <div v-if="form && (!isConfigPage || canEdit)" class="editor-grid" :class="{ 'has-board': isBoardPage }" v-loading="loading">
      <div class="editor-content"><section v-show="tab === 'characters'" data-rule-page="characters" class="special-page"><KeepAlive><CharacterManager :read-only="!can(identity,'character:write')" :can-rollback="can(identity,'character:rollback')" v-if="tab === 'characters'" @update:dirty="characterDirty=$event" @published="celebrate" @unauthorized="fail({status:401})" /></KeepAlive></section>
      <section v-show="tab === 'audio'" data-rule-page="audio" class="special-page"><KeepAlive><AudioManager v-if="tab === 'audio'" :read-only="!can(identity,'audio:write')" :can-publish="can(identity,'audio:publish')" :can-rollback="can(identity,'audio:rollback')" @version="audioVersion=$event" @update:dirty="audioDirty=$event" @published="celebrate" @unauthorized="fail({status:401})" /></KeepAlive></section>
<section v-show="tab === 'names'" data-rule-page="names" class="surface field-panel"><div class="field-panel-title"><span>土地命名</span><small>修改只在发布后生效</small></div>
        <el-radio-group v-model="boardId" class="gap">
          <el-radio-button v-for="(_, id) in form.tileNames" :key="id" :value="id">{{ BOARD_LABEL[id] || id }}</el-radio-button>
        </el-radio-group>
        <p class="muted">每格最多 8 个字。格子类型与顺序由地图决定，这里只改显示名称。</p>
        <div class="names">
          <div v-for="t in tiles" :key="boardId + t.index" class="name-row" :class="{ highlighted: selectedTile === t.index }" :data-tile-row="t.index">
            <span class="idx">{{ t.index }}</span>
            <span class="type">{{ tileType(t.tile) }}</span>
            <el-input v-model="form.tileNames[boardId][t.index]" maxlength="8" />
          </div>
        </div>
      </section>
<section v-show="tab === 'sets'" data-rule-page="sets" class="surface field-panel"><div class="field-panel-title"><span>同组地产</span><small>修改只在发布后生效</small></div>
        <el-form v-if="form.sets" label-width="160px" class="narrow">
          <p class="muted">同一组的普通地产全部归同一个人、且都没有抵押时，组内每块地的租金按倍率上涨（填 100% 即关闭）。组号填 0 表示不分组；同一组号的地就是一组，每组至少 2 块。车站与功能格不能分组。</p>
          <el-form-item label="集齐后租金倍率（%）"><el-input-number v-model="form.sets.rentPercent" :min="100" :max="500" :step="10" /></el-form-item>
        </el-form>
        <el-radio-group v-model="boardId" class="gap">
          <el-radio-button v-for="(_, id) in form.tileNames" :key="id" :value="id">{{ BOARD_LABEL[id] || id }}</el-radio-button>
        </el-radio-group>
        <div v-if="form.sets && form.sets.groups[boardId]" class="names">
          <div v-for="t in tiles.filter((x) => x.tile && x.tile.type === 'PROPERTY')" :key="'g' + boardId + t.index" class="name-row" :class="{ highlighted: selectedTile === t.index }" :data-tile-row="t.index">
            <span class="idx">{{ t.index }}</span>
            <span class="type">{{ form.tileNames[boardId][t.index] }}</span>
            <el-input-number v-model="form.sets.groups[boardId][t.index]" :min="0" :max="99" size="small" />
            <span class="muted">{{ groupMates(t.index) }}</span>
          </div>
        </div>
      </section>
<section v-show="tab === 'prices'" data-rule-page="prices" class="surface field-panel"><div class="field-panel-title"><span>价格与租金</span><small>修改只在发布后生效</small></div>
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
      </section>
<section v-show="tab === 'events'" data-rule-page="events" class="surface field-panel"><div class="field-panel-title"><span>事件概率</span><small>修改只在发布后生效</small></div>
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
      </section>
<section v-show="tab === 'money'" data-rule-page="money" class="surface field-panel"><div class="field-panel-title"><span>金额与固定费用</span><small>修改只在发布后生效</small></div>
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
      </section>
<section v-show="tab === 'room'" data-rule-page="room" class="surface field-panel"><div class="field-panel-title"><span>建房选项</span><small>修改只在发布后生效</small></div>
        <el-form v-if="form.room" label-width="160px" class="narrow">
          <p class="muted">房主建房时能选的项与默认值。每组 1～5 个，用逗号分隔，保存时按从小到大排列；默认值必须是其中一个。只影响发布之后新建的房间。</p>
          <h3>动画模式</h3>
          <el-form-item label="允许快速模式"><el-switch v-model="form.room.fastModeEnabled" @change="(v) => { if (!v) form.room.defaultFastMode = false }" /><span class="hint">房主开局前选择，全员统一，开局后不可切换</span></el-form-item>
          <el-form-item label="默认快速模式"><el-switch v-model="form.room.defaultFastMode" :disabled="!form.room.fastModeEnabled" /></el-form-item>
          <el-form-item label="快速动画时长比例"><el-input-number v-model="form.room.fastAnimationPercent" :min="10" :max="100" /><span class="hint">% · 50 表示动画用普通模式一半时间，操作时限不变</span></el-form-item>
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
      </section>
<section v-show="tab === 'rentRise'" data-rule-page="rentRise" class="surface field-panel"><div class="field-panel-title"><span>租金上涨</span><small>修改只在发布后生效</small></div>
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
      </section>
<section v-show="tab === 'startPick'" data-rule-page="startPick" class="surface field-panel"><div class="field-panel-title"><span>起点三选一</span><small>修改只在发布后生效</small></div>
        <el-form v-if="form.startPick" label-width="160px" class="narrow">
          <p class="muted">玩家前进经过或停在起点时，先在起点暂停，从三张背面朝上的牌里选一张，抽完再继续剩余步数。每张牌按权重决定是现金还是道具，现金在范围内按步长随机，道具按「道具概率」抽取。两个权重都为 0 时关闭；关闭时经过起点只发起点奖励。后退经过起点不抽卡。</p>
          <el-form-item label="现金权重"><el-input-number v-model="form.startPick.cashWeight" :min="0" :max="1000" /></el-form-item>
          <el-form-item label="道具权重"><el-input-number v-model="form.startPick.cardWeight" :min="0" :max="1000" /><span class="hint">{{ startPickHint }}</span></el-form-item>
          <el-form-item label="现金最小值"><el-input-number v-model="form.startPick.cashMin" :min="1" :max="1000000" :step="100" /></el-form-item>
          <el-form-item label="现金最大值"><el-input-number v-model="form.startPick.cashMax" :min="1" :max="1000000" :step="100" /></el-form-item>
          <el-form-item label="现金步长"><el-input-number v-model="form.startPick.cashStep" :min="1" :max="1000000" :step="50" /><span class="hint">须整除（最大值 − 最小值）</span></el-form-item>
        </el-form>
      </section>
<section v-show="tab === 'roundReward'" data-rule-page="roundReward" class="surface field-panel"><div class="field-panel-title"><span>轮次奖励</span><small>修改只在发布后生效</small></div>
        <el-alert class="gap" :closable="false" title="第 N 整轮完成后一次性发放。累计实际玩法收入，排除开局资金、抵押借款、资产买卖和奖励本身；只给存活玩家，提醒结束后开始下一位投骰。" />
        <el-form label-width="220px" class="narrow">
          <el-form-item label="启用收入奖励"><el-switch v-model="form.roundReward.enabled" /></el-form-item>
          <el-form-item label="奖励显示名称"><el-input v-model="form.roundReward.name" maxlength="12" /><span class="hint">例如“月度荣耀奖励”，与局时或半程无关</span></el-form-item>
          <el-form-item label="限时模式奖励轮次"><el-input-number v-model="form.roundReward.timeLimitRewardRound" :min="1" :max="1000" /></el-form-item>
          <el-form-item label="破产模式奖励轮次"><el-input-number v-model="form.roundReward.bankruptcyRewardRound" :min="1" :max="1000" /></el-form-item>
          <el-form-item label="奖励比例（%）"><el-input-number v-model="form.roundReward.rewardPercent" :min="0" :max="100" /></el-form-item>
          <el-form-item label="每人封顶"><el-input-number v-model="form.roundReward.perPlayerCap" :min="0" :max="1000000" /></el-form-item>
          <el-form-item label="向下取整单位"><el-select v-model="form.roundReward.roundingUnit"><el-option v-for="n in [1,10,50,100]" :key="n" :label="n" :value="n" /></el-select></el-form-item>
          <el-form-item label="收入来源"><el-checkbox-group v-model="form.roundReward.incomeSources"><el-checkbox v-for="(label,key) in INCOME_LABEL" :key="key" :value="key">{{ label }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item label="提醒展示（毫秒）"><el-input-number v-model="form.roundReward.presentationMs" :min="1000" :max="10000" /></el-form-item>
          <el-form-item label="计算预览：累计收入"><el-input-number v-model="rewardIncome" :min="0" :max="100000000" /><span class="hint">奖励 +{{ rewardPreview }}</span></el-form-item>
        </el-form>
      </section>
<section v-show="tab === 'cityEvents'" data-rule-page="cityEvents" class="surface field-panel"><div class="field-panel-title"><span>城市事件</span><small>修改只在发布后生效</small></div>
        <el-alert class="gap" :closable="false" title="整轮交界随机触发，至多一个事件，不叠加。有事件时跳过新抽取。租金顺序：基础→同组→轮次上涨→城市倍率（向下取整到10）；建设折扣不改变资产标准价值。" />
        <el-form label-width="240px" class="narrow">
          <el-form-item label="启用城市事件"><el-switch v-model="form.cityEvents.enabled" /></el-form-item>
          <el-form-item label="适用模式"><el-checkbox-group v-model="form.cityEvents.allowedModes"><el-checkbox value="TIME_LIMIT">限时</el-checkbox><el-checkbox value="BANKRUPTCY">破产</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item v-for="[key,label,min,max] in CITY_FIELDS" :key="key" :label="label"><el-input-number v-model="form.cityEvents[key]" :min="min" :max="max" /></el-form-item>
        </el-form>
        <p class="hint">首次成功后：第 {{ form.cityEvents.firstCheckRound + form.cityEvents.advanceRounds }} 轮开始生效。有效权重决定被选中的相对概率，全关闭或权重为0不触发。</p>
        <el-table :data="form.cityEvents.pool">
          <el-table-column label="效果" width="150"><template #default="{row}">{{ CITY_LABEL[row.kind] }}</template></el-table-column>
          <el-table-column label="启用" width="80"><template #default="{row}"><el-switch v-model="row.enabled" /></template></el-table-column>
          <el-table-column label="显示名称"><template #default="{row}"><el-input v-model="row.name" maxlength="12" /></template></el-table-column>
          <el-table-column label="权重"><template #default="{row}"><el-input-number class="cell-num" v-model="row.weight" :min="0" :max="1000" /></template></el-table-column>
          <el-table-column label="费用/租金倍率%"><template #default="{row}"><el-input-number class="cell-num" v-model="row.multiplierPercent" :min="1" :max="300" /></template></el-table-column>
          <el-table-column label="持续整轮数"><template #default="{row}"><el-input-number class="cell-num" v-model="row.durationRounds" :min="1" :max="20" /></template></el-table-column>
          <el-table-column label="插画"><template #default="{row}"><el-select v-model="row.artworkKey"><el-option v-for="(key,kind) in CITY_ART" :key="key" :value="key" :label="CITY_LABEL[kind]" /></el-select></template></el-table-column>
        </el-table>
      </section>
<section v-show="tab === 'funTitles'" data-rule-page="funTitles" class="surface field-panel"><div class="field-panel-title"><span>结算称号</span><small>修改只在发布后生效</small></div>
        <el-alert class="gap" :closable="false" title="称号只作展示，不发现金。收租王按实际收租，地产大亨按同时持有峰值，幸运之星按事件与小游戏收入，逆风翻盘比较奖励轮次前的名次与最终名次；并列共同获奖。" />
        <el-switch v-model="form.funTitles.enabled" active-text="启用趣味称号" />
        <el-table :data="form.funTitles.titles">
          <el-table-column label="指标" width="150"><template #default="{row}">{{ TITLE_LABEL[row.kind] }}</template></el-table-column>
          <el-table-column label="启用" width="80"><template #default="{row}"><el-switch v-model="row.enabled" /></template></el-table-column>
          <el-table-column label="显示名称"><template #default="{row}"><el-input v-model="row.name" maxlength="12" /></template></el-table-column>
          <el-table-column label="最低有效金额/数量/名次提升"><template #default="{row}"><el-input-number v-model="row.minimum" :min="1" :max="row.kind === 'COMEBACK' ? 7 : 1000000" /></template></el-table-column>
        </el-table>
      </section>
<section v-show="tab === 'series'" data-rule-page="series" class="surface field-panel"><div class="field-panel-title"><span>好友系列赛</span><small>修改只在发布后生效</small></div>
        <el-alert class="gap" :closable="false" title="首局开始时锁定成员与房间规则；每局现金、土地、手牌重置。积分只决定系列名次，不发现金。中止局不计分，退出保留已有积分。" />
        <el-form label-width="160px" class="narrow">
          <el-form-item label="开放系列赛"><el-switch v-model="form.series.enabled" /></el-form-item>
          <el-form-item label="可选局数"><el-select v-model="form.series.gameOptions" multiple><el-option v-for="n in 19" :key="n" :label="(n+1)+'局'" :value="n+1" /></el-select></el-form-item>
          <el-form-item label="默认局数"><el-select v-model="form.series.defaultGames"><el-option v-for="n in form.series.gameOptions" :key="n" :label="n+'局'" :value="n" /></el-select></el-form-item>
          <el-form-item v-for="(_,i) in form.series.rankPoints" :key="i" :label="'第'+(i+1)+'名积分'"><el-input-number v-model="form.series.rankPoints[i]" :min="0" :max="1000" /></el-form-item>
          <el-form-item label="单局并列计分"><el-select v-model="form.series.tieMode"><el-option label="并列名次各拿该名次积分" value="SHARED_RANK" /><el-option label="平分占用名次积分（向下取整）" value="SPLIT_OCCUPIED" /></el-select></el-form-item>
          <el-form-item label="允许机器人参赛"><el-switch v-model="form.series.allowBots" /></el-form-item>
          <el-form-item label="下一局最少人数"><el-input-number v-model="form.series.minPlayers" :min="2" :max="8" /></el-form-item>
          <p class="hint">累计积分相同即并列冠军，不额外比较胜场或净资产。普通单局仍是建房默认选项。</p>
        </el-form>
      </section>
<section v-show="tab === 'team'" data-rule-page="team" class="surface field-panel"><div class="field-panel-title"><span>组队合作</span><small>修改只在发布后生效</small></div>
        <el-alert class="gap" type="info" :closable="false" title="个人资金和产权独立，队友免租，可自愿援助。发布参数后新建房间生效，进行中的房间继续使用原参数。默认个人对战。" />
        <el-form v-if="form.team" label-width="190px" class="narrow">
          <el-form-item label="开放组队"><el-switch v-model="form.team.enabled" /><span class="hint">房主建房后可选择组队，首局开始后队伍固定；与个人系列赛互斥</span></el-form-item>
          <el-form-item label="允许的组队形式"><el-select v-model="form.team.teamSizeOptions" multiple><el-option v-for="n in [2,3,4]" :key="n" :label="n+'v'+n" :value="n" /></el-select></el-form-item>
          <el-form-item label="默认组队形式"><el-select v-model="form.team.defaultTeamSize"><el-option v-for="n in form.team.teamSizeOptions" :key="n" :label="n+'v'+n" :value="n" /></el-select></el-form-item>
          <el-form-item label="允许机器人"><el-switch v-model="form.team.allowBots" /><span class="hint">同时遵守已有机器人总开关；首版机器人不自动援助</span></el-form-item>
          <el-form-item label="队友土地/车站免租"><el-switch v-model="form.team.allyRentWaiver" /></el-form-item>
          <el-form-item label="队友通过路障"><el-switch v-model="form.team.allyRoadblockPass" /></el-form-item>
          <el-form-item label="开放资金援助"><el-switch v-model="form.team.aidEnabled" /></el-form-item>
          <el-form-item label="援助单笔上限"><el-input-number v-model="form.team.aidMaxAmount" :min="1" :max="1000000" /><span class="hint">还受本人未冻结的可用现金限制</span></el-form-item>
          <el-form-item label="主动援助每回合次数"><el-input-number v-model="form.team.aidPerTurn" :min="1" :max="10" /><span class="hint">本人投骰前（含狱中判定）；不占用卡机会，欠款求助另按缺口和债务窗口限制</span></el-form-item>
          <el-form-item label="援助快捷金额"><el-input :model-value="drafts.teamQuickAmounts ?? form.team.aidQuickAmounts.join(', ')" @update:model-value="drafts.teamQuickAmounts=$event" @blur="commitTeamQuickAmounts" @keyup.enter="commitTeamQuickAmounts" /><span class="hint">1～6项，逗号分隔，不重复、不超过单笔上限</span></el-form-item>
          <el-form-item label="开放欠款援助"><el-switch v-model="form.team.debtAidEnabled" /><span class="hint">仅在资金援助开放时生效，队友自愿出资</span></el-form-item>
          <el-form-item label="欠款援助等待（毫秒）"><el-input-number v-model="form.team.debtAidWindowMs" :min="1000" :max="120000" :step="1000" /><span class="hint">求助不能延长倒计时，清算后不接受援助</span></el-form-item>
          <p class="hint">30格只容纳2v2；50格可容纳2v2/3v3/4v4。按队伍净资产结算，个人破产后队友继续。援助不计累计玩法收入与月度奖励。</p>
        </el-form>
        <p v-else class="muted">当前服务端未下发组队参数，请升级服务端后配置；不使用浏览器自设默认金额。</p>
      </section>
<section v-show="tab === 'timing'" data-rule-page="timing" class="surface field-panel"><div class="field-panel-title"><span>操作时限</span><small>修改只在发布后生效</small></div>
        <el-form v-if="form.timing" label-width="170px" class="narrow">
          <p class="muted">各类弹窗的倒计时与动画留时。投骰时间在「建房选项」里配置。只影响发布之后新建的房间。</p>
          <el-form-item v-for="f in TIMING_FIELDS" :key="f[0]" :label="f[1]">
            <el-input-number v-model="form.timing[f[0]]" :min="f[2]" :max="f[3]" :step="f[4] === '毫秒' ? 50 : 1" />
            <span class="hint">{{ f[4] }}{{ f[5] ? ' · ' + f[5] : '' }}</span>
          </el-form-item>
        </el-form>
      </section>
<section v-show="tab === 'announcement'" data-rule-page="announcement" class="surface field-panel"><div class="field-panel-title"><span>公告</span><small>修改只在发布后生效</small></div>
        <el-form v-if="form.announcement" label-width="120px" class="narrow">
          <p class="muted">发布后，大厅顶部显示这条公告（玩家可以关掉，本次打开不再显示）。用于维护通知、活动说明等。</p>
          <el-form-item label="显示公告"><el-switch v-model="form.announcement.enabled" /></el-form-item>
          <el-form-item label="标题"><el-input v-model="form.announcement.title" maxlength="20" show-word-limit placeholder="如：周末活动" /></el-form-item>
          <el-form-item label="内容"><el-input v-model="form.announcement.text" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="如：本周末经过起点奖励翻倍！" /></el-form-item>
        </el-form>
      </section>
<section v-show="tab === 'dashboard'" data-rule-page="dashboard" class="special-page"><DashboardOverview v-if="tab === 'dashboard'" :data="dash" :loading="dashLoading" :days="dashDays" :labels="{modes:MODE_LABEL,reasons:REASON_LABEL,boards:BOARD_LABEL,events:EVENT_NAMES,cards:CARD_LABEL}" :misc-labels="MISC_LABEL" @update:days="dashDays=$event; loadDash()" @refresh="loadDash" /></section>
<section v-show="tab === 'history'" data-rule-page="history" class="surface field-panel"><KeepAlive><VersionCenter :focus-version="auditVersion" :can-edit="canEdit" :can-rollback="can(identity,'config:rollback')" v-if="tab==='history'" :active="active" :history="history" :dirty="dirty" @view="view" @rolledback="load" @error="fail" /></KeepAlive></section></div>
      <BoardPreview v-if="isBoardPage" :tiles="boards.find(board=>board.id===boardId)?.tiles || []" :names="form.tileNames?.[boardId]" :groups="form.sets?.groups?.[boardId]" :selected="selectedTile" :group-mode="tab==='sets'" :board-id="boardId" @select="focusTile" />
    </div>
    <el-empty v-else-if="!form && !loading" description="无法读取管理配置"><el-button @click="load">重新加载</el-button></el-empty>
    <RulePreview v-if="form && isConfigPage && !isBoardPage" :section="tab" :settings="form" :original="originalSettings" :labels="{ tiers:TIER_LABEL,events:EVENT_LABEL,cards:CARD_LABEL,income:INCOME_LABEL,city:CITY_LABEL,titles:TITLE_LABEL }" :rent-steps="rentSteps" :reward-income="rewardIncome" :timing-fields="TIMING_FIELDS" />
    </div>
    <section v-if="form && tab==='announcement'" class="announcement-preview surface"><span class="eyebrow">游戏大厅 · 公告预览</span><div class="notice-card" :class="{ muted: !form.announcement.enabled }"><strong>{{ form.announcement.title || '公告标题' }}</strong><p>{{ form.announcement.text || '编辑公告后，在这里预览游戏内展示内容。' }}</p><el-tag>{{ form.announcement.enabled ? '发布后展示' : '公告已关闭' }}</el-tag></div></section>
    <div v-if="form && isConfigPage && canEdit" class="config-dock"><div><span class="status-dot" :class="{ clean: !dirty }" /><strong>{{ dirty ? '有未发布修改' : '配置与生效版本一致' }}</strong><small>{{ changes.length }} 项变更</small></div><div class="dock-actions"><el-button @click="useDefaults">填入默认值</el-button><el-button @click="discard">放弃修改</el-button><el-button @click="validate">校验</el-button><el-button type="primary" :disabled="loading" @click="publish">查看变更并发布</el-button></div><BorderBeam v-if="dirty" /></div>
    <el-dialog v-model="releaseOpen" title="发布配置" width="min(960px, 94vw)" class="version-dialog release-review-dialog" :close-on-click-modal="!releaseBusy" :close-on-press-escape="!releaseBusy" :show-close="!releaseBusy" append-to-body>
      <el-steps :active="releaseStage==='review' ? 0 : releaseStage==='validating' || releaseStage==='invalid' ? 1 : releaseStage==='publishing' ? 2 : 3" align-center finish-status="success"><el-step title="检查变更" /><el-step title="校验配置" /><el-step title="发布" /><el-step title="结果" /></el-steps>
      <p class="muted">新开的房间使用本次参数，进行中的对局保持原版本。</p>
      <VersionDiff v-if="releaseReview" :key="releaseReviewKey" :groups="releaseReview.groups" :before-label="versionName({configId:releaseReview.expectedActiveId})+'（参考版本）'" after-label="本次待发布" empty-description="当前没有参数变化，发布将生成一个新的配置版本。" />
      <el-input v-model="releaseNote" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="这次改了什么（选填）" :disabled="releaseBusy || releaseStage==='success'" />
      <el-alert v-if="releaseStage==='invalid'" type="error" title="校验未通过，关闭此窗口后修改页面上的错误" :closable="false" /><el-alert v-if="releaseStage==='failed'" type="error" :title="releaseStale?'生效版本已更新，草稿仍保留。请重新核对生效版本，查看整份草稿相对新版本的差异后再确认。':'发布失败，修改仍保留在编辑区，可重试'" :closable="false" /><el-alert v-if="releaseStage==='success'" type="success" title="发布成功，生效版本已更新" :closable="false" />
      <template #footer><el-button :disabled="releaseBusy" @click="releaseOpen=false">{{ releaseStage==='success'?'完成':'返回编辑' }}</el-button><el-button v-if="releaseStale" :loading="releaseBusy" @click="recheckRelease">重新核对生效版本</el-button><el-button v-if="releaseStage!=='success'" type="primary" :disabled="releaseStale" :loading="releaseBusy" @click="confirmPublish">{{ releaseStage==='validating'?'正在校验':releaseStage==='publishing'?'正在发布':'确认发布' }}</el-button></template>
    </el-dialog>
    </div>
  </AdminShell>
</template>
