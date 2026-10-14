<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { call } from '../api.js'
import NumberTicker from './effects/NumberTicker.vue'
const emit=defineEmits(['error'])
const data=ref(null),busy=ref(false),error=ref(''),query=ref(''),status=ref('all'),page=ref(1),interval=ref(5),selected=ref(null)
let timer,disposed=false,generation=0,queued=false
function schedule(){clearTimeout(timer);if(!disposed && !document.hidden && interval.value)timer=setTimeout(load,interval.value*1000)}
async function load(){
  if(busy.value){queued=true;return}
  clearTimeout(timer);busy.value=true;const request=++generation
  try{
    const result=await call('GET',`/admin-api/v1/rooms?page=${page.value}&size=20&q=${encodeURIComponent(query.value.trim())}&status=${status.value}`)
    if(!disposed && request===generation && !queued){data.value=result;error.value='';if(selected.value)selected.value=result.items.find(room=>room.code===selected.value.code) || null}
  }catch(e){if(!disposed && !queued){error.value=e.message || '读取房间失败';emit('error',e)}}finally{if(!disposed){busy.value=false;if(queued){queued=false;load()}else schedule()}}
}
function search(){page.value=1;load()}
function visibility(){clearTimeout(timer);if(!document.hidden)load()}
watch(interval,schedule)
onMounted(()=>{load();document.addEventListener('visibilitychange',visibility)})
onBeforeUnmount(()=>{disposed=true;generation++;clearTimeout(timer);document.removeEventListener('visibilitychange',visibility)})
const labels={WAITING:'等待开局',PLAYING:'对局中'}
const metricTones={rooms:'violet',playing:'green',players:'blue',automated:'amber',spectators:'rose'}
const metrics=[['rooms','活跃房间'],['playing','进行中'],['players','参与人数'],['automated','托管中'],['spectators','观战人数']]
</script>
<template>
  <section class="live-rooms"><div class="surface live-room-toolbar"><div><h3>实时房间 · 当前服务实例</h3><p class="muted">{{data?'最近采样 '+new Date(data.sampledAt).toLocaleTimeString('zh-CN',{hour12:false}):'正在读取当前房间…'}} · 人数为成员或本局参与者，含机器人，不代表在线人数。</p></div><el-select v-model="interval" aria-label="自动刷新间隔" style="width:140px"><el-option :value="5" label="每5秒刷新" /><el-option :value="10" label="每10秒刷新" /><el-option :value="30" label="每30秒刷新" /><el-option :value="0" label="手动刷新" /></el-select><el-button :loading="busy" @click="load">刷新</el-button></div><el-alert v-if="error" :title="error+'；下方保留最近一次采样，可能已过时'" type="warning" :closable="false" />
    <div class="live-room-metrics"><div v-for="[key,label] in metrics" :key="key" class="surface" :data-control-tone="metricTones[key]"><small>{{label}}</small><strong><NumberTicker :value="data?.totals?.[key]" /></strong></div></div>
    <section class="surface"><div class="transfer-actions"><el-input v-model="query" placeholder="搜索六位房间号" aria-label="搜索房间号" maxlength="30" clearable style="width:220px" @keyup.enter="search" @clear="search" /><el-select v-model="status" aria-label="房间状态" style="width:160px" @change="search"><el-option value="all" label="全部状态" /><el-option value="WAITING" label="等待开局" /><el-option value="PLAYING" label="对局中" /></el-select><el-button @click="search">查询</el-button></div><el-table :data="data?.items || []" v-loading="busy" @row-click="selected=$event"><el-table-column prop="code" label="房间号" width="110" /><el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.status==='PLAYING'?'success':'info'">{{labels[row.status] || row.status}}</el-tag></template></el-table-column><el-table-column prop="players" label="人数" width="70" /><el-table-column prop="spectators" label="观战" width="70" /><el-table-column label="轮次 / 回合" min-width="120"><template #default="{row}">{{row.round || '—'}} / {{row.turn || '—'}}</template></el-table-column><el-table-column prop="automated" label="托管" width="70" /><el-table-column label="规则版本" min-width="120"><template #default="{row}">{{row.configId?'#'+row.configId:'内置默认'}}</template></el-table-column><el-table-column label="操作" width="100"><template #default="{row}"><el-button text @click.stop="selected=row">查看详情</el-button></template></el-table-column></el-table><el-pagination v-if="data?.total" v-model:current-page="page" :page-size="20" :total="data.total" layout="prev,pager,next,total" @current-change="load" /><el-empty v-if="data && !data.total" description="当前服务实例没有符合条件的活跃房间" :image-size="60" /></section>
    <el-drawer :model-value="!!selected" title="房间运行概览" size="min(480px,94vw)" @close="selected=null"><template v-if="selected"><h3>{{selected.code}} · {{labels[selected.status]}}</h3><el-descriptions :column="1" border><el-descriptions-item label="地图">{{selected.boardId}}</el-descriptions-item><el-descriptions-item label="结束方式">{{selected.endMode==='BANKRUPTCY'?'破产模式':'限时模式'}}</el-descriptions-item><el-descriptions-item label="轮次 / 回合">{{selected.round}} / {{selected.turn}}</el-descriptions-item><el-descriptions-item label="对局编号">{{selected.gameNo || '尚未开局'}}</el-descriptions-item><el-descriptions-item label="机器人 / 托管">{{selected.bots}} / {{selected.automated}}</el-descriptions-item><el-descriptions-item label="规则 / 人物版本">#{{selected.configId}} / #{{selected.characterVersion}}</el-descriptions-item><el-descriptions-item label="建房时间">{{new Date(selected.createdAt).toLocaleString('zh-CN')}}</el-descriptions-item><el-descriptions-item v-if="selected.gameStartedAt" label="本局开局时间">{{new Date(selected.gameStartedAt).toLocaleString('zh-CN')}}</el-descriptions-item></el-descriptions><p class="muted">这里只提供运行状态查看；采样不改变房间，不加入观战，也不读取玩家手牌或其他私有数据。</p></template></el-drawer>
  </section>
</template>
<style scoped>.live-room-toolbar,.transfer-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.live-room-toolbar>div{flex:1}.live-room-metrics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:14px}.live-room-metrics .surface{padding:22px;background:linear-gradient(135deg,var(--admin-card),var(--admin-brand-soft))}.live-room-metrics small{display:block;color:var(--admin-muted)}.live-room-metrics strong{display:block;font-size:28px;margin-top:12px}.live-rooms .el-table{margin-top:20px}.live-rooms .el-pagination{margin-top:16px}@media(max-width:1000px){.live-room-metrics{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:550px){.live-room-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}</style>
