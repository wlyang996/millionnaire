import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { createReleaseReview } from '../src/admin/releaseReview.js'
import { compareVersions } from '../src/admin/versionComparison.js'
import { historyQuery, publisherName, publicationType, sourceName } from '../src/admin/versionHistory.js'

test('release review handles reactive forms and publishes the exact reviewed snapshot',()=>{
  const before={startPick:{cashWeight:60,cardWeight:40},timing:{animDiceMs:500},cityEvents:{triggerPercent:30}}
  const form=reactive(structuredClone(before)); form.startPick.cashWeight=70; form.timing.animDiceMs=800; form.cityEvents.triggerPercent=35
  const review=createReleaseReview(before,form,12)
  assert.deepEqual(review.groups,compareVersions(before,JSON.parse(JSON.stringify(form))))
  form.startPick.cashWeight=80; form.timing.animDiceMs=900
  assert.equal(review.settings.startPick.cashWeight,70); assert.equal(review.settings.timing.animDiceMs,800); assert.equal(review.expectedActiveId,12)
  const rows=review.groups.flatMap(group=>group.rows)
  assert.equal(rows.find(row=>row.path==='timing.animDiceMs').after,'800 毫秒')
  assert.equal(rows.find(row=>row.path==='cityEvents.triggerPercent').label,'触发概率')
  assert.equal(rows.find(row=>row.path==='startPick.cardWeight').kind,'probability')
  assert.ok(rows.every(row=>!['label','f[1]','配置项'].includes(row.label)))
})
test('version history filters are encoded safely and rollback to default is explicit',()=>{
  const url=new URL(historyQuery({page:4,size:10,q:'  夏季 & 100%  ',type:'rollback',status:'inactive'}),'http://localhost')
  assert.equal(url.searchParams.get('q'),'夏季 & 100%');assert.equal(url.searchParams.get('page'),'4');assert.equal(url.searchParams.get('size'),'10')
  assert.equal(url.searchParams.get('type'),'rollback');assert.equal(url.searchParams.get('status'),'inactive')
  const rollback={configId:9,sourceConfigId:0,createdBy:'admin'}
  assert.equal(publicationType(rollback),'回滚发布');assert.equal(sourceName(rollback),'内置默认配置');assert.equal(publisherName(rollback),'管理员')
  assert.equal(publicationType({configId:0}),'内置默认');assert.equal(sourceName({configId:8,sourceConfigId:2}),'版本 #2')
  assert.equal(publisherName({createdBy:'system'}),'系统');assert.equal(publisherName({createdBy:'other-user'}),'other-user')
})
