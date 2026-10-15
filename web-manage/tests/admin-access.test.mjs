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
test('password validation accepts 6 through 12 Unicode code points and rejects control characters',()=>{
  for(const value of ['abcdef','a'.repeat(12),'密'.repeat(6),'密'.repeat(12),'😀'.repeat(6),'😀'.repeat(12)])assert.equal(passwordError(value),'')
  for(const value of [null,'','short','a'.repeat(13),'密'.repeat(13),'😀'.repeat(13),'abcde\n','abcde\u0085'])assert.notEqual(passwordError(value),'')
})
