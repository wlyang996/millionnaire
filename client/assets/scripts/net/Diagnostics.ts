/** Connection diagnostics only: never pass request/response bodies, headers, or credentials. */
let sequence = 0;
export function diagnosticId(prefix: string): string {
    return prefix + '-' + Date.now().toString(36) + '-' + (++sequence).toString(36) + '-' + Math.random().toString(36).slice(2, 8);
}

/** Cocos/WeChat need not provide URL; discard credentials, query and fragment without it. */
export function safeEndpoint(value: string): string {
    const base = value.split(/[?#]/, 1)[0];
    return base.replace(/^(\w+:\/\/)[^/@]*@/, '$1').slice(0, 240);
}

export function safeDiagnosticText(value: unknown): string {
    const text = String(value ?? '');
    const cloudFailure = /\bcloud\.(?:callContainer|connectContainer|init):fail\b/i.test(text);
    return text
        .replace(/(?:https?|wss?):\/\/[^\s"'<>]+/gi, url => safeEndpoint(url))
        .replace(/\bBearer\s+[^\s,;"']+/gi, 'Bearer [redacted]')
        .replace(/\b(secret|appsecret|js_code|code|token|access_token|openid|session_key|authorization)\b["']?\s*[=:]\s*("[^"]*"|'[^']*'|\[[^\]\r\n]{1,80}\]|[^\s,;&}\]]+)/gi,
            (_match, key: string, raw: string) => {
                // In native cloud failure messages, code=[INVALID_ENV] / code=[-501000]
                // is a platform diagnostic, not wx.login's one-time credential.
                if (cloudFailure && key.toLowerCase() === 'code' && /^(?:-?\d{1,9}|\[(?:-?\d{1,9}|[A-Za-z][A-Za-z0-9_.-]{1,63})\])$/.test(raw)) return key + '=' + raw;
                return key + '=[redacted]';
            })
        .replace(/[\r\n\t]/g, ' ').slice(0, 600);
}

export function diagnosticError(error: unknown): Record<string, string | number> {
    const e = (error && typeof error === 'object' ? error : { message: error }) as {
        name?: unknown; code?: unknown; errMsg?: unknown; message?: unknown; wxErrcode?: unknown;
        errCode?: unknown; errcode?: unknown; errno?: unknown; platformCode?: unknown;
    };
    const out: Record<string, string | number> = {};
    for (const key of ['name', 'code', 'errMsg', 'message'] as const) {
        if (typeof e[key] === 'string' || typeof e[key] === 'number') out[key] = safeDiagnosticText(e[key]);
    }
    if (typeof e.wxErrcode === 'number') out.wxErrcode = e.wxErrcode;
    for (const key of ['errCode', 'errcode', 'errno'] as const) {
        if ((typeof e[key] === 'number' || typeof e[key] === 'string') && /^-?\d{1,9}$/.test(String(e[key]))) out[key] = Number(e[key]);
    }
    const native = typeof e.errMsg === 'string' ? e.errMsg : '';
    const platform = /\bcloud\.(?:callContainer|connectContainer|init):fail\b/i.test(native)
        ? native.match(/\bcode\s*=\s*(\[(?:-?\d{1,9}|[A-Za-z][A-Za-z0-9_.-]{1,63})\]|-?\d{1,9})(?=[\s,.;}]|$)/i)?.[1] : undefined;
    if (platform) out.platformCode = platform;
    else if (typeof e.platformCode === 'string' && /^(?:-?\d{1,9}|\[(?:-?\d{1,9}|[A-Za-z][A-Za-z0-9_.-]{1,63})\])$/.test(e.platformCode)) out.platformCode = e.platformCode;
    return out;
}

export function networkLog(event: string, fields: Record<string, string | number | boolean | null | undefined> = {}, failed = false): void {
    // One JSON line copies cleanly from the WeChat device console. Logs must never affect networking.
    try {
        const safe: Record<string, unknown> = { time: new Date().toISOString(), event };
        for (const [key, value] of Object.entries(fields)) safe[key] = typeof value === 'string' ? safeDiagnosticText(value) : value;
        (failed ? console.warn : console.info)('[MN-NET] ' + JSON.stringify(safe));
    } catch { /* console may be unavailable on a platform adapter */ }
}
