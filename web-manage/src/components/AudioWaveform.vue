<script setup>
import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { waveformPeaks, validLoop } from '../admin/mediaComparison.js'
const props=defineProps({blob:Object,src:String,name:String,initialLoop:Boolean,kind:String})
const player=ref(null),peaks=ref([]),duration=ref(0),position=ref(0),start=ref(0),end=ref(0),loop=ref(false),error=ref(''),decoding=ref(false)
const valid=computed(()=>validLoop(start.value,end.value,duration.value))
let generation=0,frame=0,segment=false
function cancel(){cancelAnimationFrame(frame);frame=0}
function pause(){cancel();player.value?.pause()}
async function decode(){const request=++generation;pause();peaks.value=[];duration.value=0;position.value=0;start.value=0;end.value=0;loop.value=!!props.initialLoop;error.value='';segment=false
  if(!props.blob){decoding.value=false;return}
  decoding.value=true
  try{
    const Context=window.OfflineAudioContext || window.webkitOfflineAudioContext
    if(!Context)throw new Error('当前浏览器不支持波形解析，可继续使用普通试听控件')
    const context=new Context(1,1,44100),buffer=await context.decodeAudioData(await props.blob.arrayBuffer())
    if(request!==generation)return
    duration.value=buffer.duration;end.value=buffer.duration;peaks.value=waveformPeaks(Array.from({length:buffer.numberOfChannels},(_,i)=>buffer.getChannelData(i)))
  }catch(e){if(request===generation)error.value=e.message || '波形解析失败'}finally{if(request===generation)decoding.value=false}
}
watch(()=>props.blob,decode,{immediate:true})
function tick(){const audio=player.value;if(!audio || audio.paused)return;position.value=audio.currentTime
  if(valid.value && (segment || loop.value) && audio.currentTime>=end.value){if(loop.value)audio.currentTime=start.value;else{pause();return}}
  frame=requestAnimationFrame(tick)
}
function play(){cancelAnimationFrame(frame);tick()}
function ended(){cancel();if(loop.value && valid.value && player.value){player.value.currentTime=start.value;player.value.play().catch(()=>{error.value='请点击播放继续试听'})}}
async function playRange(){if(!valid.value || !player.value)return;segment=true;player.value.currentTime=start.value;try{await player.value.play()}catch(e){error.value=e.message || '请点击音频控件开始试听'}}
function seek(event){if(!duration.value || !player.value)return;const rect=event.currentTarget.getBoundingClientRect();player.value.currentTime=Math.max(0,Math.min(duration.value,(event.clientX-rect.left)/rect.width*duration.value));position.value=player.value.currentTime}
function seekKey(event){if(!player.value || !duration.value)return;const amount=event.key==='ArrowRight'?1:event.key==='ArrowLeft'?-1:0;if(!amount)return;event.preventDefault();player.value.currentTime=Math.max(0,Math.min(duration.value,player.value.currentTime+amount));position.value=player.value.currentTime}
function metadata(event){const seconds=event.target.duration;if(!duration.value && Number.isFinite(seconds) && seconds>0){duration.value=seconds;end.value=seconds}}
const hidden=()=>{if(document.hidden)pause()};document.addEventListener('visibilitychange',hidden)
onDeactivated(pause)
onBeforeUnmount(()=>{generation++;pause();document.removeEventListener('visibilitychange',hidden)})
defineExpose({pause})
</script>
<template>
  <section class="audio-waveform" :data-control-tone="kind==='effect'?'rose':'cyan'" v-loading="decoding"><div class="surface-heading"><h3>{{name}}</h3><small>{{position.toFixed(1)}} / {{duration.toFixed(1)}} 秒</small></div><div v-if="peaks.length" class="wave-track" role="slider" tabindex="0" aria-label="音频播放进度，左右键按秒定位" :aria-valuemin="0" :aria-valuemax="duration" :aria-valuenow="position" @click="seek" @keydown="seekKey"><svg viewBox="0 0 1000 100" preserveAspectRatio="none" aria-hidden="true"><rect v-if="valid" :x="start/duration*1000" y="0" :width="(end-start)/duration*1000" height="100" class="wave-range" /><rect v-for="(peak,i) in peaks" :key="i" :x="i/peaks.length*1000" :y="50-Math.max(2,peak*80)/2" :width="1000/peaks.length*.7" :height="Math.max(2,peak*80)" :class="i/peaks.length<=position/duration?'wave-played':'wave-bar'" rx="1" /><line :x1="position/duration*1000" :x2="position/duration*1000" y1="0" y2="100" class="wave-cursor" /></svg></div><el-alert v-if="error" :title="error" type="warning" :closable="false" /><audio ref="player" :src="src" controls preload="metadata" @play="play" @pause="cancel" @ended="ended" @timeupdate="position=$event.target.currentTime" @loadedmetadata="metadata" /><div class="wave-controls"><label>开始（秒）<el-input-number v-model="start" :min="0" :max="duration" :precision="2" :step=".1" aria-label="试听区间开始秒数" /></label><label>结束（秒）<el-input-number v-model="end" :min="0" :max="duration" :precision="2" :step=".1" aria-label="试听区间结束秒数" /></label><span class="wave-loop-control" data-control-tone="amber"><el-switch v-model="loop" aria-label="循环试听所选区间" active-text="循环试听" /></span><el-button data-control-tone="green" type="primary" plain :disabled="!valid" @click="playRange">播放所选区间</el-button></div><p class="muted">波形来自当前音频文件，区间与循环仅用于本机试听，不写入草稿或游戏配置。离开页面自动停止。</p></section>
</template>
<style scoped>.audio-waveform{padding:22px;border:1px solid var(--admin-border);border-radius:18px;background:linear-gradient(140deg,var(--admin-card),var(--admin-brand-soft));width:100%}.wave-track{height:100px;border-radius:12px;background:var(--admin-secondary);cursor:crosshair;margin:16px 0;overflow:hidden}.wave-track svg{width:100%;height:100%}.wave-range{fill:var(--admin-brand);opacity:.09}.wave-bar{fill:color-mix(in srgb,var(--control-accent) 45%,var(--admin-card))}.wave-played{fill:var(--admin-brand)}.wave-cursor{stroke:#e8b257;stroke-width:3}.wave-controls{display:flex;gap:14px;align-items:flex-end;flex-wrap:wrap;margin-top:18px}.wave-controls label{display:grid;gap:7px;font-size:12px}audio{width:100%}.wave-track:focus-visible{outline:2px solid var(--admin-brand)}</style>
