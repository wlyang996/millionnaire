import test from 'node:test'
import assert from 'node:assert/strict'
import { backupConfig,readBackup,readRuleDraft,ruleDraft,draftKey } from '../src/admin/configFiles.js'
import { audioDifferences,characterDifferences,waveformPeaks,validLoop } from '../src/admin/mediaComparison.js'
import { pageVisible } from '../src/admin/accessPolicy.js'
const settings={tiers:[],room:{initialCashOptions:[2000]},fees:{startReward:500}}
test('backup round trip preserves exact rules and cannot inject pending inputs or unsafe keys',()=>{
  const source=structuredClone(settings),file=backupConfig(source,12)
  assert.deepEqual(readBackup(JSON.stringify(file)).settings,source)
  assert.equal(readBackup(JSON.stringify({...file,inputs:{constructor:'bad'}})).inputs,undefined)
  assert.throws(()=>readBackup('{bad'),/JSON/)
  assert.throws(()=>readBackup(JSON.stringify({...file,formatVersion:2})),/版本/)
  assert.throws(()=>readBackup('{"format":"millionnaire-rule-backup","formatVersion":1,"settings":{"tiers":[],"room":{},"fees":{},"__proto__":{"polluted":true}}}'),/不允许/)
  assert.equal({}.polluted,undefined);assert.deepEqual(source,settings)
})
test('rule recovery is account scoped, retains its original baseline and preserves unfinished comma input',()=>{
  const owner={user:{id:42}},draft=ruleDraft(owner,12,settings,{...settings,fees:{startReward:600}},{initialCashOptions:'2000, 3000,',timeLimitMinutesOptions:'10,20,'})
  assert.equal(readRuleDraft(JSON.stringify(draft),owner).inputs.initialCashOptions,'2000, 3000,')
  assert.equal(readRuleDraft(JSON.stringify(draft),owner).inputs.timeLimitMinutesOptions,'10,20,')
  assert.deepEqual(draft.baseSettings,settings)
  assert.equal(readRuleDraft(JSON.stringify(draft),{user:{id:43}}),null)
  assert.equal(readRuleDraft(JSON.stringify({...draft,inputs:{notAField:'foo'}}),owner),null)
  assert.equal(readRuleDraft('garbled',owner),null)
  assert.notEqual(draftKey(owner),draftKey({user:{id:43}}))
})
test('audio comparisons resolve resource names and include loops, volume and unbound cues',()=>{
  const before={release:{settings:{musicVolume:40,scenes:{room:{assetId:'a',loop:true}},effects:{dice:'cue'}}},assets:[{id:'a',name:'旧音乐'},{id:'cue',name:'旧音效'}]}
  const after={release:{settings:{musicVolume:65,scenes:{room:{assetId:'b',loop:false}},effects:{dice:''}}},assets:[{id:'b',name:'新音乐'}]}
  const rows=audioDifferences(before,after)
  assert.deepEqual(rows.map(row=>row.key),['musicVolume','scenes.room','scenes.room.loop','effects.dice'])
  assert.equal(rows[1].before,'旧音乐');assert.equal(rows[1].after,'新音乐');assert.equal(rows[3].after,'静音 / 未绑定')
  assert.deepEqual(audioDifferences(before,before),[])
})
test('character comparison aligns IDs despite reordered rosters and hashes distinguish changed images',()=>{
  const a={id:1,name:'甲',sort:1,enabled:true,bundled:false,images:{pawn:{url:'/same',sha256:'old'}}},b={id:2,name:'乙',sort:2,enabled:true,bundled:true,images:{}}
  const rows=characterDifferences({characters:[a,b]},{characters:[b,{...a,name:'新甲',images:{pawn:{url:'/same',sha256:'new'}}}]})
  assert.deepEqual(rows.map(row=>row.key),['1.name','1.pawn'])
  assert.equal(rows[1].type,'image');assert.equal(rows[1].beforeCharacter,a)
  assert.ok(characterDifferences({characters:[a]},{characters:[]}).some(row=>row.key==='1.identity'))
})
test('waveform includes the loudest channel, represents silence and validates local loop bounds',()=>{
  assert.deepEqual(waveformPeaks([new Float32Array([0,0,0,0])],2),[0,0])
  assert.deepEqual(waveformPeaks([new Float32Array([0,0,0,0]),new Float32Array([0,1,0,.5])],2),[1,.5])
  assert.equal(validLoop(1,2,3),true);assert.equal(validLoop(2,1,3),false);assert.equal(validLoop(0,4,3),false);assert.equal(validLoop(0,Infinity,3),false)
})
test('room monitoring follows dashboard read permission and cannot bypass password rotation',()=>{
  const viewer={permissions:['dashboard:read']}
  assert.equal(pageVisible(viewer,'liveRooms'),true)
  assert.equal(pageVisible({permissions:['config:read']},'liveRooms'),false)
  assert.equal(pageVisible({...viewer,mustChangePassword:true},'liveRooms'),false)
})
