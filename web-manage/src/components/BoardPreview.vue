<script setup>
import { computed } from 'vue'
import { boardGrid, boardCell } from '../admin/boardGeometry.js'
const props = defineProps({ tiles: Array, names: Array, groups: Array, selected: Number, groupMode: Boolean, boardId: String })
const emit = defineEmits(['select'])
// Same perimeter geometry as client/core/BoardLayout.ts: start at bottom left, then right.
const grid = computed(() => boardGrid(props.tiles?.length))
function cell(index) { return boardCell(index,grid.value) }
const selectedGroup = computed(() => props.groups?.[props.selected] || 0)
const mates = computed(() => props.groupMode && selectedGroup.value > 0 ? (props.tiles || []).map((tile, index) => ({ tile, index })).filter(({index}) => props.groups?.[index] === selectedGroup.value) : [])
const lines = computed(() => mates.value.map(({index}) => { const c = cell(index); return `${(c.col+.5)*100/grid.value.cols},${(c.row+.5)*100/grid.value.rows}` }).join(' '))
const selectedName = computed(() => props.names?.[props.selected] || '选择地图上的一格')
</script>
<template><section class="board-preview surface"><div class="surface-heading"><h3>{{ boardId === 'classic-50' ? '50 格地图' : '30 格地图' }}</h3><span class="muted">与游戏格子顺序一致</span></div><div v-if="tiles?.length && [30,50].includes(tiles.length)" class="board-grid" :style="{ gridTemplateColumns: `repeat(${grid.cols}, minmax(0,1fr))`, gridTemplateRows: `repeat(${grid.rows}, minmax(0,1fr))`, aspectRatio: `${grid.cols}/${grid.rows}` }"><svg v-if="lines" class="board-connections" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><polyline :points="lines" fill="none" stroke="currentColor" stroke-width=".45" /></svg><button v-for="(tile,index) in tiles" :key="index" type="button" class="board-cell" :class="{ selected: index === selected, mate: groupMode && selectedGroup && groups?.[index] === selectedGroup, property: tile.type === 'PROPERTY' }" :style="{ gridColumn: cell(index).col+1, gridRow: cell(index).row+1, '--group-hue': `${(Number(groups?.[index]) || 0)*47 % 360}` }" :aria-label="`第 ${index} 格 ${names?.[index] || tile.type}${groupMode ? '，组号 ' + (groups?.[index] || 0) : ''}`" :aria-pressed="index === selected" @click="emit('select', index)"><span>{{ index }}</span><strong>{{ names?.[index] || tile.type }}</strong><small v-if="groupMode && groups?.[index]">组 {{ groups[index] }}</small></button><div class="board-center"><span>MILLIONNAIRE</span><strong>{{ selectedName }}</strong><small v-if="groupMode">{{ selectedGroup ? `组 ${selectedGroup} · ${mates.length} 块` : '尚未分组' }}</small><small>点选格子定位编辑项</small></div></div><el-empty v-else description="当前地图暂无可预览的格子" :image-size="80" /></section></template>
