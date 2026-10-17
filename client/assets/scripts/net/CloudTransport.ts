/** WeChat-only cloud-container transport; HTTP and socket authentication remain the game's bearer token. */
import { serverUrl, wechatCloudSettings } from './Config';
import { diagnosticError, networkLog } from './Diagnostics';
import type { Socket } from './GameClient';

interface SocketTask {
    onOpen(fn: (event: unknown) => void): void;
    onMessage(fn: (event: { data: unknown }) => void): void;
    onError(fn: (event: unknown) => void): void;
    onClose(fn: (event: { code?: number; reason?: string }) => void): void;
    send(options: { data: string; fail?(error: unknown): void }): void;
    close(options: { code?: number; reason?: string; fail?(error: unknown): void }): void;
}

interface CloudApi {
    init(options: { env: string; traceUser: boolean }): void | Promise<unknown>;
    callContainer?(options: {
        config: { env: string }; path: string; method: string; header: Record<string, string>; data?: string;
        dataType: string; responseType: string; timeout: number;
    }): Promise<{ statusCode: number; data: unknown }>;
    connectContainer?(options: {
        config: { env: string }; service: string; path: string; timeout: number;
    }): Promise<{ socketTask: SocketTask }>;
}

export interface CloudTarget { env: string; service: string; path: string }

/** Only route this backend through cloud containers, never third-party asset URLs. */
export function cloudTarget(url: string): CloudTarget | null {
    const wx = (globalThis as unknown as { wx?: { login?: unknown } }).wx;
    if (!wx || typeof wx.login !== 'function') return null;
    const normalize = (value: string) => value.replace(/^ws(s?):/, 'http$1:');
    const target = normalize(url).match(/^https?:\/\/([^/?#]+)([^#]*)/i);
    const backend = serverUrl().match(/^https?:\/\/([^/?#]+)/i);
    if (!target || !backend || target[1].toLowerCase() !== backend[1].toLowerCase()) return null;
    return { ...wechatCloudSettings(), path: target[2] || '/' };
}

const initialized = new WeakMap<CloudApi, Map<string, Promise<void>>>();

function failure(code: string, hint: string, error?: unknown): Error {
    const details = diagnosticError(error);
    return Object.assign(new Error(hint), { code, errMsg: hint + (details.errMsg || details.message ? '：' + (details.errMsg || details.message) : '') });
}

async function cloudApi(target: CloudTarget, capability: 'callContainer' | 'connectContainer'): Promise<CloudApi> {
    if (!target.env || !target.service || !/^[a-zA-Z0-9-]+$/.test(target.env) || !/^[a-zA-Z0-9-]+$/.test(target.service)) {
        throw failure('WX_CLOUD_CONFIG_INVALID', '云托管环境 ID 或服务名未正确配置');
    }
    const cloud = (globalThis as unknown as { wx?: { cloud?: CloudApi } }).wx?.cloud;
    if (!cloud || typeof cloud.init !== 'function' || typeof cloud[capability] !== 'function') {
        throw failure('WX_CLOUD_UNAVAILABLE', '当前微信环境缺少 wx.cloud.' + capability + '，请检查基础库及云开发能力');
    }
    let environments = initialized.get(cloud);
    if (!environments) { environments = new Map(); initialized.set(cloud, environments); }
    let ready = environments.get(target.env);
    if (!ready) {
        const entries = environments;
        ready = Promise.resolve().then(async () => {
            networkLog('cloud.init.start', { env: target.env, service: target.service });
            await cloud.init({ env: target.env, traceUser: false });
            networkLog('cloud.init.end', { env: target.env, service: target.service });
        }).catch(error => {
            entries.delete(target.env); // A subsequent user retry may initialize again.
            networkLog('cloud.init.fail', { env: target.env, ...diagnosticError(error) }, true);
            throw failure('WX_CLOUD_INIT_FAILED', '云托管初始化失败，请检查小游戏与环境的关联', error);
        });
        environments.set(target.env, ready);
    }
    await ready;
    return cloud;
}

export async function cloudRequest(target: CloudTarget, method: string, trace: string, data?: string, token?: string): Promise<{ statusCode: number; data: unknown }> {
    const cloud = await cloudApi(target, 'callContainer');
    const header: Record<string, string> = { 'X-WX-SERVICE': target.service, 'Content-Type': 'application/json', 'X-MN-Trace-Id': trace };
    if (token) header.Authorization = 'Bearer ' + token;
    try {
        return await cloud.callContainer!({ config: { env: target.env }, path: target.path, method, header, data,
            dataType: 'text', responseType: 'text', timeout: 10000 });
    } catch (error) { throw failure('WX_CLOUD_REQUEST_FAILED', '云托管请求失败', error); }
}

/** Adapt asynchronous connectContainer/SocketTask to GameClient's synchronous WebSocket-shaped factory. */
export class CloudSocket implements Socket {
    readyState = 0;
    onopen: Socket['onopen'] = null;
    onmessage: Socket['onmessage'] = null;
    onerror: Socket['onerror'] = null;
    onclose: Socket['onclose'] = null;
    private task: SocketTask | null = null;

    constructor(target: CloudTarget) {
        // The microtask lets GameClient install handlers before initialization can fail.
        void Promise.resolve().then(async () => {
            const cloud = await cloudApi(target, 'connectContainer');
            if (this.readyState === 3) return;
            const result = await cloud.connectContainer!({ config: { env: target.env }, service: target.service, path: target.path, timeout: 10000 });
            const task = result?.socketTask;
            if (!task) throw new Error('connectContainer 未返回 socketTask');
            if (this.readyState === 3) { this.closeTask(task, 1000, 'cancelled'); return; }
            this.task = task;
            task.onOpen(event => { if (this.readyState === 0) { this.readyState = 1; this.onopen?.(event); } });
            task.onMessage(event => { if (this.readyState === 1) this.onmessage?.(event); });
            task.onClose(event => this.finish(event));
            task.onError(error => this.fail(error));
        }).catch(error => this.fail(error));
    }

    send(data: string): void {
        if (this.readyState !== 1 || !this.task) throw new Error('云托管连接尚未打开');
        try { this.task.send({ data, fail: error => this.fail(error) }); }
        catch (error) { this.fail(error); }
    }

    close(code = 1000, reason = 'bye'): void {
        if (this.readyState === 3) return;
        const task = this.task;
        this.finish({ code, reason, wasClean: true });
        if (task) this.closeTask(task, code, reason);
    }

    private fail(error: unknown): void {
        if (this.readyState === 3) return;
        const task = this.task;
        const problem = failure('WX_CLOUD_SOCKET_FAILED', '云托管长连接失败', error);
        try { this.onerror?.(problem); }
        finally {
            this.finish({ code: 1006, reason: problem.message, wasClean: false });
            if (task) this.closeTask(task, 1000, 'connection failed');
        }
    }

    private finish(event: { code?: number; reason?: string; wasClean?: boolean }): void {
        if (this.readyState === 3) return;
        this.readyState = 3;
        this.task = null;
        this.onclose?.(event);
    }

    private closeTask(task: SocketTask, code: number, reason: string): void {
        try { task.close({ code, reason, fail: () => undefined }); } catch { /* already closed */ }
    }
}
