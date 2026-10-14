const sceneLabels={lobby:'大厅',room:'好友房间 / 系列赛',board:'棋盘 / 观战',game:'小游戏',result:'结算'}
const effectLabels={dice:'骰子投掷',move:'逐格移动',cashGain:'金钱增加',cashLoss:'金钱减少',card:'翻牌',purchase:'购买土地',upgrade:'升级土地',auction:'拍卖结果',jail:'入狱',release:'出狱',reward:'全员奖励'}
export const slotLabels={avatar:'头像',pawn:'棋子',portrait:'资料立绘',give:'递币',receive:'接币',worry:'欠款求助',celebrate:'捧杯',pawncrouch:'移动·蓄力',pawnairborne:'移动·腾空',pawnland:'移动·落地',pawnready:'移动·回稳'}
const show=value=>value==null?'—':typeof value==='boolean'?(value?'开启':'关闭'):String(value)
export function audioDifferences(before,after) {
  const rows=[],a=before.release.settings,b=after.release.settings
  const resources=value=>new Map((value.assets || []).map(asset=>[asset.id,asset.name]))
  const old=resources(before),next=resources(after)
  const name=(id,names)=>id?(names.get(id) || id):'静音 / 未绑定'
  function row(key,label,left,right){if(left!==right)rows.push({key,label,before:show(left),after:show(right)})}
  for(const [key,label] of [['musicEnabled','背景音乐'],['musicVolume','背景音乐音量（%）'],['effectsEnabled','独立音效'],['effectsVolume','音效音量（%）'],['fadeMs','切换淡入淡出（毫秒）']])row(key,label,a[key],b[key])
  for(const key of new Set([...Object.keys(a.scenes || {}),...Object.keys(b.scenes || {})])){
    const x=a.scenes?.[key],y=b.scenes?.[key]
    if(x?.assetId!==y?.assetId)rows.push({key:`scenes.${key}`,label:`${sceneLabels[key] || key} · 选曲`,before:name(x?.assetId,old),after:name(y?.assetId,next)})
    row(`scenes.${key}.loop`,`${sceneLabels[key] || key} · 循环`,x?.loop,y?.loop)
  }
  for(const key of new Set([...Object.keys(a.effects || {}),...Object.keys(b.effects || {})]))if(a.effects?.[key]!==b.effects?.[key])rows.push({key:`effects.${key}`,label:effectLabels[key] || key,before:name(a.effects?.[key],old),after:name(b.effects?.[key],next)})
  return rows
}
export function characterDifferences(before,after) {
  const rows=[],a=new Map((before.characters || []).map(c=>[c.id,c])),b=new Map((after.characters || []).map(c=>[c.id,c]))
  const image=(character,slot)=>character?.images?.[slot]?.sha256 || character?.images?.[slot]?.url || (character?.bundled?`builtin:${character.id}:${slot}`:null)
  for(const id of new Set([...a.keys(),...b.keys()])){
    const left=a.get(id),right=b.get(id),label=`${right?.name || left?.name} · ID ${id}`
    if(!left || !right)rows.push({key:`${id}.identity`,label:label+' · 人物',before:left?'存在':'未包含',after:right?'存在':'未包含'})
    for(const [key,title] of [['name','名称'],['sort','排序'],['enabled','可供选择'],['fallback','本地备用人物']])if(left?.[key]!==right?.[key])rows.push({key:`${id}.${key}`,label:label+' · '+title,before:show(left?.[key]),after:show(right?.[key])})
    for(const slot of Object.keys(slotLabels))if(image(left,slot)!==image(right,slot))rows.push({key:`${id}.${slot}`,label:label+' · '+slotLabels[slot],type:'image',slot,beforeCharacter:left,afterCharacter:right,before:image(left,slot)?'已有素材':'未包含',after:image(right,slot)?'已有素材':'未包含'})
  }
  return rows
}
export function waveformPeaks(channels,bars=180) {
  if(!channels?.length || !channels[0].length)return []
  const size=channels[0].length,step=Math.max(1,Math.ceil(size/bars)),peaks=[]
  for(let start=0;start<size;start+=step){let peak=0;const end=Math.min(size,start+step),stride=Math.max(1,Math.floor((end-start)/256));for(const samples of channels)for(let i=start;i<end;i+=stride)peak=Math.max(peak,Math.abs(samples[i] || 0));peaks.push(peak)}
  const maximum=Math.max(...peaks)
  return peaks.map(value=>maximum?value/maximum:0)
}
export function validLoop(start,end,duration){return Number.isFinite(start)&&Number.isFinite(end)&&Number.isFinite(duration)&&duration>0&&start>=0&&end<=duration&&end>start}
