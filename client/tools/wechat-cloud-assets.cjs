'use strict';

// Installed before Cocos' WeChat adapter captures fsUtils.downloadFile.
// Only our virtual asset origin is intercepted; it never goes to the public network.
const ORIGIN = 'https://mn-game-assets.invalid/';

function installFs(fsUtils, wx, config) {
    if (!fsUtils || typeof fsUtils.downloadFile !== 'function') throw new Error('Cocos fsUtils.downloadFile unavailable');
    const original = fsUtils.downloadFile.bind(fsUtils);
    let ready;
    fsUtils.downloadFile = function(url, filePath, header, onProgress, complete) {
        if (!url.startsWith(ORIGIN)) return original(url, filePath, header, onProgress, complete);
        const relative = url.slice(ORIGIN.length);
        if (!/^[a-f0-9]{2}\/[a-f0-9-]+(?:\.[a-f0-9]+)?\.png$/.test(relative)) {
            complete(new Error('Invalid game asset path')); return;
        }
        if (!wx.cloud || typeof wx.cloud.downloadFile !== 'function') {
            complete(new Error('微信云存储下载能力不可用')); return;
        }
        if (!ready) ready = Promise.resolve().then(() => wx.cloud.init({env: config.env, traceUser: false}))
            .catch(error => { ready = null; throw error; });
        let task, cancelled = false, finished = false;
        const finish = (error, path) => {
            if (finished) return;
            finished = true; clearTimeout(timer);
            complete(error, path);
        };
        const timer = setTimeout(() => {
            cancelled = true;
            if (task && task.abort) task.abort();
            finish(new Error('云存储图片下载超时：' + relative));
        }, 45000);
        ready.then(() => {
            if (cancelled) return;
            const options = {
                config: {env: config.env},
                fileID: config.fileRoot + '/' + relative,
                success: result => {
                    const path = result.tempFilePath || result.filePath;
                    finish(path ? null : new Error('云存储未返回图片路径'), path);
                },
                fail: error => {
                    console.warn('[MN-ASSET] download.fail', relative, error.errCode, error.errMsg);
                    finish(new Error('云存储图片下载失败：' + relative + '；' + (error.errMsg || '未知错误')));
                }
            };
            if (filePath) options.filePath = filePath;
            task = wx.cloud.downloadFile(options);
            if (onProgress && task && task.onProgressUpdate) task.onProgressUpdate(onProgress);
        }).catch(error => finish(error));
        return {abort() { cancelled = true; if (task && task.abort) task.abort(); finish(new Error('Asset download cancelled')); }};
    };
}

function installPipeline(assetManager) {
    assetManager.transformPipeline.append(task => {
        // Cocos moves the preceding pipe's output to input and clears output.
        // Always pass the complete request array to the next stage, including bundles.
        task.output = task.input;
        for (const item of task.input) {
            if (item.isNative && typeof item.url === 'string' && item.url.startsWith('assets/resources/native/')) {
                item.url = ORIGIN + item.url.slice('assets/resources/native/'.length);
            }
        }
    });
}

module.exports = {ORIGIN, installFs, installPipeline};
