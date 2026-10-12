<script setup>
import { computed, defineAsyncComponent, ref } from 'vue'
import { Coins, Gem, Gift, Crown, UsersRound, Timer, Flag, ArrowRight, ShieldCheck, Zap, Sparkles, Trophy } from '@lucide/vue'
import { cashRange, cityScenario, rewardAmount } from '../admin/ruleVisuals.js'
import { categoryColor } from '../admin/chartColors.js'
import NumberTicker from './effects/NumberTicker.vue'
const AdminChart = defineAsyncComponent(() => import('./AdminChart.vue'))
const props = defineProps({ section: String, settings: Object, labels: Object, rewardIncome: Number, timingFields: Array })
const mode = ref(null), selectedCash = ref(null), selectedCity = ref(null), selectedTitle = ref(null), teamSize = ref(null), revealed = ref(null)
const s = computed(() => props.settings || {})
const previewMode = computed(() => mode.value || s.value.room?.defaultEndMode)
const previewCash = computed(() => s.value.room?.initialCashOptions?.includes(selectedCash.value) ? selectedCash.value : s.value.room?.defaultInitialCash)
const reward = computed(() => rewardAmount(s.value.roundReward, props.rewardIncome))
const startPool = computed(() => {
  const pick = s.value.startPick || {}, total = Number(pick.cashWeight) + Number(pick.cardWeight)
  return { total, cash: total > 0 ? pick.cashWeight * 100 / total : 0, card: total > 0 ? pick.cardWeight * 100 / total : 0, range: cashRange(pick.cashMin, pick.cashMax, pick.cashStep) }
})
const event = computed(() => s.value.cityEvents?.pool?.find(row => row.kind === selectedCity.value) || s.value.cityEvents?.pool?.[0])
const scenario = computed(() => cityScenario(s.value.cityEvents, event.value))
const title = computed(() => s.value.funTitles?.titles?.find(row => row.kind === selectedTitle.value) || s.value.funTitles?.titles?.[0])
const titleDescriptions = { RENT_KING: '按累计实际收租比较', PROPERTY_TYCOON: '按同时持有地产峰值比较', LUCKY_STAR: '按事件与小游戏收入比较', COMEBACK: '按奖励轮次前名次与最终名次的提升比较' }
const titleUnits = { RENT_KING: '金额', PROPERTY_TYCOON: '块地产', LUCKY_STAR: '金额', COMEBACK: '名' }
const titleIcons = { RENT_KING: Coins, PROPERTY_TYCOON: Crown, LUCKY_STAR: Sparkles, COMEBACK: Trophy }
const actualTeamSize = computed(() => s.value.team?.teamSizeOptions?.includes(teamSize.value) ? teamSize.value : s.value.team?.defaultTeamSize)
const teamMembers = computed(() => Array.from({ length: Math.max(0, Math.min(4, Number(actualTeamSize.value) || 0)) }, (_, index) => index + 1))
const teamOn = computed(() => !!s.value.team?.enabled)
const moneyRange = computed(() => cashRange(s.value.eventCash?.min, s.value.eventCash?.max, s.value.eventCash?.step))
const moneyOption = computed(() => ({ grid: { top: 20, left: 110, right: 35, bottom: 30 }, xAxis: { type: 'value', name: '金额' }, yAxis: { type: 'category', data: ['起点奖励', '小游戏奖励', '出狱费用'] }, series: [{ name: '金额', type: 'bar', barMaxWidth: 26, itemStyle: { borderRadius: [0, 8, 8, 0] }, data: [{ value: s.value.fees?.startReward, itemStyle: { color: '#10b981' } }, { value: s.value.fees?.miniGameWinReward, itemStyle: { color: '#8b5cf6' } }, { value: s.value.fees?.bailCost, itemStyle: { color: '#f97316' } }] }] }))
const seriesOption = computed(() => ({ grid: { top: 25, left: 40, right: 25, bottom: 45 }, xAxis: { type: 'category', data: (s.value.series?.rankPoints || []).map((_, index) => `第${index + 1}名`) }, yAxis: { type: 'value', name: '积分' }, series: [{ name: '名次积分', type: 'bar', barMaxWidth: 35, itemStyle: { borderRadius: [8, 8, 0, 0] }, data: (s.value.series?.rankPoints || []).map((value, index) => ({ value, itemStyle: { color: categoryColor('players', `${index + 2}人`) } })) }] }))
const seconds = computed(() => (props.timingFields || []).filter(row => row[4] === '秒').map(([key, label]) => ({ key, label, value: s.value.timing?.[key] })))
const maxSeconds = computed(() => Math.max(1, ...seconds.value.map(row => Number(row.value) || 0)))
const animations = computed(() => (props.timingFields || []).filter(row => row[4] === '毫秒').map(([key, label]) => ({ key, label, value: s.value.timing?.[key] })))
const maxAnimation = computed(() => Math.max(1, ...animations.value.map(row => Number(row.value) || 0)))
const availableSections = ['room', 'startPick', 'roundReward', 'cityEvents', 'funTitles', 'series', 'team', 'timing', 'money']
</script>

<template>
  <section v-if="availableSections.includes(section)" class="surface rule-preview gameplay-preview" :data-preview="section">
    <div class="preview-heading"><span class="eyebrow">RULES IN MOTION</span><h3>{{ {room:'一眼看到房间开局',startPick:'三张牌，同一个奖池',roundReward:'把奖励放到轮次上',cityEvents:'预告、生效与结束',funTitles:'让称号有自己的舞台',series:'每一局，向系列冠军前进',team:'两支队伍，各自协作',timing:'看见每一个操作窗口',money:'收入、支出与现金范围'}[section] }}</h3><p class="muted">预览随编辑值联动，预览中的选择不会修改或发布配置。</p></div>

    <template v-if="section === 'room' && s.room">
      <div class="preview-toggle" aria-label="预览结束模式"><button v-for="[key,label] in [['TIME_LIMIT','限时模式'],['BANKRUPTCY','破产模式']]" :key="key" type="button" :aria-pressed="previewMode === key" @click="mode=key">{{ label }}<small v-if="s.room.defaultEndMode === key">默认</small></button></div>
      <div class="room-ticket visual-card"><div class="ticket-icon"><Flag :size="30" /></div><div><small>房间预览 · {{ previewMode === 'BANKRUPTCY' ? '破产' : '限时' }}</small><h4>每人初始 <NumberTicker :value="previewCash || 0" /> 现金</h4><p>{{ previewMode === 'BANKRUPTCY' ? '最长 '+s.room.bankruptcyCapMinutes : s.room.defaultTimeLimitMinutes }} 分钟 · 投骰 {{ s.room.defaultRollSeconds }} 秒</p></div><span class="ticket-mode"><Zap :size="18" />{{ s.room.fastModeEnabled && s.room.defaultFastMode ? '快速 '+s.room.fastAnimationPercent+'%' : '普通动画' }}</span></div>
      <p class="muted">点击金额体验建房选择，带“默认”的为发布后的初始选项。</p><div class="preview-toggle"><button v-for="cash in s.room.initialCashOptions" :key="cash" type="button" :aria-pressed="previewCash === cash" @click="selectedCash=cash"><Coins :size="15" />{{ cash }}<small v-if="cash===s.room.defaultInitialCash">默认</small></button></div>
      <div class="preview-chips"><span><small>限时可选</small>{{ s.room.timeLimitMinutesOptions?.join(' / ') }} 分钟</span><span><small>投骰可选</small>{{ s.room.rollSecondsOptions?.join(' / ') }} 秒</span></div>
    </template>

    <template v-else-if="section === 'startPick' && s.startPick">
      <div v-if="startPool.total > 0" class="start-pick-stage"><button v-for="index in 3" :key="index" type="button" class="pick-card visual-card" :class="{ revealed: revealed===index }" :aria-pressed="revealed===index" @click="revealed=revealed===index ? null : index"><template v-if="revealed===index"><Coins :size="28" /><strong>现金 {{ startPool.cash.toFixed(1) }}%</strong><Gem :size="26" /><strong>道具 {{ startPool.card.toFixed(1) }}%</strong><small>展示此牌的两类可能结果</small></template><template v-else><Sparkles :size="32" /><strong>选择 {{ index }}</strong><small>点击查看结果概率</small></template></button></div>
      <el-empty v-else :image-size="65" description="两个权重均为 0，起点抽卡关闭" />
      <div class="probability-strip" v-if="startPool.total > 0" aria-label="起点现金与道具概率"><span :style="{width:startPool.cash+'%',background:'#10b981'}" /><span :style="{width:startPool.card+'%',background:'#8b5cf6'}" /></div>
      <p class="muted">三张牌均按同一权重独立生成结果；这里只展示概率，不抽取或承诺某个结果。前进经过起点才抽卡，后退不抽。</p>
      <div class="preview-chips"><span><small>现金概率</small>{{ startPool.cash.toFixed(1) }}%</span><span><small>道具概率</small>{{ startPool.card.toFixed(1) }}%</span><span><small>现金范围</small>{{ s.startPick.cashMin }} ～ {{ s.startPick.cashMax }}<small>{{ startPool.range.valid ? '共 '+startPool.range.count+' 个等概率金额，步长 '+s.startPick.cashStep : '范围或步长待修正' }}</small></span></div>
    </template>

    <template v-else-if="section === 'roundReward' && s.roundReward">
      <span class="preview-state" :class="{ off:!s.roundReward.enabled }">{{ s.roundReward.enabled ? '奖励启用' : '奖励关闭 · 以下展示配置' }}</span>
      <div class="round-tracks"><div v-for="[label,round] in [['限时',s.roundReward.timeLimitRewardRound],['破产',s.roundReward.bankruptcyRewardRound]]" :key="label" class="round-track"><strong>{{ label }}</strong><div class="round-line"><span class="round-node"><Flag :size="17" /><small>第 1 轮</small></span><span class="round-connector" /><span class="round-node reward-node"><Gift :size="24" /><strong>完成第 {{ round }} 轮</strong><small>{{ s.roundReward.name }}</small></span><span class="round-connector" /><span class="round-node"><ArrowRight :size="18" /><small>继续对局</small></span></div></div></div>
      <div class="reward-equation visual-card"><div><small>累计有效收入</small><strong>{{ rewardIncome ?? '—' }}</strong></div><span>× {{ s.roundReward.rewardPercent }}%</span><ArrowRight :size="22" /><div class="reward-result"><small>预计奖励</small><strong>+<NumberTicker :value="reward" /></strong></div></div>
      <p class="muted">每人封顶 {{ s.roundReward.perPlayerCap }}，向下取整到 {{ s.roundReward.roundingUnit }}；只发一次且只给存活玩家。累计收入使用规则编辑区的计算预览输入。</p><div class="income-source-tags"><span v-for="source in s.roundReward.incomeSources" :key="source"><Coins :size="13" />{{ labels?.income?.[source] || source }}</span></div>
    </template>

    <template v-else-if="section === 'cityEvents' && s.cityEvents">
      <div class="preview-toggle"><button v-for="item in s.cityEvents.pool" :key="item.kind" type="button" :aria-pressed="event?.kind===item.kind" @click="selectedCity=item.kind">{{ item.name }}<small v-if="!item.enabled || item.weight===0">不参与</small></button></div>
      <template v-if="event"><span class="preview-state" :class="{off:!scenario.available}">{{ scenario.available ? '情景预览：假设首次检查触发并选中此事件' : '此事件当前不会触发，以下展示配置时序' }}</span>
      <div class="city-timeline"><article class="visual-card"><small>完成第 {{ scenario.check }} 轮</small><Sparkles :size="26" /><h4>检查 / 预告</h4><p>触发概率 {{ s.cityEvents.triggerPercent }}%</p></article><ArrowRight :size="20" /><article class="visual-card active-city"><small>第 {{ scenario.start }} ～ {{ scenario.last }} 轮</small><Zap :size="26" /><h4>{{ event.name }}</h4><p>{{ labels?.city?.[event.kind] || event.kind }} · 倍率 {{ event.multiplierPercent }}%</p><strong>持续 {{ event.durationRounds }} 整轮</strong></article><ArrowRight :size="20" /><article class="visual-card"><small>第 {{ scenario.end }} 轮开始</small><Flag :size="26" /><h4>恢复原倍率</h4><p>结束本次城市事件</p></article></div>
      <div class="preview-chips"><span><small>触发后选中此事件</small>{{ scenario.share.toFixed(1) }}%</span><span><small>后续检查间隔</small>{{ s.cityEvents.checkEveryRounds }} 轮</span><span><small>适用模式</small>{{ s.cityEvents.allowedModes?.map(key=>key==='TIME_LIMIT'?'限时':'破产').join(' / ') || '未选择' }}</span></div><p class="muted">先判定是否触发，再从启用且权重大于 0 的事件中抽取。有事件时跳过新抽取，不叠加；时序不表示实际对局必然发生。</p></template>
    </template>

    <template v-else-if="section === 'funTitles' && s.funTitles">
      <span class="preview-state" :class="{off:!s.funTitles.enabled}">{{ s.funTitles.enabled ? '结算称号开启' : '结算称号关闭 · 以下展示徽章配置' }}</span>
      <div class="title-gallery"><button v-for="(item,index) in s.funTitles.titles" :key="item.kind" type="button" class="title-badge" :class="{ selected:title?.kind===item.kind, disabled:!item.enabled || !s.funTitles.enabled }" :style="{'--badge-color':['#f59e0b','#8b5cf6','#10b981','#ec4899'][index%4]}" :aria-pressed="title?.kind===item.kind" @click="selectedTitle=item.kind"><div class="medal"><component :is="titleIcons[item.kind] || Crown" :size="30" /></div><strong>{{ item.name }}</strong><small>{{ item.enabled ? '最低 '+item.minimum+' '+(titleUnits[item.kind] || '') : '停用' }}</small></button></div>
      <div v-if="title" class="title-inspector visual-card"><component :is="titleIcons[title.kind] || Crown" :size="25" /><div><h4>{{ title.name }}</h4><p>{{ titleDescriptions[title.kind] }}，最低 {{ title.minimum }} {{ titleUnits[title.kind] }}。</p><small>并列共同获奖；仅展示荣誉，不发现金。</small></div></div>
    </template>

    <template v-else-if="section === 'series' && s.series">
      <span class="preview-state" :class="{off:!s.series.enabled}">{{ s.series.enabled ? '系列赛开放' : '系列赛关闭 · 以下展示配置' }}</span><div class="series-track"><span v-for="index in s.series.defaultGames" :key="index" class="series-game visual-card"><Trophy v-if="index===s.series.defaultGames" :size="23" /><Flag v-else :size="17" /><strong>第 {{ index }} 局</strong></span></div>
      <AdminChart :option="seriesOption" label="系列赛各名次获得积分" :height="240" /><div class="preview-chips"><span><small>可选局数</small>{{ s.series.gameOptions?.join(' / ') }}</span><span><small>下一局最少人数</small>{{ s.series.minPlayers }}</span><span><small>并列积分</small>{{ s.series.tieMode === 'SPLIT_OCCUPIED' ? '占位积分均分（向下取整）' : '同名次积分' }}</span></div><p class="muted">积分决定系列名次，不发现金；每局现金、土地、手牌重置。累计同分即并列冠军。</p>
    </template>

    <template v-else-if="section === 'team' && s.team">
      <span class="preview-state" :class="{off:!teamOn}">{{ teamOn ? '组队开放' : '组队关闭 · 以下展示配置关系' }}</span><div class="preview-toggle" aria-label="预览组队人数"><button v-for="size in s.team.teamSizeOptions" :key="size" type="button" :aria-pressed="actualTeamSize===size" @click="teamSize=size">{{ size }}v{{ size }}<small v-if="size===s.team.defaultTeamSize">默认</small></button></div>
      <div class="team-diagram"><article v-for="team in ['A','B']" :key="team" class="team-side visual-card" :class="'team-'+team"><h4>{{ team }} 队 · 个人资金与产权独立</h4><div class="team-seats"><div v-for="index in teamMembers" :key="index" class="team-seat"><UsersRound :size="23" /><strong>{{ team }}{{ index }}</strong><small>独立账户</small></div></div><div class="team-link" :class="{off:!teamOn || !s.team.aidEnabled}"><span /><ArrowRight :size="20" /><small>{{ teamOn && s.team.aidEnabled ? '队内自愿援助' : '主动援助关闭' }}</small><span /></div><div class="team-rule"><ShieldCheck :size="16" />{{ teamOn && s.team.allyRentWaiver ? '队友免租' : '队友照常收租' }}<span>·</span>{{ teamOn && s.team.allyRoadblockPass ? '通过队友路障' : '队友路障生效' }}</div></article></div>
      <div class="preview-chips"><span><small>主动援助上限 / 次数</small>{{ s.team.aidMaxAmount }} / 每回合 {{ s.team.aidPerTurn }} 次</span><span><small>欠款援助</small>{{ teamOn && s.team.aidEnabled && s.team.debtAidEnabled ? s.team.debtAidWindowMs/1000+' 秒窗口' : '关闭' }}</span><span><small>机器人</small>{{ s.team.allowBots ? '允许（仍受总开关限制）' : '不允许' }}</span></div><p class="muted">{{ actualTeamSize===2 ? '30格、50格均支持当前形式。' : '当前形式仅50格地图支持。' }}按队伍净资产结算；组队单局与个人系列赛互斥。以上为座位示意。</p>
    </template>

    <template v-else-if="section === 'timing' && s.timing">
      <h4><Timer :size="17" />操作窗口（秒）</h4><div class="timing-bars"><div v-for="row in seconds" :key="row.key"><span>{{ row.label }}</span><div class="timing-rail"><span :style="{width:Math.max(0,(row.value || 0)/maxSeconds*100)+'%'}" /></div><strong>{{ row.value ?? '未下发' }} 秒</strong></div></div>
      <h4><Zap :size="17" />动画与展示留时（毫秒）</h4><div class="timing-bars animation-bars"><div v-for="row in animations" :key="row.key"><span>{{ row.label }}</span><div class="timing-rail"><span :style="{width:Math.max(0,(row.value || 0)/maxAnimation*100)+'%'}" /></div><strong>{{ row.value ?? '未下发' }} ms</strong></div></div><p class="muted">条形长度分别按两组最大值比较，不将独立操作窗口相加。快速模式只缩短支持加速的动画，不改变操作时限；全员挂机上限为 {{ s.timing.allAwayTurns }} 回合。</p>
    </template>

    <template v-else-if="section === 'money'">
      <AdminChart :option="moneyOption" label="起点奖励、小游戏奖励与出狱费用金额对比" :height="230" /><h4><Coins :size="17" />事件现金取值</h4><div v-if="moneyRange.valid" class="cash-ladder"><span v-for="value in moneyRange.values" :key="value" class="visual-card">{{ value }}<small>{{ (100/moneyRange.count).toFixed(2) }}%</small></span></div><el-alert v-else title="范围或步长无效，无法生成现金分布" type="warning" :closable="false" /><p class="muted" v-if="moneyRange.valid">共 {{ moneyRange.count }} 个等概率金额，步长 {{ s.eventCash.step }}；{{ moneyRange.count > moneyRange.values.length ? '展示前 '+moneyRange.values.length+' 项。' : '全部展示。' }}现金奖励与罚款共用此范围。</p>
    </template>
    <el-empty v-else :image-size="65" description="服务端尚未下发这一组参数" />
  </section>
</template>
