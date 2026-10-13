<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Monitor, ShieldCheck } from '@lucide/vue'
import { call } from '../api.js'
import { roleLabels } from '../admin/accessPolicy.js'
import PasswordChange from './PasswordChange.vue'
import BorderBeam from './effects/BorderBeam.vue'
import SpotlightCard from './effects/SpotlightCard.vue'
const props=defineProps({page:String,identity:Object})
const emit=defineEmits(['error','signedout','compare'])
const busy=ref(false),error=ref(''),items=ref([]),total=ref(0),pageIndex=ref(1),size=ref(20),query=ref(''),status=ref('all'),auditAction=ref(''),auditResult=ref('all'),dateRange=ref(null)
const roles=ref([]),drawer=ref(false),mode=ref(''),selected=ref(null),form=ref({}),secret=ref(''),saving=ref(false),sessions=ref([]),selectedRole=ref('SUPER_ADMIN')
let ticket=0
const time=ms=>ms?new Date(ms).toLocaleString('zh-CN',{hour12:false}):'尚未登录'
const actions={request:'接口请求',login:'登录',logout:'退出登录','password-change':'本人改密','password-reset':'重置密码','user-create':'创建账号','user-update':'修改账号','session-revoke':'撤销会话',publish:'发布',rollback:'回滚',configure:'发布人物资料',upload:'上传素材','draft-create':'创建草稿','draft-edit':'修改草稿资料',bootstrap:'初始化管理员','offline-recovery':'运维恢复'}
const actionName=value=>actions[value]||value
const resultName=value=>({SUCCESS:'成功',FAILED:'失败',DENIED:'拒绝'})[value]||value
function report(e){error.value=e.message||'读取失败';emit('error',e)}
async function load(){
  const own=++ticket;busy.value=true;error.value='';items.value=[]
  try{
    let result
    if(props.page==='accounts')result=await call('GET','/admin-api/v1/users?'+new URLSearchParams({page:String(pageIndex.value),size:String(size.value),q:query.value.trim(),status:status.value}))
    if(props.page==='audit'){const params={page:String(pageIndex.value),size:String(size.value),actor:query.value.trim(),action:auditAction.value,result:auditResult.value};if(dateRange.value){params.from=String(new Date(dateRange.value[0]).getTime());params.to=String(new Date(dateRange.value[1]).getTime())}result=await call('GET','/admin-api/v1/audit?'+new URLSearchParams(params))}
    if(props.page==='roles')result=await call('GET','/admin-api/v1/roles')
    if(props.page==='sessions')result=await call('GET','/admin-api/v1/auth/sessions')
    if(own!==ticket)return
    if(['accounts','audit'].includes(props.page)){items.value=result.items;total.value=result.total;const last=Math.max(1,Math.ceil(result.total/size.value));if(pageIndex.value>last){pageIndex.value=last;load()}}
    else if(props.page==='roles')roles.value=result
    else if(props.page==='sessions')sessions.value=result
  }catch(e){if(own===ticket)report(e)}finally{if(own===ticket)busy.value=false}
}
function filter(){pageIndex.value=1;load()}
function openCreate(){mode.value='create';selected.value=null;form.value={username:'',displayName:'',role:'OPERATOR'};secret.value='';drawer.value=true;error.value=''}
function openEdit(row){mode.value='edit';selected.value=row;form.value={...row,expectedVersion:row.version};secret.value='';drawer.value=true;error.value=''}
async function save(){
  if(saving.value)return
  if(!form.value.displayName?.trim()||(!selected.value&&!/^[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}$/.test(form.value.username||''))){ElMessage.error('请填写有效账号和姓名');return}
  if(selected.value){try{await ElMessageBox.confirm(`修改 ${selected.value.username}：角色 ${roleLabels[selected.value.role]} → ${roleLabels[form.value.role]}，状态 ${selected.value.status==='ACTIVE'?'启用':'停用'} → ${form.value.status==='ACTIVE'?'启用':'停用'}。角色或状态变化后会撤销该用户全部会话。`,'确认账号变更',{type:'warning'})}catch{return}}
  saving.value=true
  try{const result=await call(selected.value?'PATCH':'POST','/admin-api/v1/users'+(selected.value?'/'+selected.value.id:''),selected.value?{displayName:form.value.displayName,role:form.value.role,status:form.value.status,expectedVersion:form.value.expectedVersion}:form.value)
    if(result.temporaryPassword){secret.value=result.temporaryPassword;mode.value='secret'}else{drawer.value=false;ElMessage.success('账号已更新')}
    await load()
    if(selected.value?.id===props.identity.user.id)await call('GET','/admin-api/v1/auth/me')
  }catch(e){report(e);if(e.status===409)ElMessage.warning('请刷新账号列表后重新核对')}finally{saving.value=false}
}
async function reset(row){try{await ElMessageBox.confirm('重置 '+row.username+' 的密码并撤销全部会话？下次登录必须修改临时密码。','重置密码',{type:'warning'})}catch{return}
  saving.value=true;try{const result=await call('POST','/admin-api/v1/users/'+row.id+'/reset-password',{expectedVersion:row.version});secret.value=result.temporaryPassword;mode.value='secret';selected.value=row;drawer.value=true;await load()}catch(e){report(e)}finally{saving.value=false}
}
async function userSessions(row){mode.value='sessions';selected.value=row;secret.value='';drawer.value=true;sessions.value=[];try{sessions.value=await call('GET','/admin-api/v1/users/'+row.id+'/sessions')}catch(e){report(e)}}
async function revoke(session){try{await ElMessageBox.confirm(session.current?'退出当前设备并重新登录？':'撤销此会话？该设备下一次请求需重新登录。','撤销会话',{type:'warning'})}catch{return}
  try{await call('POST','/admin-api/v1/auth/sessions/'+session.id+'/revoke',{});if(session.current)emit('signedout');else{ElMessage.success('会话已撤销');await load()}}catch(e){report(e)}
}
async function revokeAll(){try{await ElMessageBox.confirm('撤销 '+selected.value.username+' 的全部会话？','撤销全部会话',{type:'warning'})}catch{return}
  try{await call('POST','/admin-api/v1/users/'+selected.value.id+'/sessions/revoke-all',{});sessions.value=[];if(selected.value.id===props.identity.user.id)emit('signedout');else ElMessage.success('会话已撤销')}catch(e){report(e)}
}
function openAudit(row){selected.value=row;mode.value='audit';drawer.value=true}
const moduleRows=[['数据看板','dashboard:read'],['规则与公告 · 查看','config:read'],['规则与公告 · 编辑','config:write'],['规则与公告 · 发布','config:publish'],['规则与公告 · 回滚','config:rollback'],['生效人物 · 查看','character:read'],['人物草稿 · 查看','character:draft'],['人物 · 编辑上传','character:write'],['人物 · 发布','character:publish'],['人物 · 回滚','character:rollback'],['后台账号管理','admin-user:manage'],['其他用户会话','admin-session:manage'],['操作审计','audit:read']]
const shownPermissions=computed(()=>props.page==='myPermissions'?props.identity.permissions:roles.value.find(r=>r.id===selectedRole.value)?.permissions||[])
watch(()=>props.page,()=>{ticket++;drawer.value=false;secret.value='';selected.value=null;pageIndex.value=1;query.value='';status.value='all';load()})
onMounted(load);onBeforeUnmount(()=>{ticket++;secret.value=''})
</script>
<template>
  <PasswordChange v-if="page==='password'" @changed="emit('signedout')" @error="emit('error',$event)" />
  <div v-else class="admin-access-manager">
    <el-alert v-if="identity?.persistent===false" type="warning" :closable="false" title="本地内存模式：账号、会话和审计重启会丢失，不能用于生产。" />
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button @click="load">重试</el-button></el-alert>
    <template v-if="page==='roles'||page==='myPermissions'">
      <div v-if="page==='roles'" class="access-role-grid"><SpotlightCard v-for="role in roles" :key="role.id" class="access-role-card" :class="{selected:selectedRole===role.id}"><ShieldCheck :size="22" /><h3>{{ role.name }}</h3><p class="muted">{{ role.description }}</p><el-button :type="selectedRole===role.id?'primary':'default'" @click="selectedRole=role.id">查看权限</el-button><BorderBeam v-if="selectedRole===role.id" /></SpotlightCard></div>
      <section class="surface access-panel" v-loading="busy"><div class="surface-heading"><h3>{{ page==='roles'?roleLabels[selectedRole]:'我的权限 · '+roleLabels[identity.user.role] }}</h3><el-tag>固定角色</el-tag></div><p class="muted">规则与公告以完整快照发布。本人改密和自己的会话管理向所有角色开放。</p><el-table v-if="!busy" :data="moduleRows"><el-table-column label="业务能力" min-width="190"><template #default="{row}">{{ row[0] }}</template></el-table-column><el-table-column label="权限" min-width="120"><template #default="{row}"><el-tag :type="shownPermissions.includes(row[1])?'success':'info'">{{ shownPermissions.includes(row[1])?'允许':'无权限' }}</el-tag></template></el-table-column></el-table></section>
    </template>
    <section v-if="page==='accounts'||page==='audit'" class="surface access-panel" v-loading="busy">
      <div class="access-toolbar"><h3>{{ page==='accounts'?'后台账号':'操作审计' }}</h3><el-button v-if="page==='accounts'" type="primary" @click="openCreate">新增账号</el-button><el-button @click="load">刷新</el-button></div>
      <div class="access-toolbar"><el-input v-model="query" :placeholder="page==='accounts'?'账号或姓名':'操作人账号'" aria-label="查询账号" clearable maxlength="100" @keyup.enter="filter" /><el-select v-if="page==='accounts'" v-model="status" aria-label="账号状态" @change="filter"><el-option label="全部状态" value="all" /><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /></el-select><template v-else><el-select v-model="auditAction" aria-label="审计操作" @change="filter"><el-option label="全部操作" value="" /><el-option v-for="(name,key) in actions" :key="key" :label="name" :value="key" /></el-select><el-select v-model="auditResult" aria-label="审计结果" @change="filter"><el-option label="全部结果" value="all" /><el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAILED" /><el-option label="拒绝" value="DENIED" /></el-select><el-date-picker v-model="dateRange" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" class="access-date-picker" @change="filter" /></template><el-button @click="filter">查询</el-button></div>
      <el-table v-if="page==='accounts'" :data="items" stripe><el-table-column label="姓名 / 账号" min-width="190"><template #default="{row}"><strong>{{ row.displayName }}</strong><div class="muted">{{ row.username }}</div></template></el-table-column><el-table-column label="角色" min-width="130"><template #default="{row}">{{ roleLabels[row.role] }}</template></el-table-column><el-table-column label="状态" min-width="130"><template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':'info'">{{ row.status==='ACTIVE'?'启用':'停用' }}</el-tag><small v-if="row.mustChangePassword"> 待首次改密</small></template></el-table-column><el-table-column label="最近登录" min-width="170"><template #default="{row}">{{ time(row.lastLoginAt) }}</template></el-table-column><el-table-column label="操作" min-width="280"><template #default="{row}"><el-button size="small" @click="openEdit(row)">管理</el-button><el-button size="small" @click="userSessions(row)">会话</el-button><el-button size="small" :disabled="saving || row.id===identity.user.id" @click="reset(row)">重置密码</el-button></template></el-table-column></el-table>
      <el-table v-else :data="items" stripe><el-table-column label="时间 / 操作人" min-width="210"><template #default="{row}">{{ time(row.createdAt) }}<div>{{ row.actorName }} <small class="muted">{{ row.actorUsername }}</small></div></template></el-table-column><el-table-column label="操作" min-width="120"><template #default="{row}">{{ actionName(row.action) }}</template></el-table-column><el-table-column prop="objectId" label="对象" min-width="140" /><el-table-column label="结果" min-width="90"><template #default="{row}"><el-tag :type="row.result==='SUCCESS'?'success':row.result==='DENIED'?'warning':'danger'">{{ resultName(row.result) }}</el-tag></template></el-table-column><el-table-column label="详情" min-width="90"><template #default="{row}"><el-button size="small" @click="openAudit(row)">查看</el-button></template></el-table-column></el-table>
      <el-pagination v-model:current-page="pageIndex" v-model:page-size="size" class="access-pagination" :total="total" :page-sizes="[10,20,50]" :pager-count="5" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="filter" />
    </section>
    <section v-if="page==='sessions'" class="surface access-panel access-self-panel" v-loading="busy"><div class="access-toolbar"><h3>我的会话</h3><el-button @click="load">刷新</el-button></div><p class="muted">近期活动按分钟更新，不代表实时在线状态。</p><el-empty v-if="!sessions.length&&!busy" description="没有可用会话" /><div v-for="session in sessions" :key="session.id" class="access-session"><Monitor :size="25" /><div class="access-session-copy"><strong>{{ session.device || '未识别设备' }}</strong><el-tag v-if="session.current" type="success">当前会话</el-tag><p class="muted">登录 {{ time(session.createdAt) }} · 到期 {{ time(session.expiresAt) }}<br>近期活动 {{ time(session.lastSeenAt) }}</p></div><el-button @click="revoke(session)">{{ session.current?'退出此设备':'撤销会话' }}</el-button></div></section>
    <el-drawer v-model="drawer" class="access-drawer" :title="mode==='create'?'新增后台账号':mode==='secret'?'一次性临时密码':mode==='sessions'?'账号会话':mode==='audit'?'操作详情':'管理后台账号'" size="min(460px, 100vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @closed="secret=''; selected=null">
      <template v-if="mode==='create'||mode==='edit'"><el-form label-position="top" @submit.prevent="save"><el-form-item label="账号"><el-input v-model="form.username" :disabled="mode==='edit'||saving" maxlength="32" placeholder="例如 ops.xiaohe" /></el-form-item><el-form-item label="显示姓名"><el-input v-model="form.displayName" :disabled="saving" maxlength="32" /></el-form-item><el-form-item label="角色"><el-select v-model="form.role" :disabled="saving"><el-option v-for="(name,key) in roleLabels" :key="key" :value="key" :label="name" /></el-select></el-form-item><el-form-item v-if="mode==='edit'" label="账号状态"><el-select v-model="form.status" :disabled="saving"><el-option value="ACTIVE" label="启用" /><el-option value="DISABLED" label="停用" /></el-select></el-form-item><p class="muted">最后一个启用的超级管理员不可停用或降级。角色/状态变更与密码重置会撤销会话。</p><el-button type="primary" native-type="submit" :loading="saving">{{ mode==='create'?'创建账号':'核对影响并保存' }}</el-button></el-form></template>
      <template v-if="mode==='secret'"><p class="access-secret-warning">临时密码仅在本次显示，请交给账号本人。关闭窗口后不能再查询；首次登录必须改密。</p><div class="access-secret">{{ secret }}</div><el-button @click="drawer=false">已记录，关闭</el-button></template>
      <template v-if="mode==='sessions'"><h3>{{ selected?.displayName }} · {{ selected?.username }}</h3><el-button type="warning" @click="revokeAll">撤销全部会话</el-button><div v-for="session in sessions" :key="session.id" class="access-session"><Monitor :size="22" /><div class="access-session-copy"><strong>{{ session.device }}</strong><p class="muted">到期 {{ time(session.expiresAt) }}</p></div></div><el-empty v-if="!sessions.length" description="没有可用会话" /></template>
      <template v-if="mode==='audit'&&selected"><el-tag>{{ resultName(selected.result) }}</el-tag><dl class="access-detail"><dt>操作人</dt><dd>{{ selected.actorName }} / {{ selected.actorUsername || '匿名' }}</dd><dt>业务模块</dt><dd>{{ selected.module }}</dd><dt>操作</dt><dd>{{ actionName(selected.action) }}</dd><dt>对象</dt><dd>{{ selected.objectId }}</dd><dt>请求编号</dt><dd>{{ selected.requestId }}</dd></dl><p>{{ selected.detail }}</p><el-button v-if="selected.module==='config'&&selected.result==='SUCCESS'&&['publish','rollback'].includes(selected.action)" @click="emit('compare',Number(selected.objectId));drawer=false">前往版本中心对比</el-button></template>
    </el-drawer>
  </div>
</template>
