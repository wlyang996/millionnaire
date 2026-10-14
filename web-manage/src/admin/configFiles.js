export const MAX_CONFIG_BYTES = 2 * 1024 * 1024
export function cloneJson(value) { return JSON.parse(JSON.stringify(value)) }
export function safeConfig(value) {
  let nodes=0
  function visit(node,depth) {
    if(++nodes>100000 || depth>40) throw new Error('配置内容过大或嵌套过深')
    if(node===null || typeof node==='string' || typeof node==='boolean') return
    if(typeof node==='number' && Number.isFinite(node)) return
    if(typeof node!=='object') throw new Error('配置包含不支持的数据类型')
    for(const [key,item] of Object.entries(node)) {
      if(['__proto__','prototype','constructor'].includes(key)) throw new Error('配置包含不允许的字段：'+key)
      visit(item,depth+1)
    }
  }
  if(!value || Array.isArray(value) || typeof value!=='object' || !Array.isArray(value.tiers) || !value.room || !value.fees) throw new Error('不是完整的游戏规则配置')
  visit(value,0)
  return value
}
export function backupConfig(settings, configId, note='') {
  return {format:'millionnaire-rule-backup',formatVersion:1,exportedAt:new Date().toISOString(),referenceConfigId:configId ?? null,note,settings:cloneJson(safeConfig(settings))}
}
export function readBackup(text) {
  if(new TextEncoder().encode(text).length>MAX_CONFIG_BYTES) throw new Error('配置文件不可超过2MB')
  let value;try{value=JSON.parse(text)}catch{throw new Error('配置文件不是有效的JSON')}
  if(value?.format!=='millionnaire-rule-backup' || value.formatVersion!==1) throw new Error('不支持的配置备份格式或版本，请使用本后台导出的JSON')
  safeConfig(value.settings)
  return {format:value.format,formatVersion:1,settings:value.settings,referenceConfigId:value.referenceConfigId ?? null}
}
export function draftKey(identity) { return identity?.user?.id == null ? null : `millionnaire-admin-rule-draft-v1:${identity.user.id}` }
export function ruleDraft(identity,baseVersion,baseSettings,settings,inputs={}) {
  return {formatVersion:1,accountId:String(identity.user.id),baseVersion,baseSettings:cloneJson(baseSettings),settings:cloneJson(settings),inputs:cloneJson(inputs),updatedAt:Date.now()}
}
export function readRuleDraft(text,identity) {
  if(!text || !identity?.user || new TextEncoder().encode(text).length>MAX_CONFIG_BYTES)return null
  try {
    const draft=JSON.parse(text)
    if(draft.formatVersion!==1 || draft.accountId!==String(identity.user.id) || !Number.isSafeInteger(draft.baseVersion) || draft.baseVersion<0 || !Number.isFinite(draft.updatedAt))return null
    safeConfig(draft.settings);safeConfig(draft.baseSettings)
    if(!draft.inputs || typeof draft.inputs!=='object' || Array.isArray(draft.inputs) || Object.values(draft.inputs).some(value=>typeof value!=='string'))return null
    if(Object.keys(draft.inputs).some(key=>!['initialCashOptions','timeLimitMinutesOptions','rollSecondsOptions','teamQuickAmounts'].includes(key)))return null
    return draft
  }catch{return null}
}
export function downloadJson(value,name) {
  const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}))
  const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}
