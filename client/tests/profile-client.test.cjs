const test=require('node:test');
const assert=require('node:assert/strict');
const {GameClient}=require('../temp/profile-tests/net/GameClient.js');

test('profile editing reuses the existing identity and token and only commits successful responses',async()=>{
    const previous=global.fetch,requests=[];
    let rejected=false;
    global.fetch=async(url,options)=>{
        requests.push({url,options});
        const data=rejected?{code:'CONTENT_REJECTED'}:{nickname:'新昵称',avatar:1,appliesNextRoom:true};
        return {status:rejected?400:200,text:async()=>JSON.stringify(data)};
    };
    try{
        const client=new GameClient('https://game.example');
        client.token='existing-token';client.userId='player-1';client.nickname='旧昵称';client.avatar=0;
        assert.deepEqual(await client.updateProfile('新昵称',1),{nickname:'新昵称',avatar:1,appliesNextRoom:true});
        assert.equal(client.userId,'player-1');assert.equal(client.token,'existing-token');
        assert.equal(client.nickname,'新昵称');assert.equal(client.avatar,1);
        assert.equal(requests[0].url,'https://game.example/api/me/profile');
        assert.equal(requests[0].options.headers.Authorization,'Bearer existing-token');
        assert.deepEqual(JSON.parse(requests[0].options.body),{nickname:'新昵称',avatar:1});
        rejected=true;
        await assert.rejects(client.updateProfile('拒绝昵称',2),e=>e.code==='CONTENT_REJECTED');
        assert.equal(client.nickname,'新昵称');assert.equal(client.avatar,1);
        assert.equal(requests.some(r=>r.url.includes('/auth/')),false);
    }finally{global.fetch=previous;}
});
test('returning WeChat users keep the server nickname and avatar when logging in without new profile data',async()=>{
    const previous=global.fetch;
    global.fetch=async(url,options)=>{
        assert.deepEqual(JSON.parse(options.body),{code:'one-time-code'});
        return {status:200,text:async()=>JSON.stringify({token:'token',userId:'player-1',nickname:'已保存昵称',avatar:5})};
    };
    try{
        const client=new GameClient('https://game.example');
        assert.deepEqual(await client.wxLogin('one-time-code'),{nickname:'已保存昵称',avatar:5});
        assert.equal(client.avatar,5);assert.equal(client.nickname,'已保存昵称');
    }finally{global.fetch=previous;}
});
