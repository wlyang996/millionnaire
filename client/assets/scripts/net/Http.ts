/**
 * 最小 JSON 请求：优先用 XMLHttpRequest（浏览器与微信小游戏的 Cocos 适配层都提供），没有时退回 fetch（Node 测试）。
 */
export interface HttpResult<T> {
    status: number;
    body: T | null;
}

export function requestJson<T>(method: 'GET' | 'POST', url: string, body?: unknown, token?: string): Promise<HttpResult<T>> {
    const payload = body === undefined ? undefined : JSON.stringify(body);
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
