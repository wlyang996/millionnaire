import test from 'node:test'
import assert from 'node:assert/strict'
import { can,pageVisible,passwordError } from '../src/admin/accessPolicy.js'
test('viewer may inspect published content while account management and mutation remain unavailable',()=>{
  const user={permissions:['dashboard:read','config:read','character:read','audio:read']}
  for(const page of ['dashboard','characters','audio','history','events','myPermissions','sessions','password'])assert.equal(pageVisible(user,page),true)
  for(const page of ['accounts','roles','audit'])assert.equal(pageVisible(user,page),false)
  assert.equal(pageVisible(user,'unknown-page'),false)
  for(const permission of ['config:publish','config:rollback','character:draft','character:write','audio:draft','audio:write','audio:publish','audio:rollback'])assert.equal(can(user,permission),false)
})
test('mandatory password rotation blocks normal navigation even with administrator permissions',()=>{
  const user={mustChangePassword:true,permissions:['admin-user:manage','config:publish','config:read']}
  assert.equal(can(user,'config:publish'),false);assert.equal(pageVisible(user,'accounts'),false);assert.equal(pageVisible(null,'dashboard'),false)
})
test('password validation measures Unicode code points and the actual bcrypt byte boundary',()=>{
  assert.equal(passwordError('safe-test-password'), '')
  assert.equal(passwordError('密'.repeat(24)), '')
  assert.notEqual(passwordError('密'.repeat(25)), '')
  assert.notEqual(passwordError('short'), '')
  assert.notEqual(passwordError('safe-test-password\n'), '')
})
