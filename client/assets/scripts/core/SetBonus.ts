/**
 * 同组地产加成（纯 TS）：同一组的普通地产全部归同一人且都未抵押时，组内每块地的租金 × 倍率（向下取整到 10）。
 * 联机以房间绑定的后台参数为准（applyServerSets）；演示用内置默认：棋盘四条边上的普通地产按顺序两两一组（与服务端 SetBonus.bySides 一致）。
 */
import { gridFor } from './BoardLayout';
import { BoardTile, GameView } from './Models';

let percent = 150;
let serverGroups: Record<string, number[]> | null = null;
const defaults: Record<string, number[]> = {};

export function applyServerSets(s: { rentPercent: number; groups: Record<string, number[]> } | undefined): void {
    if (!s) return;
    percent = s.rentPercent;
    serverGroups = s.groups;
}

export function setBonusPercent(): number {
    return percent;
}

/** 默认分组：四条边上的普通地产两两一组，一条边剩 3 块时最后一组 3 块。 */
export function defaultGroups(tiles: BoardTile[]): number[] {
    const g = gridFor(tiles.length === 30 ? 30 : 50);
    const n = tiles.length;
    const corners = [0, g.cols - 1, g.cols + g.rows - 2, 2 * g.cols + g.rows - 3, n];
    const out = new Array<number>(n).fill(0);
    let next = 1;
    for (let s = 0; s < 4; s++) {
        const props: number[] = [];
        for (let i = corners[s]; i < corners[s + 1]; i++) if (tiles[i].type === 'PROPERTY') props.push(i);
        for (let k = 0; k + 1 < props.length; k += 2) {
            const three = k + 3 === props.length;
            out[props[k]] = next;
            out[props[k + 1]] = next;
            if (three) {
                out[props[k + 2]] = next;
                k++;
            }
            next++;
        }
    }
    return out;
}

function groupsOf(boardId: string, tiles: BoardTile[]): number[] {
    const s = serverGroups?.[boardId];
    if (s && s.length === tiles.length) return s;
    if (!defaults[boardId] || defaults[boardId].length !== tiles.length) defaults[boardId] = defaultGroups(tiles);
    return defaults[boardId];
}

/** 某格的组号（0 = 不分组）。 */
export function groupOf(game: Pick<GameView, 'boardId' | 'tiles'>, tile: number): number {
    return groupsOf(game.boardId, game.tiles)[tile] ?? 0;
}

/** 与某格同组的全部格子（含自己）；不分组为空。 */
export function groupMembers(game: Pick<GameView, 'boardId' | 'tiles'>, tile: number): number[] {
    const gs = groupsOf(game.boardId, game.tiles);
    const id = gs[tile] ?? 0;
    if (!id) return [];
    const out: number[] = [];
    gs.forEach((v, i) => {
        if (v === id) out.push(i);
    });
    return out;
}

/** 同组加成是否生效：整组归同一人且都未抵押（倍率为 100 时视为关闭）。 */
export function setComplete(game: Pick<GameView, 'boardId' | 'tiles' | 'properties'>, tile: number): boolean {
    if (percent <= 100) return false;
    const members = groupMembers(game, tile);
    if (!members.length) return false;
    const owner = game.properties.find((p) => p.tileIndex === tile)?.owner;
    if (!owner) return false;
    return members.every((i) => {
        const p = game.properties.find((x) => x.tileIndex === i);
        return !!p && p.owner === owner && !p.mortgaged;
    });
}

/** 基础租金乘同组倍率（向下取整到 10）。 */
export function applySet(base: number): number {
    return Math.floor((base * percent) / 100 / 10) * 10;
}

/** 组色（按组号循环取色），用于棋盘格顶部的细条。 */
const GROUP_COLORS = ['#E8505B', '#F6A623', '#F8D347', '#5DBB63', '#2EB5C9', '#3D7BE0', '#8E6BE0', '#E06BB9',
    '#A0703C', '#7FB800', '#00A39A', '#5568C9', '#C94F4F', '#6E8A9E'];
export function groupColor(id: number): string {
    return GROUP_COLORS[(id - 1 + GROUP_COLORS.length) % GROUP_COLORS.length];
}

