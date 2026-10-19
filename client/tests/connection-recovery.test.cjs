const test=require('node:test');
const assert=require('node:assert/strict');
const {GameClient}=require('../temp/network-tests/net/GameClient.js');

async function fixture(t,run){
    t.mock.timers.enable({apis:['setTimeout','setInterval']});
    let now=1000;
    t.mock.method(Date,'now',()=>now);
    const lines=[],sockets=[];
    t.mock.method(console,'info',line=>lines.push(line));
    t.mock.method(console,'warn',line=>lines.push(line));
    const client=new GameClient('https://game.example',()=>{
        const socket={readyState:1,sent:[],closed:[],send(data){this.sent.push(JSON.parse(data));},
            close(code,reason){this.closed.push({code,reason});},onopen:null,onclose:null,onerror:null,onmessage:null};
        sockets.push(socket);return socket;
    });
    client.token='PRIVATE_TOKEN';client.connect();sockets[0].onopen();
    sockets[0].onmessage({data:'{"type":"HELLO","roomCode":null}'});
    try{await run({client,sockets,lines,advance(ms){now+=ms;t.mock.timers.tick(ms);},jump(ms){now+=ms;},tick(ms){t.mock.timers.tick(ms);}});}
    finally{client.close();}
}

test('joining after a silent mobile suspension reconnects and sends the queued join once',t=>fixture(t,async f=>{
    f.jump(16000); // Mobile timers were suspended and no close event arrived.
    const result=f.client.joinRoom('211250');
    assert.equal(f.client.state,'reconnecting');
    assert.equal(f.sockets[0].sent.filter(m=>m.type==='JOIN_ROOM').length,0);
    f.tick(1000);assert.equal(f.sockets.length,2);f.sockets[1].onopen();
    const join=f.sockets[1].sent.find(m=>m.type==='JOIN_ROOM');assert.equal(join.roomCode,'211250');
    f.sockets[1].onmessage({data:JSON.stringify({type:'RESULT',requestId:join.requestId,ok:true,outcome:'ACCEPTED',roomCode:'211250'})});
    assert.equal((await result).ok,true);
    assert(f.lines.some(l=>l.includes('ws.heartbeat.timeout')));
    assert(!f.lines.join('').includes('PRIVATE_TOKEN'));
}));

test('missed heartbeat replies replay an outstanding join with its original request ID',t=>fixture(t,async f=>{
    f.advance(6000);const result=f.client.joinRoom('211250');
    const original=f.sockets[0].sent.find(m=>m.type==='JOIN_ROOM');
    f.advance(10000);assert.equal(f.client.state,'reconnecting');
    f.tick(1000);f.sockets[1].onopen();
    const replay=f.sockets[1].sent.find(m=>m.type==='JOIN_ROOM');assert.equal(replay.requestId,original.requestId);
    // A late close or reply from the old connection must not disturb the new one.
    f.sockets[0].onclose({code:1006});assert.equal(f.client.state,'open');
    f.sockets[1].onmessage({data:JSON.stringify({type:'RESULT',requestId:replay.requestId,ok:true,outcome:'ACCEPTED'})});
    assert.equal((await result).ok,true);
}));

test('live heartbeat replies keep the connection open and an unanswered join has useful timeout diagnostics',t=>fixture(t,async f=>{
    const result=f.client.joinRoom('211250');
    for(let i=0;i<3;i++){
        f.sockets[0].onmessage({data:'{"type":"PONG"}'});f.advance(5000);
    }
    assert.equal((await result).code,'TIMEOUT');assert.equal(f.client.state,'open');assert.equal(f.sockets.length,1);
    const timeout=f.lines.map(l=>JSON.parse(l.slice('[MN-NET] '.length))).find(l=>l.event==='ws.request.timeout');
    assert.equal(timeout.command,'JOIN_ROOM');assert.equal(timeout.sentCount,1);assert.equal(timeout.linkState,'open');
    assert.equal(timeout.elapsedMs,15000);assert(!f.lines.join('').includes('PRIVATE_TOKEN'));
}));

test('a synchronous mobile send failure reconnects without losing or duplicating the request',t=>fixture(t,async f=>{
    let original;
    f.sockets[0].send=data=>{original=JSON.parse(data);throw new Error('native socket is closed');};
    const result=f.client.joinRoom('211250');assert.equal(f.client.state,'reconnecting');
    f.tick(1000);f.sockets[1].onopen();
    const replay=f.sockets[1].sent.find(m=>m.type==='JOIN_ROOM');assert.equal(replay.requestId,original.requestId);
    f.sockets[1].onmessage({data:JSON.stringify({type:'RESULT',requestId:replay.requestId,ok:true,outcome:'ACCEPTED'})});
    assert.equal((await result).ok,true);assert(f.lines.some(l=>l.includes('ws.send.fail')));
}));

test('returning to the foreground repairs a silent connection even before a room command',t=>fixture(t,async f=>{
    f.jump(20000);f.client.checkConnection();assert.equal(f.client.state,'reconnecting');
    assert.equal(f.sockets[0].closed.length,1);
    f.client.checkConnection();f.tick(1000);assert.equal(f.sockets.length,2);
    f.sockets[1].onopen();f.sockets[1].onmessage({data:'{"type":"HELLO","roomCode":null}'});
    f.advance(5000);assert.equal(f.client.state,'open');assert.equal(f.sockets[1].sent[0].type,'PING');
}));
