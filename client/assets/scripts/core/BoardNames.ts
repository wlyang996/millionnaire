/**
* 地名与设计位置（取自 design/ui/board-v6/map-ui-definition.json，三字地名按 LAND_NAMES.md）。
* 格子、详情、资产列表、交易/拍卖弹窗的名称统一从这里取。纯 TS，SelfCheck 校验。
*/

export interface DesignTile { index: number; col: number; row: number; type: string; tier: string | null; name: string }

export const DESIGN_30: { cols: number; rows: number; tiles: DesignTile[] } = {
    cols: 8, rows: 9,
    tiles: [
        { index: 0, col: 0, row: 8, type: 'START', tier: null, name: '小镇入口' },
        { index: 1, col: 1, row: 8, type: 'PROPERTY', tier: 'LOW', name: '青麦路' },
        { index: 2, col: 2, row: 8, type: 'EVENT', tier: null, name: '事件1' },
        { index: 3, col: 3, row: 8, type: 'PROPERTY', tier: 'MID', name: '陶艺街' },
        { index: 4, col: 4, row: 8, type: 'STATION', tier: null, name: '风车站' },
        { index: 5, col: 5, row: 8, type: 'PROPERTY', tier: 'HIGH', name: '星湖路' },
        { index: 6, col: 6, row: 8, type: 'PROPERTY', tier: 'LOW', name: '风车路' },
        { index: 7, col: 7, row: 8, type: 'JAIL', tier: null, name: '小镇监狱' },
        { index: 8, col: 7, row: 7, type: 'PROPERTY', tier: 'MID', name: '木匠街' },
        { index: 9, col: 7, row: 6, type: 'FIXED_EVENT', tier: null, name: '随地吐痰' },
        { index: 10, col: 7, row: 5, type: 'PROPERTY', tier: 'LOW', name: '雏菊巷' },
        { index: 11, col: 7, row: 4, type: 'STATION', tier: null, name: '林荫站' },
        { index: 12, col: 7, row: 3, type: 'PROPERTY', tier: 'HIGH', name: '银湾路' },
        { index: 13, col: 7, row: 2, type: 'PROPERTY', tier: 'MID', name: '石桥路' },
        { index: 14, col: 7, row: 1, type: 'BANK', tier: null, name: '小镇银行' },
        { index: 15, col: 7, row: 0, type: 'GAME_ZONE', tier: null, name: '游乐广场' },
        { index: 16, col: 6, row: 0, type: 'PROPERTY', tier: 'LOW', name: '向阳巷' },
        { index: 17, col: 5, row: 0, type: 'EVENT', tier: null, name: '事件2' },
        { index: 18, col: 4, row: 0, type: 'PROPERTY', tier: 'MID', name: '河灯巷' },
        { index: 19, col: 3, row: 0, type: 'STATION', tier: null, name: '湖畔站' },
        { index: 20, col: 2, row: 0, type: 'PROPERTY', tier: 'HIGH', name: '碧水路' },
        { index: 21, col: 1, row: 0, type: 'PROPERTY', tier: 'LOW', name: '松果巷' },
        { index: 22, col: 0, row: 0, type: 'REST', tier: null, name: '河畔公园' },
        { index: 23, col: 0, row: 1, type: 'PROPERTY', tier: 'MID', name: '布谷巷' },
        { index: 24, col: 0, row: 2, type: 'FIXED_EVENT', tier: null, name: '搭乘快车' },
        { index: 25, col: 0, row: 3, type: 'PROPERTY', tier: 'LOW', name: '白桦巷' },
        { index: 26, col: 0, row: 4, type: 'STATION', tier: null, name: '河湾站' },
        { index: 27, col: 0, row: 5, type: 'PROPERTY', tier: 'MID', name: '燕子巷' },
        { index: 28, col: 0, row: 6, type: 'PROPERTY', tier: 'HIGH', name: '白帆街' },
        { index: 29, col: 0, row: 7, type: 'EVENT', tier: null, name: '事件3' },
    ],
};

export const DESIGN_50: { cols: number; rows: number; tiles: DesignTile[] } = {
    cols: 12, rows: 15,
    tiles: [
        { index: 0, col: 0, row: 14, type: 'START', tier: null, name: '小镇入口' },
        { index: 1, col: 1, row: 14, type: 'PROPERTY', tier: 'MID', name: '陶艺街' },
        { index: 2, col: 2, row: 14, type: 'EVENT', tier: null, name: '事件1' },
        { index: 3, col: 3, row: 14, type: 'PROPERTY', tier: 'LOW', name: '青麦路' },
        { index: 4, col: 4, row: 14, type: 'STATION', tier: null, name: '风车站' },
        { index: 5, col: 5, row: 14, type: 'PROPERTY', tier: 'HIGH', name: '星湖路' },
        { index: 6, col: 6, row: 14, type: 'BANK', tier: null, name: '小镇银行' },
        { index: 7, col: 7, row: 14, type: 'PROPERTY', tier: 'LOW', name: '风车路' },
        { index: 8, col: 8, row: 14, type: 'PROPERTY', tier: 'MID', name: '木匠街' },
        { index: 9, col: 9, row: 14, type: 'FIXED_EVENT', tier: null, name: '好人好事' },
        { index: 10, col: 10, row: 14, type: 'PROPERTY', tier: 'HIGH', name: '银湾路' },
        { index: 11, col: 11, row: 14, type: 'JAIL', tier: null, name: '小镇监狱' },
        { index: 12, col: 11, row: 13, type: 'PROPERTY', tier: 'MID', name: '石桥路' },
        { index: 13, col: 11, row: 12, type: 'EVENT', tier: null, name: '事件2' },
        { index: 14, col: 11, row: 11, type: 'PROPERTY', tier: 'LOW', name: '雏菊巷' },
        { index: 15, col: 11, row: 10, type: 'STATION', tier: null, name: '林荫站' },
        { index: 16, col: 11, row: 9, type: 'PROPERTY', tier: 'HIGH', name: '碧水路' },
        { index: 17, col: 11, row: 8, type: 'PROPERTY', tier: 'LOW', name: '向阳巷' },
        { index: 18, col: 11, row: 7, type: 'FIXED_EVENT', tier: null, name: '免费加盖' },
        { index: 19, col: 11, row: 6, type: 'PROPERTY', tier: 'MID', name: '河灯巷' },
        { index: 20, col: 11, row: 5, type: 'PROPERTY', tier: 'HIGH', name: '白帆街' },
        { index: 21, col: 11, row: 4, type: 'STATION', tier: null, name: '湖畔站' },
        { index: 22, col: 11, row: 3, type: 'PROPERTY', tier: 'MID', name: '布谷巷' },
        { index: 23, col: 11, row: 2, type: 'EVENT', tier: null, name: '事件3' },
        { index: 24, col: 11, row: 1, type: 'PROPERTY', tier: 'LOW', name: '松果巷' },
        { index: 25, col: 11, row: 0, type: 'GAME_ZONE', tier: null, name: '游乐广场' },
        { index: 26, col: 10, row: 0, type: 'PROPERTY', tier: 'HIGH', name: '云顶路' },
        { index: 27, col: 9, row: 0, type: 'PROPERTY', tier: 'LOW', name: '白桦巷' },
        { index: 28, col: 8, row: 0, type: 'FIXED_EVENT', tier: null, name: '随地吐痰' },
        { index: 29, col: 7, row: 0, type: 'STATION', tier: null, name: '河湾站' },
        { index: 30, col: 6, row: 0, type: 'PROPERTY', tier: 'MID', name: '燕子巷' },
        { index: 31, col: 5, row: 0, type: 'BANK', tier: null, name: '河湾银行' },
        { index: 32, col: 4, row: 0, type: 'PROPERTY', tier: 'HIGH', name: '望月路' },
        { index: 33, col: 3, row: 0, type: 'EVENT', tier: null, name: '事件4' },
        { index: 34, col: 2, row: 0, type: 'PROPERTY', tier: 'MID', name: '书香街' },
        { index: 35, col: 1, row: 0, type: 'PROPERTY', tier: 'LOW', name: '青果路' },
        { index: 36, col: 0, row: 0, type: 'REST', tier: null, name: '河畔公园' },
        { index: 37, col: 0, row: 1, type: 'PROPERTY', tier: 'HIGH', name: '暖泉路' },
        { index: 38, col: 0, row: 2, type: 'FIXED_EVENT', tier: null, name: '回到起点' },
        { index: 39, col: 0, row: 3, type: 'PROPERTY', tier: 'LOW', name: '蜜桃路' },
        { index: 40, col: 0, row: 4, type: 'STATION', tier: null, name: '集市站' },
        { index: 41, col: 0, row: 5, type: 'PROPERTY', tier: 'MID', name: '学府路' },
        { index: 42, col: 0, row: 6, type: 'PROPERTY', tier: 'HIGH', name: '樱泉路' },
        { index: 43, col: 0, row: 7, type: 'EVENT', tier: null, name: '事件5' },
        { index: 44, col: 0, row: 8, type: 'GAME_ZONE', tier: null, name: '花园剧场' },
        { index: 45, col: 0, row: 9, type: 'PROPERTY', tier: 'MID', name: '钟楼街' },
        { index: 46, col: 0, row: 10, type: 'PROPERTY', tier: 'LOW', name: '面包街' },
        { index: 47, col: 0, row: 11, type: 'STATION', tier: null, name: '钟楼站' },
        { index: 48, col: 0, row: 12, type: 'PROPERTY', tier: 'LOW', name: '糖果街' },
        { index: 49, col: 0, row: 13, type: 'PROPERTY', tier: 'MID', name: '花钟街' },
    ],
};

/** 后台发布的地名（按格子数）；没有时用设计稿地名。 */
const overrides: { [size: number]: string[] } = {};

export function boardNames(size: 30 | 50): string[] {
    return overrides[size] ?? (size === 30 ? DESIGN_30 : DESIGN_50).tiles.map((t) => t.name);
}

/** 用房间绑定的参数版本覆盖地名（地图 ID → 逐格名称）；长度不符的忽略。 */
export function applyServerNames(names: { [boardId: string]: string[] }): void {
    for (const id of Object.keys(names)) {
        const size = id === 'classic-30' ? 30 : id === 'classic-50' ? 50 : 0;
        if (size && names[id].length === size) overrides[size] = names[id].slice();
    }
}
