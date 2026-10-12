import test from 'node:test'
import assert from 'node:assert/strict'
import { compareVersions, versionName } from '../src/admin/versionComparison.js'
const rows=(before,after)=>compareVersions(before,after).flatMap(group=>group.rows)

test('version comparisons are read-only, omit equal fields and display units and switches',()=>{
  const before={timing:{decisionSeconds:15,animDiceMs:800},fees:{startReward:500},room:{defaultInitialCash:6000},announcement:{enabled:false,title:'原公告'}}
  const after=structuredClone(before); after.timing.decisionSeconds=20; after.fees.startReward=800; after.announcement.enabled=true
  const snapshot=JSON.stringify([before,after]), changes=rows(before,after)
  assert.equal(changes.length,3)
  assert.equal(changes.find(row=>row.path==='timing.decisionSeconds').before,'15 秒')
  assert.equal(changes.find(row=>row.path==='fees.startReward').after,'800 金币')
  assert.equal(changes.find(row=>row.path==='announcement.enabled').before,'关闭')
  assert.equal(changes.find(row=>row.path==='announcement.enabled').after,'启用')
  assert.equal(JSON.stringify([before,after]),snapshot)
  assert.deepEqual(compareVersions(before,structuredClone(before)),[])
  assert.equal(versionName({configId:0}),'内置默认配置')
})
test('weight changes expose probability impact on unchanged entries without mixing pools',()=>{
  const before={eventWeights:{CASH_REWARD:1,CARD:1},cardWeights:{BUILD:1,ROADBLOCK:1},lucky:[{kind:'CASH_REWARD',label:'奖励',weight:1,unlucky:false},{kind:'CASH_FINE',label:'罚款',weight:1,unlucky:true}]}
  const after=structuredClone(before);after.eventWeights.CASH_REWARD=3
  const changes=rows(before,after)
  assert.equal(changes.length,2)
  assert.equal(changes[0].probability.before,'50%');assert.equal(changes[0].probability.after,'75%');assert.equal(changes[0].delta,'增加 25 个百分点')
  assert.equal(changes[1].kind,'probability');assert.equal(changes[1].delta,'减少 25 个百分点')
  assert.equal(changes[1].before,changes[1].after)
})
test('lucky and unlucky duplicate kinds stay distinct, and all-zero pools are shown as closed',()=>{
  const before={lucky:[{kind:'CASH_REWARD',label:'奖励',weight:1,unlucky:false},{kind:'BUILD',label:'升级',weight:1,unlucky:false},{kind:'CASH_REWARD',label:'不幸奖励',weight:1,unlucky:true}]}
  const after=structuredClone(before);after.lucky[0].weight=0;after.lucky[1].weight=0
  const changes=rows(before,after)
  assert.equal(changes.length,2);assert.ok(changes.every(row=>row.label.startsWith('幸运')))
  assert.ok(changes.every(row=>row.probability.after==='奖池未开放'))
})
test('array identity survives reorder and aligns additions and removals by kind',()=>{
  const before={tiers:[{tier:'LOW',rents:[100,200]},{tier:'HIGH',basePrice:900}],cityEvents:{pool:[{kind:'PROPERTY_RENT',enabled:true,name:'安居日',weight:1},{kind:'STATION_RENT',enabled:true,name:'旅游旺季',weight:1}]}}
  const after=structuredClone(before);after.tiers.reverse();after.cityEvents.pool.reverse()
  assert.deepEqual(rows(before,after),[])
  after.tiers[1].rents[1]=250
  assert.equal(rows(before,after)[0].label,'低价地产 · 一级租金')
  after.cityEvents.pool=after.cityEvents.pool.filter(item=>item.kind!=='PROPERTY_RENT')
  const removed=rows(before,after).filter(row=>row.kind==='removed')
  assert.ok(removed.length);assert.ok(removed.every(row=>row.label.startsWith('安居日')))
})
test('city pool shares use enabled entries and start-pick has its own weight denominator',()=>{
  const before={cityEvents:{pool:[{kind:'PROPERTY_RENT',enabled:true,name:'安居日',weight:1},{kind:'STATION_RENT',enabled:true,name:'旅游旺季',weight:1}]},startPick:{cashWeight:1,cardWeight:1}}
  const after=structuredClone(before);after.cityEvents.pool[0].enabled=false;after.startPick.cashWeight=3
  const changes=rows(before,after)
  assert.equal(changes.find(row=>row.path==='cityEvents.pool.STATION_RENT.weight').probability.after,'100%')
  assert.equal(changes.find(row=>row.path==='cityEvents.pool.PROPERTY_RENT.weight').probability.after,'0%')
  assert.equal(changes.find(row=>row.path==='startPick.cashWeight').probability.after,'75%')
})
test('missing old sections remain explicit, option arrays and map locations are readable',()=>{
  const changes=rows({team:null,room:{initialCashOptions:[1000]},tileNames:{'classic-30':['起点','旧名']}},{team:{enabled:false,aidMaxAmount:5000},room:{initialCashOptions:[1000,2000]},tileNames:{'classic-30':['起点','新名']},futureSetting:8})
  assert.equal(changes.find(row=>row.path==='team.enabled').kind,'added')
  assert.match(changes.find(row=>row.path==='team.enabled').before,/服务端兼容/)
  assert.equal(changes.find(row=>row.path==='room.initialCashOptions').after,'1,000 金币、2,000 金币')
  assert.equal(changes.find(row=>row.path==='tileNames.classic-30.1').label,'30 格地图 · 第 1 格 · 土地名称')
  assert.ok(compareVersions({}, {futureSetting:8}).find(group=>group.id==='other'))
  assert.ok(changes.every(row=>!row.before.includes('[object Object]')&&!row.after.includes('[object Object]')))
})
test('title threshold units follow the actual engine metrics, and small probability changes remain visible',()=>{
  const before={funTitles:{titles:[{kind:'LUCKY_STAR',name:'幸运之星',minimum:1},{kind:'COMEBACK',name:'逆风翻盘',minimum:2}]},startPick:{cashWeight:60,cardWeight:40}}
  const after=structuredClone(before);after.funTitles.titles[0].minimum=10;after.funTitles.titles[1].minimum=3;after.startPick.cashWeight=70
  const changes=rows(before,after)
  assert.equal(changes.find(row=>row.path==='funTitles.titles.LUCKY_STAR.minimum').after,'10 金币')
  assert.equal(changes.find(row=>row.path==='funTitles.titles.COMEBACK.minimum').after,'3 名')
  assert.equal(changes.find(row=>row.path==='startPick.cashWeight').probability.after,'63.6364%')
  assert.equal(changes.find(row=>row.path==='startPick.cashWeight').delta,'增加 3.6364 个百分点')
})
