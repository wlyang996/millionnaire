export function historyQuery({page=1,size=20,q='',type='all',status='all'}={}) {
  const params=new URLSearchParams({page:String(page),size:String(size),q:q.trim(),type,status})
  return '/admin-api/v1/config/history/page?'+params
}
export function publisherName(row) {
  return row.createdBy==='admin'?'管理员':row.createdBy==='system'?'系统':row.createdBy||'未记录'
}
export function publicationType(row) { return row.configId===0?'内置默认':row.sourceConfigId!=null?'回滚发布':'普通发布' }
export function sourceName(row) { return row.sourceConfigId==null?'—':row.sourceConfigId===0?'内置默认配置':'版本 #'+row.sourceConfigId }
