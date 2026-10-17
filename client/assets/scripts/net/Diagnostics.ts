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
    return String(value ?? '')
        .replace(/(?:https?|wss?):\/\/[^\s"'<>]+/gi, url => safeEndpoint(url))
        .replace(/\bBearer\s+[^\s,;"']+/gi, 'Bearer [redacted]')
        .replace(/\b(secret|appsecret|js_code|code|token|access_token|openid|session_key|authorization)\b["']?\s*[=:]\s*(?:"[^"]*"|'[^']*'|[^\s,;&}\]]+)/gi, '$1=[redacted]')
        .replace(/[\r\n\t]/g, ' ').slice(0, 600);
}

export function diagnosticError(error: unknown): Record<string, string | number> {
    const e = (error && typeof error === 'object' ? error : { message: error }) as {
        name?: unknown; code?: unknown; errMsg?: unknown; message?: unknown; wxErrcode?: unknown;
    };
    const out: Record<string, string | number> = {};
    for (const key of ['name', 'code', 'errMsg', 'message'] as const) {
        if (typeof e[key] === 'string' || typeof e[key] === 'number') out[key] = safeDiagnosticText(e[key]);
    }
    if (typeof e.wxErrcode === 'number') out.wxErrcode = e.wxErrcode;
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
