<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { motion } from 'motion-v'
import { Orbit, Map, SlidersHorizontal, UsersRound, Music2, Megaphone, History, Search, Menu, X, Sun, Moon, LogOut, Sparkles, ChevronDown, ChevronRight, Check, Gamepad2, ShieldCheck, Star } from '@lucide/vue'
import { groups, pageById } from '../admin/navigation.js'
import { pageTone, groupTone } from '../admin/controlColors.js'
import { pageVisible, roleLabels } from '../admin/accessPolicy.js'
import { useAdminPreferences } from '../admin/preferences.js'
import BorderBeam from './effects/BorderBeam.vue'
import AdminMascot from './AdminMascot.vue'
import { usePageMotion } from '../admin/usePageMotion.js'
import { useWorkbench } from '../admin/useWorkbench.js'
import CommandPalette from './CommandPalette.vue'
const OrbEffect = defineAsyncComponent(() => import('./effects/Orb.vue'))
const props = defineProps({ identity:Object, activePage: String, version: Number, dirty: Boolean, searchIndex: Object, celebration: Number, busy: Boolean, hasError: Boolean })
const emit = defineEmits(['navigate', 'parameter', 'logout'])
const { favorites, toggle, visit } = useWorkbench()
watch(() => `${props.identity?.user?.id}:${props.activePage}`, () => visit(props.activePage), {immediate:true})
const icons = { Orbit, Map, SlidersHorizontal, UsersRound, Music2, Megaphone, History, ShieldCheck }
const { theme, motionLevel, motionEnabled, richMotion } = useAdminPreferences()
const pageContent = ref(null)
usePageMotion(pageContent, () => props.activePage)
const query = ref(''), mobileOpen = ref(false), collapsed = ref({}), paused = ref(document.hidden)
const isMobile = ref(false)
let mobileMedia
const page = computed(() => pageById[props.activePage] || pageById.dashboard)
watch(() => props.activePage, id => { document.documentElement.dataset.adminAccent=pageTone(id) }, {immediate:true})
const visibleGroups = computed(() => groups.map(group => ({ ...group, pages: group.pages.filter(([id, label, title, description]) => pageVisible(props.identity,id) && `${group.label}${label}${title}${description}${props.searchIndex?.[id] || ''}`.includes(query.value.trim())) })).filter(group => group.pages.length))
function navigate(id) { emit('navigate', id); mobileOpen.value = false }
function toggleTheme(event) {
  const change = () => { theme.value = theme.value === 'dark' ? 'light' : 'dark' }
  if (!motionEnabled.value || !document.startViewTransition) { change(); return }
  const rect = event.currentTarget.getBoundingClientRect(), x = event.clientX || rect.left+rect.width/2, y = event.clientY || rect.top+rect.height/2
  const radius = Math.hypot(Math.max(x,innerWidth-x),Math.max(y,innerHeight-y))
  const transition = document.startViewTransition(change)
  transition.ready.then(() => document.documentElement.animate({ clipPath:[`circle(0px at ${x}px ${y}px)`,`circle(${radius}px at ${x}px ${y}px)`] },{duration:450,easing:'ease-out',pseudoElement:'::view-transition-new(root)'})).catch(()=>{})
}
const visibility = () => { paused.value = document.hidden }
const updateMobile = () => { isMobile.value = mobileMedia.matches; if (!isMobile.value) mobileOpen.value = false }
onMounted(() => { document.addEventListener('visibilitychange', visibility); mobileMedia=matchMedia('(max-width:760px)'); updateMobile(); mobileMedia.addEventListener('change',updateMobile) })
onBeforeUnmount(() => { document.removeEventListener('visibilitychange', visibility); mobileMedia?.removeEventListener('change',updateMobile); delete document.documentElement.dataset.adminAccent })
</script>
<template>
  <div class="admin-shell" :data-paused="paused" :class="{ 'nav-open': mobileOpen }">
    <button v-if="mobileOpen" type="button" class="sidebar-scrim" aria-label="关闭导航" @click="mobileOpen = false" />
    <aside class="admin-sidebar" aria-label="管理导航" :inert="isMobile && !mobileOpen">
      <div class="admin-brand"><span class="admin-brand-mark"><Gamepad2 :size="24" /></span><span>大富翁<small>CONTROL CENTER</small></span><button class="icon-button mobile-only" aria-label="关闭导航" @click="mobileOpen = false"><X :size="18" /></button></div>
      <el-input v-model="query" placeholder="搜索页面与参数" clearable aria-label="搜索页面与参数"><template #prefix><Search :size="15" /></template></el-input>
      <nav class="admin-nav">
        <div v-for="group in visibleGroups" :key="group.id" class="admin-nav-group" :data-control-tone="groupTone(group.id)">
          <button type="button" class="nav-group-label" :aria-expanded="query ? true : !collapsed[group.id]" @click="collapsed[group.id] = !collapsed[group.id]"><component :is="icons[group.icon]" :size="15" /><span>{{ group.label }}</span><ChevronDown :size="13" :class="{ rotated: collapsed[group.id] && !query }" /></button>
          <div v-show="query || !collapsed[group.id]" class="nav-pages">
            <motion.button v-for="[id, label] in group.pages" :key="id" type="button" class="nav-page" :data-control-tone="pageTone(id)" :class="{ active: activePage === id }" :aria-current="activePage === id ? 'page' : undefined" :while-hover="motionEnabled ? { x: 3 } : undefined" :while-press="motionEnabled ? { scale: .97 } : undefined" @click="navigate(id)">
              <motion.span v-if="activePage === id" class="nav-active" layout-id="admin-active-page" :transition="{ type: 'spring', stiffness: 330, damping: 30, duration: motionEnabled ? undefined : 0 }" />
              <span class="nav-page-dot" /><span>{{ label }}</span><ChevronRight v-if="activePage === id" :size="13" />
            </motion.button>
          </div>
        </div>
        <p v-if="!visibleGroups.length" class="muted">没有匹配的页面或参数</p>
      </nav>
      <div class="sidebar-footer"><span class="status-dot" /><span>让下一局更有意思</span><Sparkles :size="14" /></div>
    </aside>
    <div class="admin-main">
      <header class="admin-topbar"><div class="breadcrumbs"><button class="icon-button mobile-only" aria-label="打开导航" @click="mobileOpen = true"><Menu :size="20" /></button><span>{{ page.group }}</span><ChevronRight :size="12" /><strong>{{ page.label }}</strong></div><div class="topbar-actions"><CommandPalette :identity="identity" :search-index="searchIndex" @navigate="navigate" @parameter="emit('parameter',$event)" /><button class="icon-button page-star" :aria-label="(favorites.includes(activePage)?'取消收藏':'收藏')+page.label" :aria-pressed="favorites.includes(activePage)" @click="toggle(activePage)"><Star :size="17" :fill="favorites.includes(activePage)?'currentColor':'none'" /></button><span class="version-badge">{{ version === undefined ? '读取版本…' : version === 0 ? '内置默认' : '版本 #' + version }}</span><span v-if="dirty" class="draft-badge">有未发布修改</span><button class="icon-button" :aria-label="theme === 'dark' ? '切换浅色主题' : '切换深色主题'" @click="toggleTheme"><Sun v-if="theme === 'dark'" :size="18" /><Moon v-else :size="18" /></button><el-select v-model="motionLevel" class="motion-select" aria-label="动效偏好"><el-option label="丰富动效" value="rich" /><el-option label="标准动效" value="standard" /><el-option label="简化动效" value="reduced" /></el-select><el-dropdown @command="navigate"><button class="admin-profile-button"><ShieldCheck :size="16" /><span>{{ identity?.user?.displayName }}<small> · {{ roleLabels[identity?.user?.role] || identity?.user?.role }}</small></span><ChevronDown :size="12" /></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="myPermissions">我的权限</el-dropdown-item><el-dropdown-item command="sessions">我的会话</el-dropdown-item><el-dropdown-item command="password">修改密码</el-dropdown-item></el-dropdown-menu></template></el-dropdown><button class="icon-button" aria-label="退出登录" @click="emit('logout')"><LogOut :size="17" /></button></div></header>
      <main class="admin-workspace">
        <div class="admin-hero"><div class="hero-aurora" aria-hidden="true" /><div class="hero-copy"><span class="eyebrow">MILLIONNAIRE / {{ page.group }}</span><Transition name="hero-title" mode="out-in"><div :key="page.id"><h1>{{ page.title }}</h1><p>{{ page.description }}</p></div></Transition><span class="hero-caption"><span class="status-dot" />{{ dirty ? '编辑中的修改，发布后才会生效' : '每一个小调整，都是下一局的新惊喜' }}</span></div><div class="hero-orb" aria-hidden="true"><OrbEffect v-if="richMotion" :enabled="!paused" :hue="18" background-color="#000000" /><div v-else class="orb-static" /></div><AdminMascot v-if="['dashboard','workbench'].includes(activePage)" :busy="busy" :error="hasError" :success="celebration" /><BorderBeam /></div>
        <Transition name="page-enter" mode="out-in"><div :key="page.id" class="page-heading"><h2>{{ page.label }}</h2><span>{{ page.group }}</span></div></Transition>
        <div ref="pageContent" class="admin-page-content"><slot /></div>
      </main>
      <footer class="admin-footer"><span>大富翁 · 管理控制台</span><span>LET THE GOOD TIMES ROLL.</span></footer>
    </div>
    <Transition name="celebrate"><div v-if="celebration" :key="celebration" class="celebration-toast" role="status"><Check :size="18" />发布成功，新版本已生效<span v-for="i in 12" :key="i" class="confetti-bit" :style="{ '--angle': `${i * 30}deg`, '--delay': `${i % 3 * .06}s` }" /></div></Transition>
  </div>
</template>
