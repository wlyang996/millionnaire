const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {ART_PATHS}=require('../temp/performance-tests/ui/ArtCatalog.js');
const tick=()=>new Promise(resolve=>setImmediate(resolve));

function loader(){
    const active=[],started=[];
    const exports={};
    vm.runInNewContext(fs.readFileSync(require.resolve('../temp/performance-tests/ui/Art.js'),'utf8'),{
        exports,console,setTimeout:()=>1,clearTimeout(){},
        require:name=>{
            if(name==='cc')return {resources:{load(path,type,callback){const request={path,callback};active.push(request);started.push(path);}}};
            if(name==='./ArtCatalog')return {ART_PATHS};
            if(name==='../core/CharacterCatalog')return {defaultCharacterCatalog:()=>({characters:[]})};
            return {};
        }
    });
    const finish=async()=>{
        const request=active.shift();
        request.callback(null,{originalSize:{width:100,height:100}});
        await tick();
    };
    return {api:exports,active,started,finish};
}

test('startup warms a small set rather than decoding the complete art catalog',async()=>{
    const l=loader();l.api.startBackgroundArt(()=>{});
    while(l.active.length)await l.finish();
    assert(l.started.length<=16);
    assert(l.started.includes(ART_PATHS.dice_1+'/spriteFrame'));
    assert(l.started.includes(ART_PATHS.board_town+'/spriteFrame'));
    assert(!l.started.includes(ART_PATHS.scene_jail_closeup+'/spriteFrame'));
});

test('visible assets take priority without exceeding the shared download limit',async()=>{
    const l=loader();l.api.startBackgroundArt(()=>{});
    assert.equal(l.active.length,4);
    for(let i=0;i<20;i++)l.api.artRatio('scene_jail_closeup');
    assert.equal(l.active.length,4);
    await l.finish();
    assert.equal(l.started[4],ART_PATHS.scene_jail_closeup+'/spriteFrame');
    while(l.active.length){assert(l.active.length<=4);await l.finish();}
    assert.equal(l.started.filter(p=>p===ART_PATHS.scene_jail_closeup+'/spriteFrame').length,1);
    assert.equal(l.api.artRatio('scene_jail_closeup'),1);
});
