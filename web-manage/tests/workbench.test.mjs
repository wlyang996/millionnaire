import test from 'node:test'
import assert from 'node:assert/strict'
import { cleanPreferences, preferenceKey, visibleIds, recordVisit, moveFavorite, commandResults, highlightParts } from '../src/admin/workbenchState.js'
const viewer = {user:{id:2},permissions:['dashboard:read','config:read','audio:read','character:read']}
test('malformed preferences cannot introduce routes; favorite order and visit cap survive persistence', () => {
  const clean = cleanPreferences({favorites:['audio','audio','accounts','<script>'],recent:['audio','unknown','audio','history'],layout:'script'})
  assert.deepEqual(clean,{favorites:['audio','accounts'],recent:['audio','history'],layout:'panorama'})
  assert.deepEqual(visibleIds(clean.favorites,viewer),['audio'])
  assert.deepEqual(cleanPreferences({favorites:42,recent:'audio'}).favorites,[])
  assert.deepEqual(cleanPreferences({favorites:[]}).favorites,[])
  let recent=[]
  for(const id of ['room','events','audio','names','prices','money','history','dashboard','characters']) recent=recordVisit(recent,id)
  assert.equal(recent.length,8);assert.equal(recent.includes('room'),false)
  assert.deepEqual(recordVisit(recent,'audio'),['audio',...recent.filter(id=>id!=='audio')])
  assert.deepEqual(recordVisit(recent,'unknown'),recent)
})
test('account preferences use stable user IDs and become invisible after permission loss or password reset', () => {
  assert.notEqual(preferenceKey(viewer),preferenceKey({user:{id:3}}))
  assert.equal(preferenceKey(null),null)
  assert.deepEqual(visibleIds(['audio','events','accounts'],{...viewer,permissions:['audio:read']}),['audio'])
  assert.deepEqual(visibleIds(['audio','password'],{...viewer,mustChangePassword:true}),[])
})
test('favorites reorder without dropping entries or moving beyond boundaries', () => {
  const ids=['events','audio','characters']
  assert.deepEqual(moveFavorite(ids,'audio',1),['events','characters','audio'])
  assert.deepEqual(moveFavorite(ids,'events',-1),ids)
  assert.deepEqual(moveFavorite(ids,'missing',1),ids)
  assert.deepEqual(ids,['events','audio','characters'])
})
test('command search returns Chinese parameters with their owning page, English IDs and multiple search words', () => {
  const results=commandResults(viewer,'起点奖励',{money:'经过起点奖励'})
  assert.ok(results.some(result=>result.path==='fees.startReward'&&result.page==='money'))
  assert.equal(commandResults(viewer,'ROOM')[0].page,'room')
  assert.ok(commandResults(viewer,'组队 援助',{team:'组队合作 援助单笔上限'}).some(result=>result.page==='team'))
  assert.equal(commandResults(viewer,'does-not-exist').length,0)
  assert.equal(commandResults(viewer,'',{},['audio'],['events'])[0].page,'audio')
})
test('command results never reveal forbidden pages or parameter names and safe actions carry no write requests', () => {
  assert.equal(commandResults(viewer,'后台账号').length,0)
  assert.equal(commandResults({...viewer,permissions:['audio:read']},'起点奖励').length,0)
  assert.equal(commandResults({...viewer,mustChangePassword:true},'').length,0)
  const action={id:'action:theme',label:'切换主题',detail:'外观'}
  assert.deepEqual(commandResults(viewer,'切换',{},[],[],[action])[0],{...action,kind:'快捷操作',search:''})
})
test('highlighting preserves text and marks overlapping literal queries without interpreting HTML', () => {
  assert.deepEqual(highlightParts('经过起点奖励','起点'),[{text:'经过',marked:false},{text:'起点',marked:true},{text:'奖励',marked:false}])
  const parts=highlightParts('<img onerror=alert(1)>','<img')
  assert.equal(parts.map(part=>part.text).join(''),'<img onerror=alert(1)>')
  assert.equal(parts[0].marked,true)
})
