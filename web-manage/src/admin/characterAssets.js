import { poseAtlases } from './characterPoseAtlas.js'
const assets = ['tangtang','keke','ajie','naicha','akai','yuanyuan','doudou','maomao']
const companions = {
  celebrate: { src: '/admin/art/team_poses/team_trophy.png', label: '共用奖杯', left: '49.5%', top: '13.7%', width: '50.5%' },
  worry: { src: '/admin/art/team_poses/team_debt_thought.png', label: '欠款思考气泡', left: '65%', top: '0%', width: '34%' },
}
const indexOf = id => typeof id === 'number' && Number.isInteger(id) && id >= 0 && id < assets.length ? id : typeof id === 'string' && /^[0-7]$/.test(id) ? Number(id) : -1
export function bundledImage(id, slot = 'avatar') {
  const index = indexOf(id), key = assets[index]
  const pose = {pawncrouch:'crouch',pawnairborne:'airborne',pawnland:'land',pawnready:'ready'}[slot]
  if(key && pose) return `/admin/art/motion/pawn_${key}_${pose}.png`
  if (!key || !['avatar','pawn','portrait'].includes(slot)) return null
  if (slot === 'portrait') return `/admin/art/portraits/information_host_${key}.png`
  const kind = slot
  return `/admin/art/${kind}s/${String(index+1).padStart(2,'0')}-${kind}_${key}.png`
}
export function bundledVisual(id, slot = 'avatar') {
  const image = bundledImage(id, slot)
  if (image) return { src: image }
  const index = indexOf(id)
  if (index < 0 || !poseAtlases[slot]) return null
  const sheet = poseAtlases[slot]
  return { src: sheet.src, region: sheet.regions[index], sheet: { width: sheet.width, height: sheet.height }, ...(companions[slot] ? { companion: companions[slot] } : {}) }
}
export function characterVisual(character, slot = 'avatar') {
  const remote = character?.images?.[slot]?.url
  return remote ? { src: remote } : character?.bundled ? bundledVisual(character.id, slot) : null
}
export function characterImage(character, slot = 'avatar') {
  return character?.images?.[slot]?.url || (character?.bundled ? bundledImage(character.id,slot) : null)
}
