'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const LIMIT = 4 * 1024 * 1024;

function files(root) {
    return fs.readdirSync(root, {withFileTypes: true}).flatMap(e => {
        const name = path.join(root, e.name);
        if (e.isSymbolicLink()) throw new Error('Symbolic links are not supported: ' + name);
        return e.isDirectory() ? files(name) : [name];
    }).sort();
}
function prepare({source, output, staging, env, bucket}) {
    source = path.resolve(source); output = path.resolve(output); staging = path.resolve(staging);
    if (!/^[a-z0-9-]+$/.test(env) || !/^[a-z0-9-]+-\d+$/.test(bucket)) throw new Error('需要完整且有效的环境 ID 和存储桶名称');
    const contains = (a,b) => b === a || b.startsWith(a + path.sep);
    if (contains(source,output) || contains(output,source) || contains(source,staging) || contains(staging,source)
        || contains(output,staging) || contains(staging,output)) throw new Error('构建源、发布包和资源暂存目录必须互相独立');
    if (fs.existsSync(output) || fs.existsSync(staging)) throw new Error('输出目录已存在，请选择新目录；工具不会覆盖已有发布包');
    const entry = fs.readFileSync(path.join(source,'game.js'),'utf8');
    const before = "require('./web-adapter');";
    const after = "require('./engine-adapter');";
    if (entry.split(before).length !== 2 || entry.split(after).length !== 2) throw new Error('Cocos 启动模板已变化，请先检查适配位置');
    const settings = JSON.parse(fs.readFileSync(path.join(source,'src/settings.json'),'utf8'));
    if (settings.CocosEngine !== '3.8.8') throw new Error('仅验证过 Cocos Creator 3.8.8');
    const native = path.join(source,'assets/resources/native');
    const nativeFiles = files(native);
    if (!nativeFiles.length || nativeFiles.some(f => !/\.png$/.test(f))) throw new Error('当前拆包只支持已验证的 PNG 原生资源');
    const hash = crypto.createHash('sha256');
    const manifest = nativeFiles.map(file => {
        const relative = path.relative(native,file).split(path.sep).join('/');
        if (!/^[a-f0-9]{2}\/[a-f0-9-]+(?:\.[a-f0-9]+)?\.png$/.test(relative)) throw new Error('不支持的资源文件名：' + relative);
        const data = fs.readFileSync(file), sha256 = crypto.createHash('sha256').update(data).digest('hex');
        hash.update(relative + '\0' + sha256 + '\n');
        return {path: relative, bytes: data.length, sha256};
    });
    const version = hash.digest('hex');
    const prefix = 'game-assets/' + version + '/native';
    const assetBytes = manifest.reduce((n,f) => n + f.bytes,0);
    const sourceBytes = files(source).reduce((n,f) => n + fs.statSync(f).size,0);
    // Refuse oversized builds before creating any output. Leave headroom for the small adapter/config.
    if (sourceBytes - assetBytes + 16384 > LIMIT) throw new Error('拆出图片后主包仍超 4 MiB，需要进一步分包或裁剪引擎');
    fs.mkdirSync(staging,{recursive:true});
    fs.cpSync(source,output,{recursive:true,filter: name => name !== native && !contains(native,name)});
    const upload = path.join(staging,'upload',prefix);
    fs.mkdirSync(upload,{recursive:true});
    fs.cpSync(native,upload,{recursive:true});
    const config = {env, version, fileRoot: 'cloud://' + env + '.' + bucket + '/' + prefix};
    fs.writeFileSync(path.join(output,'mn-cloud-assets.config.json'),JSON.stringify(config,null,2));
    fs.copyFileSync(path.join(__dirname,'wechat-cloud-assets.cjs'),path.join(output,'mn-cloud-assets.js'));
    const patched = entry.replace(before,before + "\nrequire('./mn-cloud-assets').installFs(window.fsUtils, wx, require('./mn-cloud-assets.config.json'));")
        .replace(after,after + "\nrequire('./mn-cloud-assets').installPipeline(cc.assetManager);");
    fs.writeFileSync(path.join(output,'game.js'),patched);
    const mainBytes = files(output).reduce((n,f) => n + fs.statSync(f).size,0);
    if (mainBytes > LIMIT) throw new Error('发布包仍超过 4 MiB，请勿上传');
    const report = {version, env, bucket, mainBytes, assetBytes, fileCount:manifest.length, prefix,
        sampleFileID:config.fileRoot+'/'+manifest[0].path, output, uploadRoot:path.join(staging,'upload'), files:manifest};
    fs.writeFileSync(path.join(staging,'manifest.json'),JSON.stringify(report,null,2));
    return report;
}

if (require.main === module) {
    try {
        const args = process.argv.slice(2), options = {};
        for (let i=0;i<args.length;i+=2) {
            if (!['--source','--output','--staging','--env','--bucket'].includes(args[i]) || !args[i+1]) throw new Error('参数：--env 环境ID --bucket 完整桶名 [--source 构建目录 --output 发布目录 --staging 上传目录]');
            options[args[i].slice(2)] = args[i+1];
        }
        const client = path.resolve(__dirname,'..');
        const source = path.resolve(options.source || path.join(client,'build/wechatgame'));
        const buildTime = fs.statSync(path.join(source,'game.js')).mtimeMs;
        const changed = files(path.join(client,'assets')).filter(f => /\.(ts|png|scene)$/.test(f) && fs.statSync(f).mtimeMs > buildTime + 2000);
        if (changed.length) throw new Error('构建早于源码或素材，请先在 Cocos 重新构建：' + changed.slice(0,3).join(', '));
        const stamp = new Date().toISOString().replace(/[^0-9]/g,'');
        const report = prepare({...options,source,output:options.output || path.join(client,'build/wechatgame-publish-'+stamp),
            staging:options.staging || path.join(client,'temp/wechat-assets-'+stamp)});
        console.log(JSON.stringify({...report,files:undefined},null,2));
    } catch(error) { console.error(error.message); process.exitCode=1; }
}
module.exports = {prepare,files};
