export const roleLabels={SUPER_ADMIN:'超级管理员',OPERATOR:'运营管理员',VIEWER:'只读观察员'}
export const accessPages=['accounts','roles','audit','sessions','password','myPermissions']
export function can(identity,permission){return !identity?.mustChangePassword&&!!identity?.permissions?.includes(permission)}
export function pageVisible(identity,page){
  if(!identity||identity.mustChangePassword)return false
  if(['sessions','password','myPermissions'].includes(page))return true
  if(!['workbench','dashboard','characters','history','accounts','roles','audit','audio','names','sets','prices','money','rentRise','room','events','startPick','roundReward','cityEvents','funTitles','series','team','timing','announcement'].includes(page))return false
  const permission={workbench:'dashboard:read',dashboard:'dashboard:read',characters:'character:read',history:'config:read',accounts:'admin-user:manage',roles:'admin-user:manage',audit:'audit:read',audio:'audio:read'}[page]||'config:read'
  return can(identity,permission)
}
export function passwordError(value){
  if(!value||Array.from(value).length<12||Array.from(value).length>64||new TextEncoder().encode(value).length>72||/[\u0000-\u001f\u007f]/.test(value))return '密码须为12～64个字符，UTF-8不超过72字节且不能含控制符'
  return ''
}
