export const groups = [
  { id: 'overview', label: '运营总览', icon: 'Orbit', pages: [['workbench', '我的工作台', '下一局精彩，从这里出发', '生效版本、编辑提醒和常用入口，组成你的专属游戏控制台。'], ['dashboard', '数据看板', '看见每一局的精彩', '对局趋势、参与玩家与玩法分布，全部来自后台真实统计。'], ['liveRooms','实时房间','每一局的运行状态，都清楚可见','当前服务实例的房间、轮次、托管与版本，只读采样。']] },
  { id: 'economy', label: '地图与经济', icon: 'Map', pages: [
    ['names', '土地命名', '给小镇里的每一格起个名字', '点选地图，修改名称；格子类型和顺序始终与游戏一致。'],
    ['sets', '同组地产', '让每一组土地，都有自己的连线', '分组、集齐倍率与真实地图同步预览。'],
    ['prices', '价格与租金', '设定小镇的经济节奏', '购买、建造与收租，在等级阶梯里一眼看清。'],
    ['money', '金额与固定费用', '每一笔奖励，都有清晰的规则', '现金事件、起点奖励与固定费用集中配置。'],
    ['rentRise', '租金上涨', '轮次向前，租金跟着生长', '破产模式下的倍率阶梯随配置实时更新。'],
  ] },
  { id: 'rules', label: '玩法规则', icon: 'SlidersHorizontal', pages: [
    ['room', '建房选项', '下一局，从这里开始', '房间模式、初始现金和机器人选项集中管理。'],
    ['events', '事件与道具概率', '把惊喜，调到刚刚好', '事件、道具、幸运与不幸各自独立，合计实时反馈。'],
    ['startPick', '起点三选一', '每次出发，都有新的选择', '三选一的金额、类型和权重保留服务端配置。'],
    ['roundReward', '轮次奖励', '给每一段成长，一份奖励', '收入来源、轮次与奖励金额同步预览。'],
    ['cityEvents', '城市事件', '让整座小镇一起热闹起来', '预告、生效与持续轮次，在时间线上清晰呈现。'],
    ['funTitles', '结算称号', '给精彩的一局，留下一个名字', '称号名称、启停与门槛统一配置。'],
    ['series', '好友系列赛', '精彩不止一局', '局数、计分和并列规则随设置即时预览。'],
    ['team', '组队合作', '和队友一起，把优势连起来', '队伍模式、免租、资金与欠款援助独立配置。'],
    ['timing', '操作时限', '让每一次操作，都有合适的节奏', '选择窗口、动画留时与托管设置保留原有单位。'],
  ] },
  { id: 'characters', label: '人物工作室', icon: 'UsersRound', pages: [['characters', '人物素材', '每一位角色，都有自己的舞台', '七张基础素材、四帧移动预览和整套版本，统一发布。']] },
  { id: 'audio', label: '声音工作室', icon: 'Music2', pages: [['audio', '音频管理', '让小镇的声音，恰到好处', '上传试听、场景选曲、音量与独立音效，审核后统一发布。']] },
  { id: 'announcement', label: '公告管理', icon: 'Megaphone', pages: [['announcement', '公告', '把下一份惊喜，告诉大家', '公告内容实时预览，发布后由游戏大厅读取。']] },
  { id: 'versions', label: '版本中心', icon: 'History', pages: [['history', '发布记录', '每次发布，都有清晰的落点', '按玩法对比历史差异，查看回滚摘要或载入编辑。']] },
]
groups.push({id:'system',label:'系统管理',icon:'ShieldCheck',pages:[['accounts','后台账号','每个人，都有自己的通行证','独立账号、角色与状态统一管理。'],['roles','角色权限','权限范围，一眼就能看清','固定角色的能力与服务端校验一致。'],['audit','操作审计','每次改变，都有清晰记录','真实操作人、对象、结果与请求编号。']]})
export const pages = groups.flatMap(group => group.pages.map(([id, label, title, description]) => ({ id, label, title, description, group: group.label, icon: group.icon })))
pages.push(...[['sessions','我的会话','每次登录，都能由自己掌握','查看设备，撤销不再使用的会话。'],['password','修改密码','保护自己的通行证','改密后撤销全部会话，使用新密码重新登录。'],['myPermissions','我的权限','我的职责，一眼就能看清','当前角色的真实权限来自服务端。']].map(([id,label,title,description])=>({id,label,title,description,group:'个人账号',icon:'ShieldCheck'})))
export const pageById = Object.fromEntries(pages.map(page => [page.id, page]))

export function diffSettings(before, after, prefix = '') {
  if (JSON.stringify(before) === JSON.stringify(after)) return []
  if (before && after && typeof before === 'object' && typeof after === 'object') {
    return [...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap(key => diffSettings(before[key], after[key], prefix ? `${prefix}.${key}` : key))
  }
  return [{ path: prefix, before, after }]
}
const sectionByField = { tileNames: 'names', sets: 'sets', tiers: 'prices', station: 'prices', fees: 'money', eventCash: 'money', rentRise: 'rentRise', eventWeights: 'events', cardWeights: 'events', lucky: 'events', handLimit: 'events', room: 'room', startPick: 'startPick', roundReward: 'roundReward', cityEvents: 'cityEvents', funTitles: 'funTitles', series: 'series', team: 'team', timing: 'timing', announcement: 'announcement' }
export function sectionForField(path) { return sectionByField[String(path).split(/[.\[]/)[0]] }
