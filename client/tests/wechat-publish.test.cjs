const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const {prepare} = require('../tools/prepare-wechat-publish.cjs');
const {ORIGIN,installFs,installPipeline} = require('../tools/wechat-cloud-assets.cjs');
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(),'mn-publish-'));
    t.after(() => fs.rmSync(root,{recursive:true,force:true}));
    const source = path.join(root,'source');
    fs.mkdirSync(path.join(source,'assets/resources/native/ab'),{recursive:true});
    fs.mkdirSync(path.join(source,'src'));
    fs.writeFileSync(path.join(source,'game.js'),"require('./web-adapter');\nrequire('./engine-adapter');");
    fs.writeFileSync(path.join(source,'src/settings.json'),'{"CocosEngine":"3.8.8"}');
    fs.writeFileSync(path.join(source,'assets/resources/config.json'),'{"nativeBase":"native"}');
    fs.writeFileSync(path.join(source,'assets/resources/native/ab/abcdef.png'),Buffer.from('image fixture'));
    return {source,output:path.join(root,'publish'),staging:path.join(root,'staging'),env:'test-env',bucket:'test-bucket-123'};
}

test('publish keeps source intact and local indices, separates images with immutable content IDs', t => {
    const options = fixture(t), report = prepare(options);
    assert.equal(report.fileCount,1);
    assert(fs.existsSync(path.join(options.source,'assets/resources/native/ab/abcdef.png')));
    assert(!fs.existsSync(path.join(options.output,'assets/resources/native')));
    assert(fs.existsSync(path.join(options.output,'assets/resources/config.json')));
    const uploaded = path.join(report.uploadRoot,report.prefix,'ab/abcdef.png');
    assert.deepEqual(fs.readFileSync(uploaded),Buffer.from('image fixture'));
    const entry = fs.readFileSync(path.join(options.output,'game.js'),'utf8');
    assert(entry.indexOf('installFs') < entry.indexOf("require('./engine-adapter')"));
    assert(entry.indexOf('installPipeline') > entry.indexOf("require('./engine-adapter')"));
    // WeChat's require resolves JS modules, not Node's JSON module loader.
    const configFile = path.join(options.output,'mn-cloud-assets.config.js');
    const module = {exports:{}};
    vm.runInNewContext(fs.readFileSync(configFile,'utf8'),{module});
    assert.equal(module.exports.env,options.env);
    assert.equal(module.exports.fileRoot,'cloud://'+options.env+'.'+options.bucket+'/'+report.prefix);
    assert(!/require\([^)]*\.json['"]\)/.test(entry));
    let installed;
    const startup = entry.split("require('./engine-adapter');")[0];
    vm.runInNewContext(startup,{window:{fsUtils:{}},wx:{},require: id => {
        if (id === './web-adapter') return {};
        if (id === './mn-cloud-assets') return {installFs: (fsUtils,wx,config) => {installed=config;}};
        const filename=path.join(options.output,id+'.js');
        const loaded={exports:{}};
        vm.runInNewContext(fs.readFileSync(filename,'utf8'),{module:loaded});
        return loaded.exports;
    }});
    assert.equal(installed.fileRoot,module.exports.fileRoot);
    assert.match(report.sampleFileID,/^cloud:\/\/test-env\.test-bucket-123\/game-assets\/[a-f0-9]{64}\/native\//);
    assert.throws(() => prepare(options),/输出目录已存在/);
});

test('oversized code, changed startup templates and nested outputs fail before writing', t => {
    const options = fixture(t);
    assert.throws(() => prepare({...options,output:path.join(options.source,'nested')}),/互相独立/);
    fs.writeFileSync(path.join(options.source,'extra.js'),Buffer.alloc(4*1024*1024));
    assert.throws(() => prepare(options),/仍超/);
    assert(!fs.existsSync(options.output));
    fs.unlinkSync(path.join(options.source,'extra.js'));
    fs.writeFileSync(path.join(options.source,'game.js'),'changed template');
    assert.throws(() => prepare(options),/启动模板已变化/);
    assert(!fs.existsSync(options.output));
});

test('pipeline only rewrites native resources images, leaving scripts, indices and unrelated URLs alone', () => {
    let pipe;
    installPipeline({transformPipeline:{append: fn => {pipe=fn;}}});
    const items = [
        {isNative:true,url:'assets/resources/native/ab/abcdef.png'},
        {isNative:false,url:'assets/resources/import/ab/abcdef.json'},
        {isNative:true,url:'assets/internal/native/ab/abcdef.png'},
        {isNative:true,url:'https://other.example/a.png'}
    ];
    pipe({output:items});
    assert.equal(items[0].url,ORIGIN+'ab/abcdef.png');
    assert.equal(items[1].url,'assets/resources/import/ab/abcdef.json');
    assert.equal(items[2].url,'assets/internal/native/ab/abcdef.png');
    assert.equal(items[3].url,'https://other.example/a.png');
});

test('private images download through cloud storage, shared init, progress and Cocos cache paths', async () => {
    let initialized=0, progress=false, delegated=0;
    const cloud = {init: async options => {initialized++; assert.equal(options.env,'env');}, downloadFile: options => {
        assert.equal(options.fileID,'cloud://env.bucket/game-assets/version/native/ab/abcdef.png');
        queueMicrotask(() => options.success({tempFilePath:'wxfile://temp-image'}));
        return {onProgressUpdate: fn => {progress=true;fn({progress:100});}};
    }};
    const fsUtils = {downloadFile: () => {delegated++;}};
    installFs(fsUtils,{cloud},{env:'env',fileRoot:'cloud://env.bucket/game-assets/version/native'});
    const download = () => new Promise((resolve,reject) => fsUtils.downloadFile(ORIGIN+'ab/abcdef.png',null,null,()=>{},(error,value)=>error?reject(error):resolve(value)));
    assert.deepEqual(await Promise.all([download(),download()]),['wxfile://temp-image','wxfile://temp-image']);
    assert.equal(initialized,1);assert(progress);
    fsUtils.downloadFile('https://other.example/a.png');assert.equal(delegated,1);
});

test('permission errors never fall back to public downloads and missing SDK remains actionable', async () => {
    let direct=0;
    const fsUtils = {downloadFile: () => {direct++;}};
    installFs(fsUtils,{cloud:{init(){},downloadFile:options => queueMicrotask(()=>options.fail({errCode:-1,errMsg:'permission denied'}))}},
        {env:'env',fileRoot:'cloud://env.bucket/assets'});
    const original=console.warn;console.warn=()=>{};
    try {
        await assert.rejects(new Promise((resolve,reject) => fsUtils.downloadFile(ORIGIN+'ab/abcdef.png',null,null,null,error=>error?reject(error):resolve())),/permission denied/);
        assert.equal(direct,0);
    } finally {console.warn=original;}
    const missing={downloadFile(){throw new Error('must not use public network');}};
    installFs(missing,{},{});
    await assert.rejects(new Promise((resolve,reject)=>missing.downloadFile(ORIGIN+'ab/abcdef.png',null,null,null,error=>reject(error))),/能力不可用/);
});

test('cancellation while cloud initialization is pending never starts a download', async () => {
    let resolve, count=0, callbacks=0;
    const fsUtils={downloadFile(){}};
    installFs(fsUtils,{cloud:{init:()=>new Promise(r=>{resolve=r;}),downloadFile:()=>{count++;}}},{env:'env',fileRoot:'root'});
    const task=fsUtils.downloadFile(ORIGIN+'ab/abcdef.png',null,null,null,()=>{callbacks++;});
    await tick();task.abort();resolve();await tick();
    assert.equal(count,0);assert.equal(callbacks,1);
});
