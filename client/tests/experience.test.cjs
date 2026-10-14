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
const {Practice}=require('../temp/features-selfcheck/core/Practice.js');
test('practice completes interactive steps independently and can restart without history',()=>{
 const profile={nickname:'可可',avatar:1};const p=new Practice(profile,300);const start=p.me.cash;
 assert.equal(p.step,'ROLL');p.advance();assert.equal(p.step,'BUY');p.advance();
 assert.equal(p.property.owner,p.me.playerId);assert.equal(p.me.cash,start-p.price);
 const rented=p.rent;p.advance();assert.equal(p.me.cash,start-p.price+rented);
 p.advance();assert.equal(p.me.cash,start-p.price+rented+300);assert.equal(p.game.myHand.length,1);
 p.advance();assert.equal(p.property.level,1);assert.equal(p.game.myHand.length,0);assert.equal(p.step,'DONE');assert.equal(p.advance(),false);
 const again=new Practice(profile,300);assert.equal(again.step,'ROLL');assert.equal(again.property.owner,null);
 assert.equal(profile.avatar,1);assert.equal(p.game.gameNo,0);assert.equal(p.game.windows.length,0);
});
