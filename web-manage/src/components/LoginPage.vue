<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import LoginMascots from './LoginMascots.vue'
import { createLoginAnimation } from './loginAnimation.js'

const props = defineProps({ loading: Boolean, error: String, success: Boolean })
const emit = defineEmits(['submit', 'enter', 'clear-error'])
const scene = ref(null)
const passwordInput = ref(null)
const password = ref('')
const showPassword = ref(false)
const focused = ref(false)
const localError = ref('')
const message = computed(() => localError.value || props.error || '')
const busy = computed(() => props.loading || props.success)
const status = computed(() => props.success ? '欢迎回来，准备进入控制台' : props.loading ? '正在验证，请稍候…' : message.value ? '再试一次，我们等你' : focused.value || showPassword.value ? '放心输入，我们先看别处' : '准备好，一起管理这座小镇')
let animation, media, entryTimer

function updateState() {
  animation?.setState(props.success ? 'success' : message.value ? 'sad' : focused.value || showPassword.value ? 'password' : 'idle')
}

function restartAnimation() {
  animation?.dispose()
  animation = null
  if (document.hidden) return
  animation = createLoginAnimation(scene.value, { reducedMotion: media.matches })
  updateState()
}

function clearError() {
  localError.value = ''
  emit('clear-error')
}

function submit() {
  if (busy.value) return
  if (!password.value) {
    localError.value = '请输入管理员密码'
    passwordInput.value?.focus()
    return
  }
  clearError()
  emit('submit', password.value)
}

watch([() => props.success, message, focused, showPassword], updateState)
watch(() => props.success, success => {
  window.clearTimeout(entryTimer)
  if (success) {
    password.value = ''
    entryTimer = window.setTimeout(() => emit('enter'), media?.matches ? 0 : 950)
  }
})

onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  restartAnimation()
  media.addEventListener('change', restartAnimation)
  document.addEventListener('visibilitychange', restartAnimation)
})

onBeforeUnmount(() => {
  window.clearTimeout(entryTimer)
  animation?.dispose()
  media?.removeEventListener('change', restartAnimation)
  document.removeEventListener('visibilitychange', restartAnimation)
})
</script>

<template>
  <main class="login-page">
    <section ref="scene" class="login-scene" aria-label="大富翁管理后台">
      <a class="brand" href="/admin/" aria-label="大富翁管理后台首页">
        <span class="brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
        <span>大富翁<span class="brand-caption">管理后台</span></span>
      </a>
      <div class="scene-heading">
        <span class="eyebrow">MILLIONNAIRE / CONTROL CENTER</span>
        <h1>小镇的精彩，<br>由你来设定<span class="heading-dot">.</span></h1>
        <p>从一块地产，到一整局好游戏。</p>
      </div>
      <div class="mascot-stage" aria-hidden="true"><LoginMascots /></div>
      <div class="scene-footer"><span class="status-dot" /><span>{{ status }}</span><span class="scene-number">01 — LET’S PLAY</span></div>
      <canvas id="confettiCanvas" class="confetti" aria-hidden="true" />
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="panel-top"><span>管理控制台</span><span class="access-badge"><span />管理员访问</span></div>
      <div class="login-content">
        <div class="welcome-mark" aria-hidden="true">✦</div>
        <p class="welcome-label">WELCOME BACK</p>
        <h2 id="login-title">欢迎回来</h2>
        <p class="login-description">登录后台，让下一局更有意思。</p>
        <form class="login-form" @submit.prevent="submit" :aria-busy="busy">
          <label for="admin-password">管理员密码</label>
          <div class="password-field" :class="{ invalid: message }">
            <svg class="lock-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="3" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /><path d="M12 14v3" /></svg>
            <input id="admin-password" ref="passwordInput" v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入管理员密码" :disabled="busy" :aria-invalid="!!message" aria-describedby="login-feedback" @focus="focused = true" @blur="focused = false" @input="clearError">
            <button class="password-toggle" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" :disabled="busy" @click="showPassword = !showPassword">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /><path v-if="showPassword" d="m3 3 18 18" /></svg>
            </button>
          </div>
          <p id="login-feedback" class="login-feedback" :class="{ 'has-error': message }" aria-live="polite">{{ props.success ? '登录成功，即将进入控制台' : message || '使用管理员密码安全访问' }}</p>
          <button class="login-submit" type="submit" :disabled="busy">
            <span v-if="props.loading" class="spinner" aria-hidden="true" />
            <span>{{ props.success ? '登录成功' : props.loading ? '正在登录…' : '进入管理后台' }}</span>
            <svg v-if="!busy" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 12h15m-6-6 6 6-6 6" /></svg>
            <span v-if="props.success" aria-hidden="true">✓</span>
          </button>
        </form>
        <div class="login-note"><span aria-hidden="true">✧</span><p>每一个小调整，<br>都是下一局的新惊喜。</p></div>
      </div>
      <footer class="panel-footer"><span>大富翁 · 管理后台</span><span>LET THE GOOD TIMES ROLL.</span></footer>
    </section>
  </main>
</template>

<style scoped>
.login-page { min-height: 100vh; min-height: 100svh; display: grid; grid-template-columns: 58% 42%; color: #252426; background: #fff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; }
.login-page *, .login-page *::before, .login-page *::after { box-sizing: border-box; }
.login-scene { position: relative; min-height: 780px; display: flex; flex-direction: column; padding: 44px 8% 30px; overflow: hidden; background: #eeede9; }
.brand { display: flex; align-items: center; gap: 12px; align-self: flex-start; text-decoration: none; color: #252426; font-size: 20px; font-weight: 750; letter-spacing: 1px; }
.brand-caption { display: block; margin-top: 3px; font-size: 10px; font-weight: 500; color: #77756f; letter-spacing: 4px; }
.brand-mark { width: 38px; height: 38px; display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px; transform: rotate(-9deg); }
.brand-mark i { border-radius: 5px; background: #6b4eff; }
.brand-mark i:nth-child(2) { background: #252426; }
.brand-mark i:nth-child(3) { background: #f4782a; }
.brand-mark i:nth-child(4) { background: #f5c518; }
.scene-heading { position: relative; z-index: 1; margin-top: 65px; }
.eyebrow { color: #797771; font-size: 10px; letter-spacing: 2.4px; }
.scene-heading h1 { margin: 17px 0 14px; font-size: clamp(32px, 3.4vw, 54px); line-height: 1.25; font-weight: 750; letter-spacing: -1px; }
.heading-dot { color: #6b4eff; }
.scene-heading p { margin: 0; font-size: 14px; color: #88857e; letter-spacing: 1px; }
.mascot-stage { flex: 1; display: flex; align-items: flex-end; justify-content: center; min-height: 345px; padding: 18px 0 23px; }
.mascot-stage :deep(svg) { display: block; width: min(100%, 560px); height: auto; max-height: 43svh; overflow: visible; }
.scene-footer { position: relative; display: flex; align-items: center; gap: 8px; font-size: 11px; color: #797771; padding-top: 20px; border-top: 1px solid #dad8d2; }
.status-dot { height: 6px; width: 6px; border-radius: 50%; background: #6b4eff; flex-shrink: 0; }
.scene-number { margin-left: auto; font-size: 9px; letter-spacing: 1px; }
.confetti { display: none; position: fixed; inset: 0; z-index: 20; pointer-events: none; }
.login-panel { display: flex; flex-direction: column; justify-content: space-between; padding: 44px 10% 30px; }
.panel-top, .panel-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 11px; color: #9a9893; }
.access-badge { display: flex; align-items: center; gap: 6px; color: #77756f; }
.access-badge > span { width: 5px; height: 5px; border-radius: 50%; background: #8b9e7b; }
.login-content { width: 100%; max-width: 350px; align-self: center; padding: 50px 0; }
.welcome-mark { font-size: 43px; color: #6b4eff; line-height: 1; margin-bottom: 32px; }
.welcome-label { margin: 0 0 14px; color: #9c9995; font-size: 10px; letter-spacing: 2.5px; }
.login-content h2 { margin: 0 0 12px; font-size: 36px; font-weight: 700; letter-spacing: -1px; }
.login-description { margin: 0; font-size: 13px; color: #9a9691; line-height: 1.8; }
.login-form { margin-top: 43px; }
.login-form label { display: block; font-size: 12px; font-weight: 600; margin-bottom: 11px; }
.password-field { height: 54px; display: flex; align-items: center; border: 1px solid #e4e1dd; background: #faf9f7; border-radius: 12px; padding: 0 14px; transition: border-color .2s, box-shadow .2s; }
.password-field:focus-within { border-color: #6b4eff; box-shadow: 0 0 0 3px #6b4eff12; }
.password-field.invalid { border-color: #c65050; }
.lock-icon { flex-shrink: 0; width: 19px; height: 19px; color: #99948c; }
.password-field input { width: 100%; min-width: 0; padding: 12px; border: 0; outline: 0; background: transparent; font: inherit; font-size: 14px; color: #252426; }
.password-field input::placeholder { color: #b3afa9; font-size: 12px; }
.password-toggle { padding: 6px; margin-right: -6px; background: none; border: 0; display: flex; color: #99948c; cursor: pointer; border-radius: 4px; }
.password-toggle svg { width: 20px; height: 20px; }
.password-toggle:hover { color: #6b4eff; }
.login-feedback { min-height: 32px; margin: 9px 0 14px; font-size: 11px; line-height: 1.6; color: #a39e96; }
.login-feedback.has-error { color: #c65050; }
.login-submit { width: 100%; min-height: 54px; border: 0; border-radius: 12px; background: #29272b; color: #fff; display: flex; align-items: center; justify-content: center; gap: 12px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: background .2s, transform .2s; }
.login-submit:hover:not(:disabled) { background: #6b4eff; transform: translateY(-1px); }
.login-submit svg { width: 18px; height: 18px; }
.login-submit:disabled { cursor: default; opacity: .7; }
button:focus-visible, .brand:focus-visible { outline: 2px solid #6b4eff; outline-offset: 4px; }
.login-note { display: flex; align-items: center; gap: 14px; margin-top: 40px; padding-top: 25px; border-top: 1px solid #eeece8; color: #aca69e; }
.login-note > span { font-size: 26px; color: #b6a9dc; }
.login-note p { margin: 0; font-size: 11px; line-height: 1.8; }
.panel-footer { font-size: 9px; }
.panel-footer > span:last-child { font-size: 8px; letter-spacing: .8px; }
.spinner { width: 14px; height: 14px; border: 2px solid #ffffff50; border-top-color: #fff; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (min-width: 701px) and (max-height: 800px) { .login-scene { min-height: 100svh; padding-top: 30px; padding-bottom: 22px; } .scene-heading { margin-top: 28px; } .scene-heading h1 { font-size: 38px; } .mascot-stage { min-height: 240px; padding: 12px 0 16px; } .mascot-stage :deep(svg) { width: min(90%, 380px); max-height: 37svh; } .login-panel { padding-top: 30px; padding-bottom: 22px; } .login-content { padding: 25px 0; } }
@media (max-width: 1000px) { .login-scene { padding-left: 7%; padding-right: 7%; } .login-panel { padding-left: 9%; padding-right: 9%; } .scene-number { display: none; } .panel-footer > span:last-child { display: none; } }
@media (max-width: 700px) { .login-page { grid-template-columns: 1fr; } .login-scene { min-height: auto; padding: 24px 28px 16px; } .brand { font-size: 17px; } .brand-mark { width: 30px; height: 30px; } .brand-caption { font-size: 8px; } .scene-heading { margin-top: 26px; } .eyebrow { font-size: 8px; } .scene-heading h1 { font-size: 28px; margin: 10px 0; } .scene-heading h1 br { display: none; } .scene-heading p { font-size: 11px; } .mascot-stage { min-height: auto; padding: 6px 0 12px; } .mascot-stage :deep(svg) { width: 220px; } .scene-footer { padding-top: 12px; font-size: 10px; } .login-panel { padding: 24px 28px; } .login-content { padding: 28px 0; } .welcome-mark { display: none; } .welcome-label { margin-bottom: 10px; } .login-content h2 { font-size: 28px; } .login-form { margin-top: 24px; } .login-note { display: none; } }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation: none !important; transition: none !important; } }
</style>
