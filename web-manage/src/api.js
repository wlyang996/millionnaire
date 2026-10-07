// 管理接口：令牌存在 sessionStorage（关掉标签页即失效）
const KEY = 'millionnaire-admin-token'

export function token() {
  try { return sessionStorage.getItem(KEY) } catch { return null }
}

export function setToken(t) {
  try { t ? sessionStorage.setItem(KEY, t) : sessionStorage.removeItem(KEY) } catch { /* 忽略 */ }
}

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code || ('HTTP ' + status))
    this.status = status
    this.code = code
  }
}

export async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  const t = token()
  if (t) headers.Authorization = 'Bearer ' + t
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  let data = null
  try { data = await res.json() } catch { /* 空响应 */ }
  if (!res.ok) {
    if (res.status === 401) setToken(null)
    throw new ApiError(res.status, data && data.code, data && data.message)
  }
  return data
}
