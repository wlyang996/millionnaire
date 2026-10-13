<script setup>
import { ref } from 'vue'
import { call } from '../api.js'
import { passwordError } from '../admin/accessPolicy.js'
const props=defineProps({mandatory:Boolean})
const emit=defineEmits(['changed','error','cancel'])
const current=ref(''),next=ref(''),confirm=ref(''),busy=ref(false),error=ref('')
async function submit(){
  error.value=passwordError(next.value)||(next.value!==confirm.value?'两次输入的新密码不一致':'')
  if(error.value||busy.value)return
  busy.value=true
  try{await call('POST','/admin-api/v1/auth/password',{currentPassword:current.value,newPassword:next.value});current.value=next.value=confirm.value='';emit('changed')}
  catch(e){error.value=e.message;emit('error',e)}finally{busy.value=false}
}
</script>
<template><section class="surface access-password" :class="{'mandatory-password':mandatory}"><span class="eyebrow">账号安全</span><h2>{{ mandatory?'首次登录 · 修改密码':'修改我的密码' }}</h2><p class="muted">修改成功后撤销全部已有会话，请使用新密码重新登录。</p><el-form label-position="top" @submit.prevent="submit"><el-form-item label="当前密码 / 临时密码"><el-input v-model="current" type="password" show-password autocomplete="current-password" :disabled="busy" /></el-form-item><el-form-item label="新密码"><el-input v-model="next" type="password" show-password autocomplete="new-password" :disabled="busy" /></el-form-item><el-form-item label="确认新密码"><el-input v-model="confirm" type="password" show-password autocomplete="new-password" :disabled="busy" /></el-form-item><p v-if="error" role="alert" class="access-error">{{ error }}</p><el-button type="primary" native-type="submit" :loading="busy">修改密码并重新登录</el-button><el-button v-if="mandatory" :disabled="busy" @click="emit('cancel')">退出并切换账号</el-button></el-form></section></template>
