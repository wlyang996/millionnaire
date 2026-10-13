const test=require('node:test');const assert=require('node:assert/strict');
const {defaultCharacterCatalog,validateCharacterCatalog,characterSlots,CHARACTER_SLOTS,CHARACTER_MOTION_SLOTS}=require('../temp/character-tests/CharacterCatalog.js');
const image=slot=>({url:'/api/characters/assets/11111111-1111-1111-1111-111111111111/'+slot+'.png',sha256:'a'.repeat(64),width:128,height:128});
const entry=()=>({id:9,name:'新人物',sort:9,enabled:true,fallback:1,bundled:false,images:Object.fromEntries(CHARACTER_SLOTS.map(s=>[s,image(s)]))});
test('static published catalogs stay compatible and complete movement adds exactly four slots',()=>{
 assert.equal(validateCharacterCatalog(defaultCharacterCatalog()),true);const e=entry();assert.equal(validateCharacterCatalog({version:1,characters:[e]}),true);assert.equal(characterSlots(e).length,7);
 e.images.pawncrouch=image('pawncrouch');assert.equal(validateCharacterCatalog({version:2,characters:[e]}),false);
 for(const s of CHARACTER_MOTION_SLOTS)e.images[s]=image(s);assert.equal(validateCharacterCatalog({version:2,characters:[e]}),true);assert.equal(characterSlots(e).length,11);
});
test('wrong canvas and arbitrary assets are rejected rather than mixed',()=>{
 const e=entry();for(const s of CHARACTER_MOTION_SLOTS)e.images[s]=image(s);e.images.pawnready.width=129;assert.equal(validateCharacterCatalog({version:1,characters:[e]}),false);
 e.images.pawnready.width=128;e.images.pawnready.url='https://other.example/frame.png';assert.equal(validateCharacterCatalog({version:1,characters:[e]}),false);
 e.images.pawnready=image('pawnready');e.images.extra=image('extra');assert.equal(validateCharacterCatalog({version:1,characters:[e]}),false);
});
