/**
 * 棋盘模板与环形布局（纯 TS）。
 * 30 格：起点1 地产16(低6/中6/高4) 事件5 银行1 监狱1 休息1 游戏区1 车站4；拍卖地产 3 块（每档 1）。
 * 50 格：起点1 地产28(低10/中10/高8) 事件9 银行2 监狱1 休息1 游戏区2 车站6；拍卖地产 5 块（低2 中2 高1）。
 */
import { boardNames } from './BoardNames';
import { BoardTile, Tier, TileType } from './Models';

/** 与服务端 RuleConfigs.LAYOUT_30 一致：E 抽卡事件，F 固定事件。 */
const SEQ30 = 'S L E M T H L J M F L T H M B G L E M T H L R M F L T M H E'.split(' ');

/** 固定事件格效果（与服务端 RuleConfigs.FIXED_30 / FIXED_50 一致；联机以服务端下发为准）。 */
const FIXED30 = [{ kind: 'CASH_FINE', amount: 200, label: '随地吐痰' }, { kind: 'TO_STATION', amount: 0, label: '搭乘快车' }];
const FIXED50 = [
    { kind: 'CASH_REWARD', amount: 300, label: '好人好事' }, { kind: 'BUILD', amount: 0, label: '免费加盖' },
    { kind: 'CASH_FINE', amount: 200, label: '随地吐痰' }, { kind: 'TO_START', amount: 0, label: '回到起点' },
];

function build50(): string[] {
    const fixed: Record<number, string> = {
        0: 'S', 11: 'J', 25: 'G', 36: 'R', 6: 'B', 31: 'B', 44: 'G',
        4: 'T', 15: 'T', 21: 'T', 29: 'T', 40: 'T', 47: 'T',
        2: 'E', 9: 'F', 13: 'E', 18: 'F', 23: 'E', 28: 'F', 33: 'E', 38: 'F', 43: 'E',
    };
    const left: Record<string, number> = { L: 10, M: 10, H: 8 };
    const rot = ['M', 'L', 'H', 'L', 'M', 'H'];
    let r = 0;
    const out: string[] = [];
    for (let i = 0; i < 50; i++) {
        if (fixed[i]) {
            out.push(fixed[i]);
            continue;
        }
        let pick = '';
        for (let k = 0; k < rot.length; k++) {
            const c = rot[(r + k) % rot.length];
            if (left[c] > 0) {
                pick = c;
                r = (r + k + 1) % rot.length;
                break;
            }
        }
        left[pick]--;
        out.push(pick);
    }
    return out;
}

const TIER_OF: Record<string, Tier> = { L: 'LOW', M: 'MID', H: 'HIGH' };
const TYPE_OF: Record<string, TileType> = {
    S: 'START', E: 'EVENT', F: 'FIXED_EVENT', B: 'BANK', J: 'JAIL', R: 'REST', G: 'GAME_ZONE', T: 'STATION',
};
export const TILE_TYPE_NAME: Record<TileType, string> = {
    START: '起点', PROPERTY: '地产', STATION: '车站', EVENT: '事件', FIXED_EVENT: '固定事件', BANK: '银行', JAIL: '监狱', REST: '休息', GAME_ZONE: '游戏区',
};

/** 每档里被标记为"拍卖地产"的是该档第 n 块（0 起） */
const AUCTION_PICK30: Record<Tier, number[]> = { LOW: [3], MID: [2], HIGH: [1] };
const AUCTION_PICK50: Record<Tier, number[]> = { LOW: [2, 6], MID: [1, 5], HIGH: [3] };

export function buildBoard(size: 30 | 50): BoardTile[] {
    const seq = size === 30 ? SEQ30 : build50();
    const names = boardNames(size);
    const pick = size === 30 ? AUCTION_PICK30 : AUCTION_PICK50;
    const seen: Record<Tier, number> = { LOW: 0, MID: 0, HIGH: 0 };
    const fixed = size === 30 ? FIXED30 : FIXED50;
    let fixedNo = 0;
    return seq.map((code, index): BoardTile => {
        if (TIER_OF[code]) {
            const tier = TIER_OF[code];
            const n = seen[tier]++;
            return { index, type: 'PROPERTY', tier, name: names[index], auctionLot: pick[tier].indexOf(n) >= 0 };
        }
        const type = TYPE_OF[code];
        if (type === 'FIXED_EVENT') {
            const f = fixed[fixedNo++];
            return { index, type, name: f.label, fixed: f };
        }
        return { index, type, name: names[index] };
    });
}

export function countTypes(tiles: BoardTile[]): Record<string, number> {
    const r: Record<string, number> = {};
    for (const t of tiles) {
        r[t.type] = (r[t.type] ?? 0) + 1;
        if (t.tier) r[t.tier] = (r[t.tier] ?? 0) + 1;
        if (t.auctionLot) r['AUCTION_LOT'] = (r['AUCTION_LOT'] ?? 0) + 1;
    }
    return r;
}

export interface GridSpec { cols: number; rows: number; tile: number }

/** Display bands: enlarge the perimeter without changing ring indices or board extent. */
export function boardAxis(count: number, tile: number): number[] {
    const border = tile * 1.8;
    const inner = (count * tile - 2 * border) / (count - 2);
    return Array.from({ length: count + 1 }, (_, i) =>
        i === 0 ? 0 : i === count ? count * tile : border + (i - 1) * inner);
}

export function axisCell(edges: number[], position: number): number {
    if (position < 0 || position >= edges[edges.length - 1]) return -1;
    return edges.findIndex((edge, i) => i < edges.length - 1 && position >= edge && position < edges[i + 1]);
}

/** 环形网格：30 格 8×9（周长 30），50 格 12×15（周长 50）。 */
export function gridFor(size: 30 | 50): GridSpec {
    return size === 30 ? { cols: 8, rows: 9, tile: 84 } : { cols: 12, rows: 15, tile: 58 };
}

/** 第 index 格在网格中的 (col,row)，row 0 在上；起点在左下，顺时针（先向右）。 */
export function gridCell(index: number, g: GridSpec): { col: number; row: number } {
    const { cols, rows } = g;
    const n = 2 * (cols + rows) - 4;
    const i = ((index % n) + n) % n;
    if (i < cols) return { col: i, row: rows - 1 };
    if (i < cols + rows - 1) return { col: cols - 1, row: rows - 1 - (i - (cols - 1)) };
    if (i < 2 * cols + rows - 2) return { col: cols - 1 - (i - (cols + rows - 2)), row: 0 };
    return { col: 0, row: i - (2 * cols + rows - 3) };
}

/** 格子左上角在棋盘世界（y 向下，左上为原点）中的像素位置。 */
export function tileOrigin(index: number, g: GridSpec): { x: number; y: number } {
    const c = gridCell(index, g);
    return { x: c.col * g.tile, y: c.row * g.tile };
}

export function ringLength(g: GridSpec): number {
    return 2 * (g.cols + g.rows) - 4;
}

/**
 * 事件牌堆（design/screens/11-event-idle-preview.png）在棋盘世界坐标中的位置：外圈内侧左上角，紧贴左列与上排之内。
 * 屏幕上约 190×140 设计像素（宽约占屏宽 26%），按棋盘适配缩放 fit 换算成世界单位，并夹在内圈范围内。
 */
export function eventDeckRect(g: GridSpec, fit: number): { x: number; y: number; w: number; h: number } {
    const innerW = (g.cols - 2) * g.tile;
    const innerH = (g.rows - 2) * g.tile;
    const w = Math.min(190 / fit, innerW * 0.5);
    const h = Math.min(140 / fit, innerH * 0.4);
    // 左边多留 22 屏幕像素：扇形左侧牌角旋转后会向外探出，不能压到左列格子
    return { x: g.tile + 22 / fit, y: g.tile + 6, w, h };
}
