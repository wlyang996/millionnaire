<script setup>
import { computed } from 'vue'
import { compareVersions } from '../admin/versionComparison.js'
const props=defineProps({section:String,settings:Object})
const rows=computed(()=>compareVersions({},props.settings||{}).find(g=>g.id===props.section)?.rows||[])
</script>
<template><section class="surface readonly-config"><div class="surface-heading"><h3>生效参数详情</h3><el-tag type="info">只读</el-tag></div><p class="muted">当前角色可查看参数与图形预览，没有编辑和发布权限。</p><el-table :data="rows" stripe><el-table-column prop="label" label="参数" min-width="190" /><el-table-column prop="after" label="当前值" min-width="150" /><el-table-column label="概率占比" min-width="120"><template #default="{row}">{{ row.probability?.after || '—' }}</template></el-table-column></el-table></section></template>
