import test from 'node:test'
import assert from 'node:assert/strict'
import { pageVisible } from '../src/admin/accessPolicy.js'
import { pages } from '../src/admin/navigation.js'

test('a history-only custom role can reach its first permitted screen without configuration access',()=>{
  const identity={user:{role:'CUSTOM_HISTORY'},permissions:['player:read','game:read']}
  assert.equal(pages.find(page=>pageVisible(identity,page.id)).id,'players')
  for(const page of ['players','games','myPermissions','password','sessions'])assert.equal(pageVisible(identity,page),true)
  for(const page of ['workbench','events','accounts','roles','audit','releases','media'])assert.equal(pageVisible(identity,page),false)
})
test('approval and destructive media capabilities are independently gated and mandatory rotation wins',()=>{
  const identity={permissions:['release:read']}
  assert.equal(pageVisible(identity,'releases'),true)
  assert.equal(pageVisible(identity,'media'),false)
  assert.equal(pageVisible({permissions:['media:manage']},'media'),true)
  assert.equal(pageVisible({...identity,mustChangePassword:true},'releases'),false)
})
