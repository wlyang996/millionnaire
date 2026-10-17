const test=require('node:test');
const assert=require('node:assert/strict');
const {requestJson}=require('../temp/cloud-tests/net/Http.js');
const {GameClient}=require('../temp/cloud-tests/net/GameClient.js');
const {CloudSocket,cloudTarget}=require('../temp/cloud-tests/net/CloudTransport.js');
const {DEFAULT_SERVER,DEFAULT_WECHAT_CLOUD}=require('../temp/cloud-tests/net/Config.js');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};

async function fixture(run){
    const keys=['wx','fetch','XMLHttpRequest','WebSocket','__MN_WECHAT_CLOUD','__MN_SERVER'];
    const saved=keys.map(k=>[k,global[k]]),info=console.info,warn=console.warn,lines=[];
    for(const key of keys)delete global[key];
    console.info=console.warn=line=>lines.push(line);
    try{await run(lines);}finally{for(const [key,value]of saved){if(value===undefined)delete global[key];else global[key]=value;}console.info=info;console.warn=warn;}
}

function socketTask(){
    const events={},sent=[],closed=[];
    return {events,sent,closed,
        onOpen:fn=>events.open=fn,onMessage:fn=>events.message=fn,onError:fn=>events.error=fn,onClose:fn=>events.close=fn,
        send:options=>sent.push(options),close:options=>closed.push(options)};
}

test('concurrent backend calls share initialization and preserve route, auth, payload and server status',()=>fixture(async lines=>{
    const ready=deferred(),calls=[];let initialized=0,direct=0;
    global.__MN_WECHAT_CLOUD={env:'test-env',service:'test-service'};
    global.wx={login(){},request(){direct++;},cloud:{init(options){initialized++;assert.deepEqual(options,{env:'test-env',traceUser:false});return ready.promise;},
        async callContainer(options){calls.push(options);return {statusCode:options.method==='POST'?401:200,data:options.method==='POST'?' {"code":"WECHAT_LOGIN_FAILED","wxErrcode":40029} ':{items:[]}};}}};
    const post=requestJson('POST',DEFAULT_SERVER+'/api/auth/wx-login',{code:'PRIVATE_CODE'},'PRIVATE_TOKEN');
    const get=requestJson('GET',DEFAULT_SERVER+'/api/boards?version=1');
    await tick();assert.equal(initialized,1);assert.equal(calls.length,0);ready.resolve();
    assert.equal((await post).status,401);assert.deepEqual((await get).body,{items:[]});
    assert.equal(calls[0].path,'/api/auth/wx-login');assert.deepEqual(JSON.parse(calls[0].data),{code:'PRIVATE_CODE'});
    assert.equal(calls[0].header.Authorization,'Bearer PRIVATE_TOKEN');assert.equal(calls[0].header['X-WX-SERVICE'],'test-service');
    assert.match(calls[0].header['X-MN-Trace-Id'],/^http-/);assert.equal(calls[1].path,'/api/boards?version=1');
    assert.deepEqual(calls[0].config,{env:'test-env'});assert.equal(direct,0);
    assert(lines.some(l=>l.includes('wx.cloud.callContainer')&&l.includes('40029')));
    assert(!lines.join('').includes('PRIVATE_CODE')&&!lines.join('').includes('PRIVATE_TOKEN'));
}));

test('missing cloud capability fails clearly and never falls back to the blocked public domain',()=>fixture(async()=>{
    let direct=0;global.wx={login(){},request(){direct++;}};
    await assert.rejects(requestJson('GET',DEFAULT_SERVER+'/api/auth/methods'),e=>e.code==='WX_CLOUD_UNAVAILABLE'&&e.errMsg.includes('callContainer'));
    assert.equal(direct,0);
}));

test('failed initialization can be retried without falling back or logging credentials',()=>fixture(async lines=>{
    let attempts=0;
    global.wx={login(){},cloud:{init(){if(++attempts===1)throw {errMsg:'init:fail token=PRIVATE_INIT_TOKEN'};},
        async callContainer(){return {statusCode:200,data:'{"ok":true}'};}}};
    await assert.rejects(requestJson('GET',DEFAULT_SERVER+'/api/auth/methods'),e=>e.code==='WX_CLOUD_INIT_FAILED');
    assert.equal((await requestJson('GET',DEFAULT_SERVER+'/api/auth/methods')).body.ok,true);
    assert.equal(attempts,2);assert(!lines.join('').includes('PRIVATE_INIT_TOKEN'));
}));

test('container invocation failures retain platform diagnostics and do not invoke wx.request',()=>fixture(async()=>{
    global.wx={login(){},request(){assert.fail('unexpected public fallback');},cloud:{init(){},async callContainer(){throw {errMsg:'callContainer:fail environment not found'};}}};
    await assert.rejects(requestJson('GET',DEFAULT_SERVER+'/api/boards'),e=>e.code==='WX_CLOUD_REQUEST_FAILED'&&e.errMsg.includes('environment not found'));
}));

test('browser HTTP and WebSocket retain direct transport even when a cloud-like object exists',()=>fixture(async()=>{
    let fetched=0,opened=0;
    global.wx={cloud:{init(){assert.fail('browser initialized cloud');}}};
    global.fetch=async()=>{fetched++;return {status:200,text:async()=>'[]'};};
    global.WebSocket=class{constructor(url){opened++;assert(url.startsWith('wss://'));}close(){}};
    assert.deepEqual((await requestJson('GET',DEFAULT_SERVER+'/api/boards')).body,[]);
    const client=new GameClient(DEFAULT_SERVER);client.token='token';client.connect();client.close();
    assert.equal(fetched,1);assert.equal(opened,1);
}));

test('third-party resource URLs are not misrouted to the game backend',()=>fixture(async()=>{
    global.wx={login(){},request(options){assert.equal(options.url,'https://assets.example/file');options.success({statusCode:200,data:'{}'});},
        cloud:{init(){assert.fail('third-party resource initialized cloud');}}};
    assert.equal(cloudTarget('https://assets.example/file'),null);
    assert.deepEqual((await requestJson('GET','https://assets.example/file')).body,{});
}));

test('container socket preserves HELLO, command results, auth query and diagnostic IDs',()=>fixture(async lines=>{
    const task=socketTask();let options;
    global.wx={login(){},cloud:{init(){},async connectContainer(value){options=value;return {socketTask:task};}}};
    const client=new GameClient(DEFAULT_SERVER);client.token='PRIVATE_SOCKET_TOKEN';
    let hello=false;client.onNotice=message=>hello=message.type==='HELLO';
    try{
        client.connect();assert.equal(client.state,'connecting');await tick();
        assert.equal(options.service,DEFAULT_WECHAT_CLOUD.service);assert.deepEqual(options.config,{env:DEFAULT_WECHAT_CLOUD.env});
        assert(options.path.startsWith('/ws?token=PRIVATE_SOCKET_TOKEN&traceId=ws-'));
        task.events.open({});assert.equal(client.state,'open');
        task.events.message({data:'{"type":"HELLO","roomCode":null}'});assert.equal(hello,true);
        const result=client.request('READY',{ready:true}),message=JSON.parse(task.sent[0].data);
        task.events.message({data:JSON.stringify({type:'RESULT',requestId:message.requestId,ok:true,outcome:'ACCEPTED'})});
        assert.equal((await result).ok,true);assert.equal(client.token,'PRIVATE_SOCKET_TOKEN');
        assert(!lines.join('').includes('PRIVATE_SOCKET_TOKEN'));
    }finally{client.close();}assert.equal(task.closed.length,1);
}));

test('socket creation failure enters the existing reconnect state without public WebSocket fallback',()=>fixture(async()=>{
    global.WebSocket=class{constructor(){assert.fail('public WebSocket fallback');}};
    global.wx={login(){},cloud:{init(){},async connectContainer(){throw {errMsg:'connectContainer:fail no permission'};}}};
    const client=new GameClient(DEFAULT_SERVER);client.token='token';
    try{client.connect();await tick();assert.equal(client.state,'reconnecting');}finally{client.close();}
}));

test('closing while container creation is pending closes the late socket and never reopens',()=>fixture(async()=>{
    const ready=deferred(),task=socketTask();let started=false,opened=0,closed=0;
    global.wx={login(){},cloud:{init(){},connectContainer(){started=true;return ready.promise;}}};
    const socket=new CloudSocket(cloudTarget(DEFAULT_SERVER+'/ws'));
    socket.onopen=()=>opened++;socket.onclose=()=>closed++;
    await tick();assert.equal(started,true);socket.close();ready.resolve({socketTask:task});await tick();
    assert.equal(socket.readyState,3);assert.equal(opened,0);assert.equal(closed,1);assert.equal(task.closed.length,1);
}));

test('native socket errors close once and allow pending commands to be replayed on reconnect',()=>fixture(async()=>{
    const tasks=[];
    global.wx={login(){},cloud:{init(){},async connectContainer(){const task=socketTask();tasks.push(task);return {socketTask:task};}}};
    const client=new GameClient(DEFAULT_SERVER);client.token='token';
    try{
        client.connect();await tick();tasks[0].events.open({});
        const result=client.request('READY',{ready:true});const original=JSON.parse(tasks[0].sent[0].data);
        tasks[0].events.error({errMsg:'socket interrupted'});tasks[0].events.close({code:1006});
        assert.equal(client.state,'reconnecting');assert.equal(tasks[0].closed.length,1);
        await new Promise(resolve=>setTimeout(resolve,1100));assert.equal(tasks.length,2);tasks[1].events.open({});
        const resent=JSON.parse(tasks[1].sent[0].data);assert.equal(resent.requestId,original.requestId);
        tasks[1].events.message({data:JSON.stringify({type:'RESULT',requestId:resent.requestId,ok:true,outcome:'ACCEPTED'})});
        assert.equal((await result).ok,true);
    }finally{client.close();}
}));
