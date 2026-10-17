const test = require('node:test');
const assert = require('node:assert/strict');
const { requestJson } = require('../temp/network-tests/net/Http.js');
const { wxLoginCode } = require('../temp/network-tests/net/Wx.js');
const { GameClient } = require('../temp/network-tests/net/GameClient.js');
const { safeDiagnosticText, diagnosticError } = require('../temp/network-tests/net/Diagnostics.js');

test('cloud platform subcodes remain visible while login credentials stay redacted', () => {
    for(const subcode of ['[-501000]','[INVALID_ENV]','[ResourceEnvNotFound]','-501000']){
        const error={errCode:-501000,errno:85088,errMsg:'cloud.callContainer:fail 85088 . cloud.callContainer:fail system error. code='+subcode+' js_code=PRIVATE_CODE token=PRIVATE_TOKEN'};
        const safe=safeDiagnosticText(error.errMsg);
        assert(safe.includes('code='+subcode));assert(!safe.includes('PRIVATE_CODE')&&!safe.includes('PRIVATE_TOKEN'));
        const details=diagnosticError(error);
        assert.equal(details.platformCode,subcode);assert.equal(details.errCode,-501000);assert.equal(details.errno,85088);
        assert.equal(safeDiagnosticText(safe),safe);
    }
    assert(!safeDiagnosticText('code=123456 js_code=PRIVATE_CODE').includes('123456'));
    assert(!safeDiagnosticText('cloud.callContainer:fail code=PRIVATE_LOGIN_CODE').includes('PRIVATE_LOGIN_CODE'));
});

test('free-form platform error text redacts credential URLs, headers and quoted JSON', () => {
    const output=safeDiagnosticText('request:fail {"session_key":"PRIVATE_SESSION","secret":"PRIVATE_SECRET"} Authorization: Bearer PRIVATE_BEARER https://user:PRIVATE_PASSWORD@host/ws?token=PRIVATE_URL_TOKEN');
    assert(output.includes('request:fail') && output.includes('https://host/ws'));
    for(const value of ['PRIVATE_SESSION','PRIVATE_SECRET','PRIVATE_BEARER','PRIVATE_PASSWORD','PRIVATE_URL_TOKEN']) assert(!output.includes(value));
});

async function capture(run) {
    const saved = { wx: global.wx, fetch: global.fetch, xhr: global.XMLHttpRequest, info: console.info, warn: console.warn };
    const lines = [];
    console.info = console.warn = text => lines.push(text);
    delete global.XMLHttpRequest; delete global.wx;
    try { await run(lines); }
    finally { global.wx = saved.wx; global.fetch = saved.fetch; global.XMLHttpRequest = saved.xhr; console.info = saved.info; console.warn = saved.warn; }
}

test('wx.login logs success without code and preserves the original WeChat failure reason', () => capture(async lines => {
    global.wx = { login: ({success}) => success({code:'PRIVATE_LOGIN_CODE'}) };
    assert.equal(await wxLoginCode(), 'PRIVATE_LOGIN_CODE');
    global.wx.login = ({fail}) => fail({errMsg:'login:fail network timeout'});
    await assert.rejects(wxLoginCode(), e => e.code === 'WECHAT_LOGIN_FAILED' && e.errMsg.includes('network timeout'));
    assert(lines.some(l => l.includes('wx.login.end')));
    assert(lines.some(l => l.includes('wx.login.fail') && l.includes('network timeout')));
    assert(!lines.join('').includes('PRIVATE_LOGIN_CODE'));
}));

test('wx.request diagnostics correlate requests and retain domain-list failures without leaking data', () => capture(async lines => {
    let header;
    global.wx = { request: options => { header=options.header; options.fail({errMsg:'request:fail url not in domain list https://host/api?token=PRIVATE_TOKEN&js_code=PRIVATE_CODE'}); } };
    await assert.rejects(requestJson('POST','https://host/api/auth/wx-login?secret=PRIVATE_SECRET',
        {code:'PRIVATE_CODE',nickname:'PRIVATE_NICK'},'PRIVATE_TOKEN'));
    assert.match(header['X-MN-Trace-Id'], /^http-/);
    const output=lines.join('');
    assert(output.includes(header['X-MN-Trace-Id']));
    assert(output.includes('wx.request') && output.includes('url not in domain list'));
    for(const secret of ['PRIVATE_TOKEN','PRIVATE_CODE','PRIVATE_SECRET','PRIVATE_NICK']) assert(!output.includes(secret));
}));

test('fetch records status and server error code but never response credentials', () => capture(async lines => {
    global.fetch = async (url,options) => {
        assert.match(options.headers['X-MN-Trace-Id'],/^http-/);
        return {status:401,text:async()=>JSON.stringify({code:'WECHAT_LOGIN_FAILED',wxErrcode:40029,token:'PRIVATE_RESPONSE'})};
    };
    const response=await requestJson('POST','https://host/api/auth/wx-login',{code:'PRIVATE_REQUEST'});
    assert.equal(response.status,401);
    const output=lines.join('');
    assert(output.includes('40029') && output.includes('401') && output.includes('WECHAT_LOGIN_FAILED'));
    assert(!output.includes('PRIVATE_RESPONSE') && !output.includes('PRIVATE_REQUEST'));
}));

test('XHR timeouts log the transport and request trace', () => capture(async lines => {
    let trace;
    global.XMLHttpRequest=class {
        open(){} setRequestHeader(k,v){if(k==='X-MN-Trace-Id')trace=v;}
        send(){this.ontimeout();}
    };
    await assert.rejects(requestJson('GET','https://host/api/boards'), /timeout/);
    assert(lines.some(l=>l.includes('http.fail')&&l.includes('xhr')&&l.includes(trace)));
}));

test('WebSocket traces open, authentication, errors, close and retry without exposing its token', () => capture(async lines => {
    const socket={readyState:1,send(){},close(){},onopen:null,onmessage:null,onerror:null,onclose:null};
    let endpoint;
    const client=new GameClient('https://host',url=>{endpoint=url;return socket;});
    client.token='PRIVATE_SOCKET_TOKEN';
    try {
        client.connect();
        assert(endpoint.includes('traceId=ws-'));
        socket.onopen(); socket.onmessage({data:'{"type":"HELLO","roomCode":null}'});
        socket.onerror({errMsg:'connectSocket:fail url not in domain list '+endpoint});
        socket.onclose({code:1006,reason:'abnormal closure'});
        const output=lines.join('');
        for(const event of ['ws.open','ws.hello','ws.error','ws.close','ws.retry']) assert(output.includes(event));
        assert(output.includes('1006') && !output.includes('PRIVATE_SOCKET_TOKEN'));
    } finally { client.close(); }
}));
