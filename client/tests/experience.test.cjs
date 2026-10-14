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
const {ReturnChanges}=require('../temp/features-selfcheck/core/ReturnChanges.js');
test('return summary isolates rooms, ignores first join, and consumes each absence once',()=>{
 const s=new ReturnChanges(),change=id=>({id,gameNo:1,kind:'RentPaid',data:{}});
 s.update('a:1',[change(1)],1);assert.equal(s.take(),null);
 s.leave();s.update('a:1',[change(1),change(2)],1);assert.equal(s.suppressAnimations,true);
 s.resume();s.update('a:1',[change(2),change(3)],1);
 assert.deepEqual(s.take().changes.map(c=>c.id),[2,3]);assert.equal(s.take(),null);
 s.leave();s.resume();s.update('a:1',[change(7),change(8)],1);assert.equal(s.take().truncated,true);
 s.leave();s.resume();s.update('b:1',[change(50)],1);assert.equal(s.take(),null);
});
const {EmoteState}=require('../temp/features-selfcheck/core/Emotes.js');
test('emotes reject unknown, expired and duplicate messages',()=>{
 const s=new EmoteState(),v={playerId:'1',id:'CHEER',sequence:4,endsAt:3000};
 assert.equal(s.receive({...v,id:'unknown'},1000),false);assert.equal(s.receive(v,3000),false);
 assert.equal(s.receive(v,1000),true);assert.equal(s.receive(v,1000),false);
 assert.equal(s.get('1',2999).id,'CHEER');assert.equal(s.get('1',3000),undefined);
 s.clear();assert.equal(s.get('1',1000),undefined);
});
const {Practice}=require('../temp/features-selfcheck/core/Practice.js');
test('practice completes interactive steps independently and can restart without history',()=>{
 const profile={nickname:'可可',avatar:1};const p=new Practice(profile,300);const start=p.me.cash;
 assert.equal(p.step,'ROLL');p.advance();assert.equal(p.step,'BUY');p.advance();
 assert.equal(p.property.owner,p.me.playerId);assert.equal(p.me.cash,start-p.price);
 const rented=p.rent;p.advance();assert.equal(p.me.cash,start-p.price+rented);
 p.advance();assert.equal(p.me.cash,start-p.price+rented+300);assert.equal(p.step,'EVENT_RESULT');
 p.advance();assert.equal(p.game.myHand.length,1);
 p.advance();assert.equal(p.property.level,1);assert.equal(p.game.myHand.length,0);assert.equal(p.step,'DONE');assert.equal(p.advance(),false);
 const again=new Practice(profile,300);assert.equal(again.step,'ROLL');assert.equal(again.property.owner,null);
 assert.equal(profile.avatar,1);assert.equal(p.game.gameNo,0);assert.equal(p.game.windows.length,0);
});
