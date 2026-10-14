import {test} from 'node:test'
import assert from 'node:assert/strict'
import {pageVisible} from '../src/admin/accessPolicy.js'
import {sectionForField} from '../src/admin/navigation.js'
import {compareVersions} from '../src/admin/versionComparison.js'
test('experience controls use configuration permission and appear in release comparison',()=>{
    assert.equal(pageVisible({permissions:['config:read']},'experience'),true)
    assert.equal(pageVisible({permissions:['audio:read']},'experience'),false)
    assert.equal(sectionForField('experience.emoteCooldownMs'),'experience')
    const groups=compareVersions({experience:{emoteCooldownMs:3000}},{experience:{emoteCooldownMs:1500}})
    const row=groups.find(g=>g.id==='experience').rows[0]
    assert.match(row.label,/表情/);assert.match(row.after,/1,500|1500/);assert.match(row.after,/毫秒/)
})
