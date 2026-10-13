<script setup>
import { onMounted, ref } from 'vue'
import { call } from '../api.js'
const state=ref(null), error=ref('')
async function load(){try{state.value=await call('GET','/admin-api/v1/content-safety');error.value=''}catch{state.value=null;error.value='内容检测状态读取失败，请刷新重试'}}
onMounted(load)
</script>
<template>
  <section class="surface" aria-label="内容安全检测状态"><div class="surface-heading"><h3>内容安全检测</h3><el-button @click="load">刷新状态</el-button></div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <template v-else-if="state"><el-tag :type="state.enabled?'success':'warning'">{{state.enabled?'已启用检测策略':'未启用 · 内容记为未检测'}}</el-tag>
      <p class="muted">{{state.policy}}。此处显示启用配置，实际云调用可用性需使用微信身份联调验证。</p>
      <p class="muted">云托管放行 {{state.path}}；设置 CONTENT_SAFETY_ENABLED=true。检测超时由 CONTENT_SAFETY_TIMEOUT_MS 配置（1000～10000毫秒）。</p>
    </template><p v-else class="muted">读取状态…</p>
  </section>
</template>
