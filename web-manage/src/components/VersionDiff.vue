<script setup>
import { computed, ref } from 'vue'
const props=defineProps({groups:{type:Array,default:()=>[]},beforeLabel:String,afterLabel:String,emptyDescription:{type:String,default:'两个版本的配置内容一致'}})
const section=ref('all')
const visible=computed(()=>props.groups.filter(group=>section.value==='all'||group.id===section.value))
const count=computed(()=>props.groups.reduce((sum,group)=>sum+group.rows.length,0))
const kindLabel={added:'新增',removed:'移除',changed:'修改',probability:'概率联动'}
</script>

<template>
  <div class="version-diff">
    <div class="diff-overview"><div><strong>{{ count }} 项差异</strong><span>涉及 {{ groups.length }} 个玩法分组 · 仅显示变化项</span></div><el-select v-if="groups.length" v-model="section" aria-label="筛选玩法分组"><el-option label="全部玩法" value="all" /><el-option v-for="group in groups" :key="group.id" :label="group.label+'（'+group.rows.length+'）'" :value="group.id" /></el-select></div>
    <el-empty v-if="!count" :description="emptyDescription" :image-size="64" />
    <el-empty v-else-if="!visible.length" description="此分组没有差异，请选择其他分组" :image-size="64" />
    <section v-for="group in visible" :key="group.id" class="diff-group"><h4>{{ group.label }} <span>{{ group.rows.length }} 项</span></h4>
      <div class="diff-column-labels"><span>配置项</span><span>{{ beforeLabel }}</span><span>{{ afterLabel }}</span></div>
      <article v-for="row in group.rows" :key="row.path" class="diff-row" :data-diff-path="row.path">
        <div class="diff-name"><strong>{{ row.label }}</strong><el-tag size="small" :type="row.kind==='added'?'success':row.kind==='removed'?'danger':'info'">{{ kindLabel[row.kind] }}</el-tag><small v-if="row.delta">{{ row.delta }}</small></div>
        <div class="diff-value diff-before"><small>{{ beforeLabel }}</small><span>{{ row.before }}</span><strong v-if="row.probability">{{ row.probability.before }}</strong></div>
        <div class="diff-value diff-after"><small>{{ afterLabel }}</small><span>{{ row.after }}</span><strong v-if="row.probability">{{ row.probability.after }}</strong></div>
        <p v-if="row.probability?.note" class="diff-note">{{ row.probability.note }}</p>
      </article>
    </section>
  </div>
</template>
