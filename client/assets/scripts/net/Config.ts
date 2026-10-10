/**
 * 联机配置。纯 TS，不依赖 cc。
 * - 服务地址：默认云托管 prod；H5 可用 ?server=http://localhost:8080 覆盖（本地联调），也可预先设置 globalThis.__MN_SERVER；
 * - 演示模式：H5 地址带 ?demo=1 时不联网，沿用 MockStore 的演示数据（美术评审用）。
 */
export const DEFAULT_SERVER = 'https://springboot-ad5i-prod-d6gztvk354abc40f5-1501212143.ap-shanghai.run.wxcloudrun.com';

function query(name: string): string | null {
    const loc = (globalThis as unknown as { location?: { search?: string } }).location;
    if (!loc || typeof loc.search !== 'string') return null; // 微信小游戏没有 location
    const m = loc.search.match(new RegExp('[?&]' + name + '=([^&]*)'));
    return m ? decodeURIComponent(m[1]) : null;
}

export function serverUrl(): string {
    const override = query('server') ?? (globalThis as unknown as { __MN_SERVER?: string }).__MN_SERVER;
    return (override || DEFAULT_SERVER).replace(/\/+$/, '');
}

export function onlineEnabled(): boolean {
    return query('demo') !== '1';
}

export function previewName(): string | null {
    return onlineEnabled() ? null : query('preview');
}
