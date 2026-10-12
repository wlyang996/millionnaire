import { fieldLabels } from './fields.js'
import { pages, pageById, sectionForField } from './navigation.js'

const names = {
  LOW:'低价地产', MID:'中价地产', HIGH:'高价地产', TIME_LIMIT:'限时模式', BANKRUPTCY:'破产模式',
  CASH_REWARD:'现金奖励', CASH_FINE:'现金罚款', CARD:'获得道具', MOVE:'随机移动', JAIL:'进监狱', BUILD:'建造', DOWNGRADE:'降级', TO_STATION:'前往随机车站', TO_START:'回到起点',
  ROADBLOCK:'路障', RENT_WAIVER:'免租', FIXED_MOVE:'定点移动', JAIL_RELEASE:'出狱', AUCTION:'拍卖', TRADE:'交易', REFUSE_PURCHASE:'拒绝购买', HOUSE_PROTECTION:'房屋保护', QUERY:'查询', FORCED_PURCHASE:'强制购房', DEMOLISH:'拆楼', CLEAR_LAND:'清地',
  RENT:'实际收租', START:'起点奖励', EVENT:'现金事件', MINIGAME:'小游戏奖励', COMMISSION:'拍卖佣金',
  RENT_KING:'收租王', PROPERTY_TYCOON:'地产大亨', LUCKY_STAR:'幸运之星', COMEBACK:'逆风翻盘',
  UPGRADE_DISCOUNT:'建设节', STATION_RENT:'旅游旺季', PROPERTY_RENT:'安居日',
  SHARED_RANK:'并列名次各拿该名次积分', SPLIT_OCCUPIED:'平分占用名次积分（向下取整）',
  'classic-30':'30 格地图', 'classic-50':'50 格地图',
}
const labels = {
  ...fieldLabels,
  'room.initialCashOptions':'初始现金选项', 'room.defaultInitialCash':'默认初始现金',
  'room.timeLimitMinutesOptions':'限时时长选项', 'room.rollSecondsOptions':'投骰时限选项', 'room.defaultRollSeconds':'默认投骰时限',
  'room.fastAnimationPercent':'快速动画时长比例', 'team.aidQuickAmounts':'援助快捷金额',
  'cityEvents.firstCheckRound':'首次检查轮次', 'cityEvents.checkEveryRounds':'检查间隔', 'cityEvents.triggerPercent':'触发概率', 'cityEvents.advanceRounds':'预告提前轮数',
  'cityEvents.announcementMs':'预告展示时长', 'cityEvents.activationMs':'生效提示时长', 'cityEvents.endMs':'结束提示时长',
  'cityEvents.pool.*.enabled':'启用事件', 'cityEvents.pool.*.multiplierPercent':'费用 / 租金倍率', 'cityEvents.pool.*.durationRounds':'持续轮数', 'cityEvents.pool.*.artworkKey':'插画资源',
  'funTitles.enabled':'开放结算称号', 'funTitles.titles.*.minimum':'最低有效金额 / 数量 / 名次提升',
  'lucky.*.amount':'奖励 / 罚款金额', 'lucky.*.label':'事件名称', 'lucky.*.kind':'事件类型', 'lucky.*.unlucky':'所属奖池',
  'tiers.*.tier':'地产档位', 'cityEvents.pool.*.kind':'事件类型', 'funTitles.titles.*.kind':'称号类型',
  'timing.decisionSeconds':'买地 / 升级等选择', 'timing.responseSeconds':'响应卡询问', 'timing.discardSeconds':'弃牌时限', 'timing.tradeSeconds':'交易回应', 'timing.toothSeconds':'拔牙每次选择',
  'timing.auctionSeconds':'拍卖时长', 'timing.auctionExtendSeconds':'末尾出价顺延', 'timing.auctionMaxSeconds':'拍卖最长时长', 'timing.debtSegmentSeconds':'欠款每段时限',
  'timing.animDiceMs':'投骰动画留时', 'timing.animPerStepMs':'每格移动时长', 'timing.autoActDelayMs':'托管代操作等待', 'timing.allAwayTurns':'全员挂机结束回合',
  'timing.eventPresentationMs':'事件翻牌展示', 'timing.eventCashPresentationMs':'现金事件展示', 'timing.startPickPresentationMs':'起点抽卡展示', 'timing.jailPresentationMs':'入狱动画展示',
}
const labelPatterns = Object.entries(labels).filter(([, label]) => !['label','f[1]'].includes(label)).map(([path,label]) => [new RegExp('^'+path.split('.').map(part=>part==='*'?'[^.]+':part.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('\\.')+'$'),label])

function keyedArray(path, values) {
  const field = path==='tiers'?'tier':['lucky','cityEvents.pool','funTitles.titles'].includes(path)?'kind':null
  if (!field || !Array.isArray(values)) return values
  return Object.fromEntries(values.map((entry,index)=>[entry?.[field] ? (path==='lucky'?(entry.unlucky?'unlucky:':'lucky:'):'')+entry[field] : '@'+index,entry]))
}
function flatten(value, path='', out={}) {
  value=keyedArray(path,value)
  if (value && typeof value==='object' && (!Array.isArray(value) || path.endsWith('.rents') || path==='series.rankPoints' || path.startsWith('tileNames.') || path.startsWith('sets.groups.'))) {
    for (const [key, child] of Object.entries(value)) flatten(child,path?path+'.'+key:key,out)
  } else out[path]=value
  return out
}
const numberText = value => Number(value.toFixed(4)).toLocaleString('zh-CN',{maximumFractionDigits:4})
function unitFor(path) {
  const title=path.match(/^funTitles\.titles\.([^.]+)\.minimum$/)?.[1]
  if (title) return {RENT_KING:'金币',PROPERTY_TYCOON:'处地产 / 车站',LUCKY_STAR:'金币',COMEBACK:'名'}[title] || ''
  if (/Percent$/.test(path)) return '%'
  if (/Ms$/.test(path)) return '毫秒'
  if (/Seconds(?:Options)?$/.test(path)) return '秒'
  if (/Minutes(?:Options)?$/.test(path)) return '分钟'
  if (/Rounds?$/.test(path) || /RewardRound$/.test(path)) return '轮'
  if (path==='timing.allAwayTurns') return '回合'
  if (/Games$|gameOptions$/.test(path)) return '局'
  if (path==='team.aidPerTurn') return '次 / 回合'
  if (path==='series.minPlayers') return '人'
  if (path==='handLimit') return '张'
  if (path.startsWith('series.rankPoints.')) return '分'
  if (/^(tiers\.[^.]+\.(basePrice|upgradeCost|rents\.[0-9]+)|station\.|fees\.|eventCash\.)/.test(path) || /^startPick.cash(Min|Max|Step)$/.test(path) || /^lucky\.[^.]+\.amount$/.test(path) || ['room.initialCashOptions','room.defaultInitialCash','roundReward.perPlayerCap','roundReward.roundingUnit','team.aidMaxAmount','team.aidQuickAmounts'].includes(path)) return '金币'
  return ''
}
function valueText(value,path) {
  if (value===undefined || value===null) return '未设置（按服务端兼容规则）'
  if (Array.isArray(value)) return value.length?value.map(item=>valueText(item,path)).join('、'):'空列表'
  if (typeof value==='boolean') return path.endsWith('.unlucky')?(value?'不幸奖池':'幸运奖池'):value?'启用':'关闭'
  if (typeof value==='number') {
    if (['team.teamSizeOptions','team.defaultTeamSize'].includes(path)) return value+'v'+value
    return value.toLocaleString('zh-CN')+(unitFor(path)?' '+unitFor(path):'')
  }
  return names[value] || (value===''?'空文本':String(value))
}
function fieldName(path,before,after) {
  const parts=path.split('.'), label=labels[path] || labelPatterns.find(([pattern])=>pattern.test(path))?.[1] || parts.at(-1)
  const model=after || before || {}
  if (path.startsWith('tileNames.') || path.startsWith('sets.groups.')) return `${names[parts.at(-2)] || parts.at(-2)} · 第 ${parts.at(-1)} 格 · ${path.startsWith('tileNames.')?'土地名称':'组号'}`
  if (path.startsWith('tiers.')) return `${names[parts[1]] || parts[1]} · ${parts[2]==='rents'?['未升级','一级','二级','三级'][Number(parts[3])]+'租金':label}`
  if (/^(eventWeights|cardWeights)\./.test(path)) return `${parts[0]==='eventWeights'?({BUILD:'免费升级一块地',DOWNGRADE:'自己一块地降级'}[parts[1]] || names[parts[1]] || parts[1]):names[parts[1]] || parts[1]} · ${label}`
  if (parts[0]==='lucky') {
    const entry=[...(after?.lucky||[]),...(before?.lucky||[])].find(item=>(item.unlucky?'unlucky:':'lucky:')+item.kind===parts[1])
    return `${parts[1].startsWith('unlucky:')?'不幸':'幸运'} · ${entry?.label || names[entry?.kind] || parts[1]} · ${label}`
  }
  if (parts[1]==='pool' || parts[1]==='titles') {
    const entry=model[parts[0]]?.[parts[1]]?.find(item=>item.kind===parts[2]) || before?.[parts[0]]?.[parts[1]]?.find(item=>item.kind===parts[2])
    const metric=parts[0]==='funTitles' && parts[3]==='minimum'?{RENT_KING:'最低累计收租',PROPERTY_TYCOON:'最低地产与车站峰值合计',LUCKY_STAR:'最低事件与小游戏收入',COMEBACK:'最低名次提升'}[parts[2]]:null
    return `${entry?.name || names[parts[2]] || parts[2]} · ${metric || label}`
  }
  if (path.startsWith('series.rankPoints.')) return `第 ${Number(parts[2])+1} 名积分`
  return label
}
function probabilities(settings) {
  const out={}
  function pool(entries) {
    const total=entries.reduce((sum,item)=>sum+item.weight,0)
    for (const item of entries) out[item.path]=total>0?item.weight*100/total:null
  }
  for (const key of ['eventWeights','cardWeights']) if (settings?.[key]) pool(Object.entries(settings[key]).map(([kind,weight])=>({path:key+'.'+kind,weight:Number(weight)||0})))
  if (settings?.lucky) for (const unlucky of [false,true]) pool(settings.lucky.filter(item=>!!item.unlucky===unlucky).map(item=>({path:`lucky.${unlucky?'unlucky:':'lucky:'}${item.kind}.weight`,weight:Number(item.weight)||0})))
  if (settings?.startPick) pool(['cashWeight','cardWeight'].map(key=>({path:'startPick.'+key,weight:Number(settings.startPick[key])||0})))
  if (settings?.cityEvents?.pool) pool(settings.cityEvents.pool.map(item=>({path:`cityEvents.pool.${item.kind}.weight`,weight:item.enabled?(Number(item.weight)||0):0})))
  return out
}
const probabilityText = value => value===undefined?'未设置':value===null?'奖池未开放':numberText(value)+'%'

export function compareVersions(before={},after={}) {
  const old=flatten(before), next=flatten(after), oldProb=probabilities(before), nextProb=probabilities(after)
  const rows=[]
  for (const path of new Set([...Object.keys(old),...Object.keys(next)])) {
    // A missing section is expanded into its fields instead of a misleading object string.
    if ([...Object.keys(old),...Object.keys(next)].some(key=>key.startsWith(path+'.'))) continue
    const changed=JSON.stringify(old[path])!==JSON.stringify(next[path])
    const probabilityChanged=Math.abs((oldProb[path]??0)-(nextProb[path]??0))>1e-9 || ((oldProb[path]===null || oldProb[path]===undefined)!==(nextProb[path]===null || nextProb[path]===undefined))
    if (!changed && !probabilityChanged) continue
    const hasProbability=path in oldProb || path in nextProb
    let delta=''
    if (hasProbability && typeof oldProb[path]==='number' && typeof nextProb[path]==='number') {
      const amount=nextProb[path]-oldProb[path]
      delta=Math.abs(amount)<1e-9?'概率不变':(amount>0?'增加 ':'减少 ')+numberText(Math.abs(amount))+' 个百分点'
    } else if (typeof old[path]==='number' && typeof next[path]==='number') {
      const amount=next[path]-old[path]
      delta=(amount>0?'增加 ':'减少 ')+numberText(Math.abs(amount))+(unitFor(path)==='%'?' 个百分点':unitFor(path)?' '+unitFor(path):'')
    }
    rows.push({path,section:sectionForField(path)||'other',label:fieldName(path,before,after),before:valueText(old[path],path),after:valueText(next[path],path),delta,
      kind:!changed?'probability':old[path]===undefined || old[path]===null?'added':next[path]===undefined || next[path]===null?'removed':'changed',
      probability:hasProbability?{before:probabilityText(oldProb[path]),after:probabilityText(nextProb[path]),note:path.startsWith('cityEvents.')?'已启用事件池内占比（实际触发还受开关、模式及触发概率限制）':''}:null})
  }
  return [...pages.map(page=>page.id),'other'].map(id=>({id,label:pageById[id]?.label || '其他配置',rows:rows.filter(row=>row.section===id)})).filter(group=>group.rows.length)
}

export function versionName(version) { return version?.configId===0?'内置默认配置':`版本 #${version?.configId ?? '—'}` }
