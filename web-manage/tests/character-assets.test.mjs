import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { bundledImage, bundledVisual, characterVisual } from '../src/admin/characterAssets.js'

const slots = ['avatar', 'pawn', 'portrait', 'give', 'receive', 'worry', 'celebrate']
const names = ['tangtang','keke','ajie','naicha','akai','yuanyuan','doudou','maomao']
const source = fs.readFileSync(new URL('../../client/assets/scripts/ui/TeamPoseRegions.ts', import.meta.url), 'utf8')
const sourceRegions = JSON.parse(source.slice(source.indexOf('=') + 1).trim().replace(/;\s*$/, ''))

test('all eight builtin identities resolve seven poses and exactly match the client atlas bounds', () => {
  for (let id = 0; id < 8; id++) for (const slot of slots) {
    const asset = bundledVisual(id, slot)
    assert.ok(asset?.src)
    const bytes = fs.readFileSync(new URL('../public/' + asset.src.replace('/admin/', ''), import.meta.url))
    assert.ok(bytes.length > 0)
    if (asset.region) {
      assert.deepEqual(asset.region, sourceRegions['team_pose_' + slot][id])
      assert.equal(asset.sheet.width, bytes.readUInt32BE(16))
      assert.equal(asset.sheet.height, bytes.readUInt32BE(20))
      assert.ok(asset.region.x + asset.region.w <= asset.sheet.width)
      assert.ok(asset.region.y + asset.region.h <= asset.sheet.height)
    }
  }
  assert.notEqual(bundledImage(0, 'portrait'), bundledImage(0, 'pawn'))
})

test('preview copies preserve every portrait and atlas original byte for byte', () => {
  for (const name of names) assert.ok(fs.readFileSync(new URL(`../public/art/portraits/information_host_${name}.png`, import.meta.url)).equals(fs.readFileSync(new URL(`../../client/assets/resources/art/completion/information_host_${name}.png`, import.meta.url))))
  for (const pose of slots.slice(3)) assert.ok(fs.readFileSync(new URL(`../public/art/team_poses/team_pose_${pose}.png`, import.meta.url)).equals(fs.readFileSync(new URL(`../../client/assets/resources/art/team_poses/team_pose_${pose}.png`, import.meta.url))))
  for (const prop of ['team_trophy', 'team_debt_thought']) assert.ok(fs.readFileSync(new URL(`../public/art/team_poses/${prop}.png`, import.meta.url)).equals(fs.readFileSync(new URL(`../../client/assets/resources/art/team_poses/${prop}.png`, import.meta.url))))
  assert.equal(bundledVisual(0, 'celebrate').companion.src, '/admin/art/team_poses/team_trophy.png')
  assert.equal(bundledVisual(7, 'worry').companion.src, '/admin/art/team_poses/team_debt_thought.png')
})

test('missing or invalid IDs and the reserved robot never borrow a human identity', () => {
  for (const id of [null, undefined, '', ' ', false, -1, 0.5, 8, 9, '99']) for (const slot of slots) assert.equal(bundledVisual(id, slot), null)
  assert.equal(bundledVisual(0, 'missing'), null)
  assert.deepEqual(bundledVisual('7', 'receive'), bundledVisual(7, 'receive'))
})

test('cloud portraits and actions keep their own assets without mixing builtin fallback poses', () => {
  const custom = { id: 10, bundled: false, fallback: 1, images: { portrait: { url: '/api/characters/assets/pack/portrait.png' }, give: { url: '/api/characters/assets/pack/give.png' } } }
  assert.deepEqual(characterVisual(custom, 'portrait'), { src: custom.images.portrait.url })
  assert.deepEqual(characterVisual(custom, 'give'), { src: custom.images.give.url })
  assert.equal(characterVisual(custom, 'receive'), null)
  assert.equal(characterVisual(null, 'portrait'), null)
})
