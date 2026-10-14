<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Search, ArrowUpRight, Star, Command, CornerDownLeft } from '@lucide/vue'
import { commandResults, highlightParts } from '../admin/workbenchState.js'
import { useWorkbench } from '../admin/useWorkbench.js'
import { theme, motionLevel } from '../admin/preferences.js'
const props = defineProps({ identity: Object, searchIndex: Object })
const emit = defineEmits(['navigate', 'parameter'])
const open = ref(false), query = ref(''), selected = ref(0), input = ref(null), list = ref(null)
const { favorites, recent } = useWorkbench()
const actions = computed(() => [
  { id:'action:theme', label:theme.value === 'dark' ? '切换浅色主题' : '切换深色主题', detail:'外观偏好 · 立即切换' },
  { id:'action:motion', label:'切换动效级别', detail:`当前：${{ rich:'丰富动效', standard:'标准动效', reduced:'简化动效' }[motionLevel.value]}` }
])
const results = computed(() => commandResults(props.identity, query.value, props.searchIndex, favorites.value, recent.value, actions.value))
watch([query, results], () => { selected.value = 0 })
function show() { pending = null; query.value = ''; selected.value = 0; open.value = true }
function shortcut(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && !event.isComposing && !event.altKey) {
    // Do not steal focus from a publish/password confirmation dialog.
    if (!open.value && [...document.querySelectorAll('.el-overlay-dialog')].some(dialog=>dialog.getClientRects().length)) return
    event.preventDefault(); open.value ? open.value = false : show()
  }
}
function keydown(event) {
  if (event.isComposing) return
  if (['ArrowDown','ArrowUp'].includes(event.key)) {
    event.preventDefault()
    const count = results.value.length
    if (!count) return
    selected.value = (selected.value + (event.key === 'ArrowDown' ? 1 : -1) + count) % count
    nextTick(() => list.value?.querySelector(`[data-result="${selected.value}"]`)?.scrollIntoView({block:'nearest'}))
  } else if (event.key === 'Enter') { event.preventDefault(); choose(results.value[selected.value]) }
}
let pending
function choose(result) {
  if (!result) return
  pending = result; open.value = false
}
function dispatch() {
  const result = pending; pending = null
  if (!result) return
  if (result.id === 'action:theme') theme.value = theme.value === 'dark' ? 'light' : 'dark'
  else if (result.id === 'action:motion') motionLevel.value = {rich:'standard',standard:'reduced',reduced:'rich'}[motionLevel.value] || 'rich'
  else if (result.path) emit('parameter', result)
  else emit('navigate', result.page)
}
onMounted(() => window.addEventListener('keydown', shortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', shortcut))
</script>
<template>
  <button type="button" class="command-trigger" aria-label="打开全局命令面板，Ctrl K 或 Command K" @click="show"><Search :size="16" /><span>搜索与快捷操作</span><kbd>Ctrl K</kbd></button>
  <el-dialog v-model="open" title="全局命令面板" width="min(680px, 94vw)" class="command-dialog" append-to-body destroy-on-close @opened="input?.focus()" @closed="dispatch">
    <div class="command-search"><Search :size="20" /><input ref="input" v-model="query" placeholder="搜索页面、参数，或切换主题…" role="combobox" aria-label="搜索命令" aria-autocomplete="list" aria-expanded="true" aria-controls="admin-command-results" :aria-activedescendant="results.length ? `admin-command-${selected}` : undefined" @keydown="keydown" /></div>
    <div class="command-count" aria-live="polite">{{ query ? `找到 ${results.length} 项结果` : '收藏优先 · 输入关键词查找参数' }}</div>
    <div id="admin-command-results" ref="list" class="command-results" role="listbox" aria-label="命令结果">
      <div v-for="(result,index) in results" :id="`admin-command-${index}`" :key="result.id" role="option" :aria-selected="index===selected" :data-result="index" class="command-result" :class="{ selected:index===selected }" @pointermove="selected=index" @mousedown.prevent @click="choose(result)">
        <span class="command-result-icon"><Star v-if="favorites.includes(result.page)" :size="18" /><Command v-else-if="result.kind==='快捷操作'" :size="18" /><ArrowUpRight v-else :size="18" /></span>
        <span class="command-result-copy"><strong><template v-for="(part,i) in highlightParts(result.label,query)" :key="i"><mark v-if="part.marked">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></strong><small>{{ result.detail }}</small></span><span class="command-kind">{{ result.kind }}</span><CornerDownLeft v-if="index===selected" :size="14" />
      </div>
      <p v-if="!results.length" class="command-empty">没有匹配项，试试“起点奖励”“音频”或“组队”。</p>
    </div>
    <div class="command-help"><span>↑ ↓ 选择 · Enter 打开 · Esc 关闭</span><span>切换页面保留编辑草稿</span></div>
  </el-dialog>
</template>
