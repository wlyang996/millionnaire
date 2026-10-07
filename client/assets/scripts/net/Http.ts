/**
 * 最小 JSON 请求：优先用 XMLHttpRequest（浏览器与微信小游戏的 Cocos 适配层都提供），没有时退回 fetch（Node 测试）。
 */
export interface HttpResult<T> {
    status: number;
    body: T | null;
}

interface WxRequestApi {
    request(o: {
        url: string; method: string; data?: string; header?: Record<string, string>; dataType?: string; responseType?: string; timeout?: number;
        success(r: { statusCode: number; data: unknown }): void; fail(e: { errMsg?: string }): void;
    }): void;
}

export function requestJson<T>(method: 'GET' | 'POST', url: string, body?: unknown, token?: string): Promise<HttpResult<T>> {
    const payload = body === undefined ? undefined : JSON.stringify(body);
    // 微信小游戏：直接用 wx.request，失败时能拿到 errMsg（如 "request:fail url not in domain list"），适配层的 XHR 只给出 network error
    const wx = (globalThis as unknown as { wx?: WxRequestApi }).wx;
    if (wx && typeof wx.request === 'function') {
        return new Promise((resolve, reject) => {
            const header: Record<string, string> = { 'Content-Type': 'application/json' };
            if (token) header.Authorization = 'Bearer ' + token;
            wx.request({
                url, method, data: payload, header, dataType: '其他', responseType: 'text', timeout: 10000,
                success: (r) => resolve({ status: r.statusCode, body: parse<T>(typeof r.data === 'string' ? r.data : JSON.stringify(r.data)) }),
                fail: (e) => reject(Object.assign(new Error('network error'), { errMsg: e && e.errMsg ? e.errMsg : 'request:fail' })),
            });
        });
    }
    const g = globalThis as unknown as { XMLHttpRequest?: new () => XMLHttpRequest; fetch?: typeof fetch };
    if (g.XMLHttpRequest) {
        return new Promise((resolve, reject) => {
            const xhr = new g.XMLHttpRequest!();
            xhr.open(method, url, true);
            xhr.timeout = 10000;
            xhr.setRequestHeader('Content-Type', 'application/json');
            if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
            xhr.onload = () => resolve({ status: xhr.status, body: parse<T>(xhr.responseText) });
            xhr.onerror = () => reject(new Error('network error'));
            xhr.ontimeout = () => reject(new Error('timeout'));
            xhr.send(payload ?? null);
        });
    }
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = 'Bearer ' + token;
    return g.fetch!(url, { method, headers, body: payload }).then(async (r) => ({
        status: r.status,
        body: parse<T>(await r.text()),
    }));
}

function parse<T>(text: string): T | null {
    if (!text) return null;
    try {
        return JSON.parse(text) as T;
    } catch {
        return null;
    }
}
