const {test}=require('node:test');const assert=require('node:assert/strict');
const {TurnReminder}=require('../temp/experience-tests/TurnReminder.js');
test('reminder waits for actionable foreground turn and survives refresh',()=>{
 const r=new TurnReminder();
 assert.equal(r.take('room:1:7',false,true),false);
 assert.equal(r.take('room:1:7',true,false),false);
 assert.equal(r.take('room:1:7',true,true),true);
 assert.equal(r.take('room:1:7',true,true),false);
 assert.equal(r.take('room:1:8',true,true),true);
 assert.equal(r.take('room:2:7',true,true),true);
});
