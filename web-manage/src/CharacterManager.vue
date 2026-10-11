<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { call, token, ApiError } from './api.js'

const labels = { avatar: '普通头像', pawn: '棋盘棋子', portrait: '半身人物', give: '递币', receive: '接币', worry: '欠款忧虑', celebrate: '托杯庆祝' }
const slots = Object.keys(labels)
const state = ref(null), pack = ref(null), busy = ref(false), previews = ref({})
const fresh = () => ({ avatar: null, name: '', sort: 8, enabled: true, fallback: 0 })
const form = ref(fresh())
const complete = computed(() => slots.every(s => pack.value?.character.images[s]))
const choices = computed(() => state.value?.active.characters || [])
const progress = computed(() => slots.filter(s => pack.value?.character.images[s]).length)
const dirty = computed(() => {
  const c = pack.value?.character
  return !!c && (form.value.name !== c.name || form.value.sort !== c.sort || form.value.enabled !== c.enabled || form.value.fallback !== c.fallback)
})
function fail(e) { ElMessage.error(e.message || '人物素材操作失败') }
async function checkStorage() {
  busy.value = true
  try { const r = await call('POST', '/admin-api/v1/characters/storage/check', {}); ElMessage.success(r.message) }
  catch (e) { fail(e) } finally { busy.value = false }
}
function clearPreviews() { Object.values(previews.value).forEach(URL.revokeObjectURL); previews.value = {} }
async function load() {
  busy.value = true
  try { state.value = await call('GET', '/admin-api/v1/characters') } catch (e) { fail(e) }
  finally { busy.value = false }
}
function reset() { clearPreviews(); pack.value = null; form.value = fresh() }
async function select(p) {
  clearPreviews(); pack.value = p
  form.value = { avatar: p.character.id, name: p.character.name, sort: p.character.sort, enabled: p.character.enabled, fallback: p.character.fallback }
  for (const slot of slots) if (p.character.images[slot]) {
    try {
      const r = await fetch(`/admin-api/v1/characters/packs/${p.packId}/assets/${slot}.png`, { headers: { Authorization: 'Bearer ' + token() } })
      if (!r.ok) throw new ApiError(r.status, '', '预览读取失败')
      const url = URL.createObjectURL(await r.blob())
      if (pack.value?.packId !== p.packId) URL.revokeObjectURL(url)
      else previews.value[slot] = url
    } catch (e) { fail(e) }
  }
}
async function save() {
  busy.value = true
  try {
    const path = '/admin-api/v1/characters/packs' + (pack.value ? '/' + pack.value.packId : '')
    pack.value = await call('POST', path, { ...form.value, revision: pack.value?.revision })
    await load(); ElMessage.success('整套素材草稿已保存')
  } catch (e) { fail(e) } finally { busy.value = false }
}
async function upload(slot, event) {
  const file = event.target.files[0]; event.target.value = ''
  if (!file || !pack.value) return
  if (file.size > 5 * 1024 * 1024) return ElMessage.error('每张图片不可超过 5MB')
  busy.value = true
  try {
    const body = new FormData(); body.append('file', file); body.append('revision', String(pack.value.revision))
    const r = await fetch(`/admin-api/v1/characters/packs/${pack.value.packId}/assets/${slot}`, { method: 'POST', headers: { Authorization: 'Bearer ' + token() }, body })
    const data = await r.json()
    if (!r.ok) throw new ApiError(r.status, data.code, data.message)
    pack.value = data
    if (previews.value[slot]) URL.revokeObjectURL(previews.value[slot])
    previews.value[slot] = URL.createObjectURL(file)
    await load(); ElMessage.success(labels[slot] + '已上传到 COS')
  } catch (e) { fail(e) } finally { busy.value = false }
}
async function publish() {
  if (dirty.value) return ElMessage.warning('请先保存人物资料，再发布整套素材')
  try { await ElMessageBox.confirm('发布完整人物素材后，新房间将使用新版本；进行中的房间保持原版本。', '发布整套素材') }
  catch { return }
  busy.value = true
  try {
    await call('POST', '/admin-api/v1/characters/publish', { packId: pack.value.packId, revision: pack.value.revision, expectedVersion: state.value.active.version })
    reset(); await load(); ElMessage.success('整套人物素材已发布')
  } catch (e) { fail(e) } finally { busy.value = false }
}
async function rollback(version) {
  try { await ElMessageBox.confirm(`将人物目录回滚到版本 ${version}，已有房间不受影响。`, '回滚人物目录') }
  catch { return }
  busy.value = true
  try { await call('POST', '/admin-api/v1/characters/rollback', { version, expectedVersion: state.value.active.version }); await load(); ElMessage.success('已回滚') }
  catch (e) { fail(e) } finally { busy.value = false }
}
function replace(c) { reset(); form.value = { avatar: c.id, name: c.name, sort: c.sort, enabled: c.enabled, fallback: c.fallback } }
async function configure() {
  busy.value = true
  try { await call('POST', '/admin-api/v1/characters/configure', { id: form.value.avatar, name: form.value.name, sort: form.value.sort, enabled: form.value.enabled, expectedVersion: state.value.active.version }); await load(); ElMessage.success('名称、排序和选择状态已发布，整套图片保持原版本') }
  catch (e) { fail(e) } finally { busy.value = false }
}
onMounted(load)
onBeforeUnmount(clearPreviews)
</script>

<template>
  <div v-loading="busy">
    <p>图片存放在微信云托管对象存储；每位人物必须包含完整七项素材，统一发布。机器人编号 8 保留。</p>
    <el-alert v-if="state && !state.storageReady" title="对象存储尚未配置" description="在云托管配置 COS_BUCKET、COS_REGION，不需要长期密钥。" type="warning" :closable="false" />
    <p>后台自动获取并刷新微信云托管临时凭证。请在“云调用”放行 /_/cos/getauth 和 /_/cos/metaid/encode。桶名、地域齐全只代表参数已配置，连接及读写还需验证。</p>
    <el-button :disabled="!state?.storageReady || busy" @click="checkStorage">检查临时凭证</el-button>
    <el-alert v-if="state && !state.persistent" title="当前未连接数据库，素材目录仅存内存，重启会丢失；正式使用须启用 db 配置。" type="warning" :closable="false" />
    <h3>生效人物目录 · 版本 {{ state?.active.version ?? '—' }}</h3>
    <el-table :data="choices" border>
      <el-table-column prop="id" label="人物 ID" width="100" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="sort" label="排序" width="90" />
      <el-table-column label="状态" width="130"><template #default="{row}">{{ row.enabled ? '可选' : '已停用' }}</template></el-table-column>
      <el-table-column label="来源" width="130"><template #default="{row}">{{ row.bundled ? '客户端默认' : 'COS 整套素材' }}</template></el-table-column>
      <el-table-column label="操作" width="220"><template #default="{row}"><el-button @click="replace(row)">配置 / 替换整套</el-button></template></el-table-column>
    </el-table>
    <h3>编辑整套素材</h3>
    <el-button @click="reset">新建人物</el-button>
    <el-button @click="load">刷新目录</el-button>
    <el-form label-width="120px" class="character-form">
      <el-form-item label="人物 ID"><span>{{ pack?.character.id ?? form.avatar ?? '保存草稿后自动分配' }}</span></el-form-item>
      <el-form-item label="名称"><el-input v-model="form.name" maxlength="20" :disabled="pack?.published" /></el-form-item>
      <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="9999" :disabled="pack?.published" /></el-form-item>
      <el-form-item label="可供选择"><el-switch v-model="form.enabled" :disabled="pack?.published" /><span class="character-note">停用仅隐藏新选择，不改变已有账号人物。</span></el-form-item>
      <el-form-item label="本地备用人物"><el-select v-model="form.fallback" :disabled="pack?.published"><el-option v-for="(name,id) in ['糖糖','可可','阿杰','奶茶','阿凯','圆圆','豆豆','毛毛']" :key="id" :value="id" :label="name" /></el-select><span class="character-note">下载失败时整套回退，避免混用人物。</span></el-form-item>
      <el-form-item><el-button type="primary" :disabled="pack?.published" @click="save">{{ pack ? '保存资料' : '创建整套草稿' }}</el-button><el-button v-if="!pack && form.avatar !== null" @click="configure">仅发布名称、排序、启停</el-button></el-form-item>
    </el-form>
    <p>单张 PNG 不超过 5MB，宽高 64～2048；头像为正方形，其余人物图须透明背景，且每张只包含一位人物。</p>
    <div class="character-images">
      <div v-for="slot in slots" :key="slot" class="character-image">
        <strong>{{ labels[slot] }}</strong>
        <div class="character-preview"><img v-if="previews[slot]" :src="previews[slot]" :alt="labels[slot]" /><span v-else>未上传</span></div>
        <small v-if="pack?.character.images[slot]">{{ pack.character.images[slot].width }} × {{ pack.character.images[slot].height }}</small>
        <input type="file" accept="image/png" :disabled="busy || !pack || pack.published || !state?.storageReady" @change="upload(slot, $event)" />
      </div>
    </div>
    <p>{{ progress }} / 7 已上传。{{ pack?.published ? '已发布，不可覆盖' : dirty ? '资料有未保存修改，请先保存。' : '七项完整后可统一发布。' }}</p>
    <el-button type="success" :disabled="!complete || !pack || pack.published || dirty || !state?.storageReady || busy" @click="publish">发布完整七项素材</el-button>
    <h3>素材草稿与版本</h3>
    <el-table :data="state?.packs || []" border>
      <el-table-column label="人物"><template #default="{row}">{{ row.character.name }} · ID {{ row.character.id }}</template></el-table-column>
      <el-table-column label="完整度"><template #default="{row}">{{ Object.keys(row.character.images).length }} / 7</template></el-table-column>
      <el-table-column label="状态"><template #default="{row}">{{ row.published ? '已发布' : '草稿' }}</template></el-table-column>
      <el-table-column label="操作"><template #default="{row}"><el-button @click="select(row)">查看 / 编辑</el-button></template></el-table-column>
    </el-table>
    <h3>目录发布历史</h3>
    <el-button v-for="v in state?.history || []" :key="v" :disabled="v === state?.active.version" @click="rollback(v)">回滚到 {{ v === 0 ? '内置默认' : '#' + v }}</el-button>
  </div>
</template>

<style scoped>
.character-form { max-width: 740px; margin-top: 20px; }
.character-note { color: #697386; margin-left: 12px; }
.character-images { display: grid; grid-template-columns: repeat(auto-fit,minmax(190px,1fr)); gap: 14px; margin: 20px 0; }
.character-image { border: 1px solid #dce2e9; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
.character-preview { height: 180px; display: flex; align-items: center; justify-content: center; background: repeating-conic-gradient(#edf0f3 0% 25%,white 0% 50%) 50% / 20px 20px; }
.character-preview img { max-width: 100%; max-height: 100%; object-fit: contain; }
.character-image input { max-width: 100%; }
</style>
