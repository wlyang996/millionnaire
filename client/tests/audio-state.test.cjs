const { test } = require('node:test');
const assert = require('node:assert/strict');
const { AudioState, AUDIO_SCENES, AUDIO_EFFECTS, cleanAudioPreferences, validAudioCatalog } = require('../temp/audio-tests/AudioState.js');
function catalog(fadeMs=0) {
  return {version:0,settings:{musicEnabled:true,musicVolume:30,effectsEnabled:false,effectsVolume:65,fadeMs,
    scenes:Object.fromEntries(AUDIO_SCENES.map(id=>[id,{assetId:id==='room'?'friends-play':'morning-town',loop:true}])),
    effects:Object.fromEntries(AUDIO_EFFECTS.map(id=>[id,'']))},
    assets:['morning-town','friends-play'].map(id=>({id,name:id,url:'/api/audio/assets/'+id+'.wav',sha256:'a'.repeat(64),seconds:30}))};
}
class Driver {
  log=[]; deferred=new Map(); delayed=false; volumeValue=0;
  load(url) {this.log.push(['load',url]);return this.delayed?new Promise((resolve,reject)=>this.deferred.set(url,{resolve,reject})):Promise.resolve(url)}
  play(clip,loop){this.log.push(['play',clip,loop])} resume(){this.log.push(['resume'])} pause(){this.log.push(['pause'])} stop(){this.log.push(['stop'])}
  volume(v){this.volumeValue=v} effect(clip,v){this.log.push(['effect',clip,v])} stopEffects(){this.log.push(['stopEffects'])}
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('requires gesture, preserves personal mute across releases, reset uses latest defaults',async()=>{
  const driver=new Driver(),saved=[];const s=new AudioState(driver,{musicEnabled:false},p=>saved.push(p));s.configure(catalog(),'https://local');s.setScene('lobby');
  s.gesture();await flush();assert.equal(driver.log.length,0);
  s.configure({...catalog(),version:1},'https://local');assert.equal(s.values.musicEnabled,false);
  s.reset();await flush();assert.equal(s.values.musicVolume,30);assert.equal(driver.log.filter(e=>e[0]==='play').length,1);assert.deepEqual(saved.at(-1),{});
  s.setPreferences({musicVolume:17});s.configure({...catalog(),version:2},'https://local');assert.equal(s.values.musicVolume,17);
  const off=catalog();off.settings.musicEnabled=false;s.configure(off,'https://local');s.setPreferences({musicEnabled:true});assert.equal(driver.log.at(-1)[0],'pause');
});
test('stale async loads never play, scene switching uses one music channel with fades',async()=>{
  const d=new Driver();d.delayed=true;const s=new AudioState(d);s.configure(catalog(1000),'https://local');s.setScene('lobby');s.gesture();s.setScene('room');
  d.deferred.get('https://local/api/audio/assets/morning-town.wav').resolve('old');await flush();assert.equal(d.log.filter(e=>e[0]==='play').length,0);
  d.deferred.get('https://local/api/audio/assets/friends-play.wav').resolve('room');await flush();s.update(.3);assert.equal(d.volumeValue,.3);
  s.setScene('board');d.deferred.get('https://local/api/audio/assets/morning-town.wav').resolve('board');await flush();s.update(.15);assert.equal(d.volumeValue,.15);s.update(.15);
  const last=d.log.slice(-2);assert.deepEqual(last,[['stop'],['play','board',true]]);s.update(.3);assert.equal(d.volumeValue,.3);
});
test('background pause/resume and repeated same-scene navigation do not stack or restart',async()=>{
  const d=new Driver(),s=new AudioState(d);s.configure(catalog(),'');s.setScene('lobby');await flush();assert.equal(d.log.length,0);
  s.gesture();await flush();s.setScene('board');await flush();assert.equal(d.log.filter(e=>e[0]==='play').length,1);
  s.setVisible(false);s.setVisible(false);assert.equal(d.log.filter(e=>e[0]==='pause').length,1);s.setVisible(true);assert.equal(d.log.filter(e=>e[0]==='resume').length,1);
  s.setScene(null);s.update(1);assert.equal(d.log.at(-1)[0],'stop');
});
test('effects are silent by default, deduplicate identities and do not play after hide or disposal',async()=>{
  const d=new Driver(),s=new AudioState(d);const c=catalog();c.settings.effects.dice='morning-town';s.configure(c,'');s.gesture();await s.effect('dice','1');assert.equal(d.log.length,0);
  c.settings.effectsEnabled=true;s.configure(c,'');await s.effect('dice','1');await s.effect('dice','1');assert.equal(d.log.filter(e=>e[0]==='effect').length,1);
  d.delayed=true;const late=s.effect('dice','2');s.setVisible(false);d.deferred.get('/api/audio/assets/morning-town.wav').resolve('late');await late;
  assert.equal(d.log.filter(e=>e[0]==='effect').length,1);s.dispose();s.setVisible(true);s.gesture();s.update(1);await s.effect('dice','3');assert.equal(d.log.filter(e=>e[0]==='effect').length,1);
});
test('invalid or external catalog data is ignored and corrupt preferences are cleaned',()=>{
  const d=new Driver(),s=new AudioState(d);const c=catalog();assert.equal(validAudioCatalog(c),true);c.assets[0].url='https://external/evil.wav';assert.equal(validAudioCatalog(c),false);s.configure(c,'');assert.equal(s.catalog,null);
  assert.deepEqual(cleanAudioPreferences({musicVolume:NaN,effectsVolume:120,musicEnabled:'true',effectsEnabled:false}),{effectsEnabled:false});
});
test('loading/playback failure stays silent and a later gesture can retry',async()=>{
  const d=new Driver();d.delayed=true;const s=new AudioState(d);s.configure(catalog(),'');s.setScene('lobby');s.gesture();d.deferred.get('/api/audio/assets/morning-town.wav').reject(new Error('network'));await flush();s.update(1);assert.equal(d.log.filter(e=>e[0]==='play').length,0);
  d.delayed=false;d.play=()=>{throw new Error('autoplay denied')};s.gesture();await flush();assert.doesNotThrow(()=>s.update(1));
  d.play=(clip,loop)=>d.log.push(['play',clip,loop]);s.gesture();await flush();assert.equal(d.log.filter(e=>e[0]==='play').length,1);
});
test('raising volume after zero starts at the new quiet volume without a stale loud burst',async()=>{
  const d=new Driver(),s=new AudioState(d);s.configure(catalog(400),'');s.setScene('lobby');s.gesture();await flush();s.update(1);assert.equal(d.volumeValue,.3);
  s.setPreferences({musicVolume:0});assert.equal(d.volumeValue,0);s.setPreferences({musicVolume:1});assert.equal(d.volumeValue,0);s.update(.1);assert.equal(d.volumeValue,.01);
});
