const assets = ['tangtang','keke','ajie','naicha','akai','yuanyuan','doudou','maomao']
export function bundledImage(id, slot = 'avatar') {
  const key = assets[Number(id)]
  if (!key || !['avatar','pawn','portrait'].includes(slot)) return null
  const kind = slot === 'avatar' ? 'avatar' : 'pawn'
  return `/admin/art/${kind}s/${String(Number(id)+1).padStart(2,'0')}-${kind}_${key}.png`
}
export function characterImage(character, slot = 'avatar') {
  return character?.images?.[slot]?.url || (character?.bundled ? bundledImage(character.id,slot) : null)
}
