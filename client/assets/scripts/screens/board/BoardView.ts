/**
 * 环形棋盘视图：世界节点（格子/中心装饰/棋子）放在带 Mask 的视口内；支持拖动、双指/滚轮缩放、当前角色跟随、点击格子。
 * （v6 设计已取消右侧放大/缩小/定位按钮。）
 * 世界节点锚点左上，缩放以世界左上角为原点：屏幕点 = (vx + wx*s, vy + wy*s)。
 * 格子：上部小房屋/图标，下部三字地名（统一取自 core/BoardNames），色条与归属标记保留。
 * 棋子：我的棋子更大，带"我·昵称"气泡与发光底座；逐格跳跃由 hopTo() 按时间插值（BoardScreen 用时间戳驱动）。
 */
import { Label, Mask, Node, UITransform, Vec3, view } from 'cc';
import { axisCell, gridCell, gridFor, GridSpec, ringLength } from '../../core/BoardLayout';
import { BoardTile, GameView, PropertyState } from '../../core/Models';
import { Theme, textWidth } from '../../core/Theme';
import { drawHouse, drawPips } from '../../ui/Icons';
import { col, fillCircle, fillPoly, fillRR, gfx, line, mk, place, setOpacity, strokeRR, text } from '../../ui/Kit';
import { avatar } from '../../ui/Widgets';
import { drawEventDeck } from './EventDeck';
import { art, artRatio, characterKey } from '../../ui/Art';

export interface CamState { scale: number; vx: number; vy: number; follow: boolean; boardId: string }

interface Pt { x: number; y: number }
interface TouchLike { getUILocation(): Pt }
interface BoardEvt { getAllTouches(): TouchLike[]; getUIDelta(): Pt; getUILocation(): Pt; getScrollY?(): number }
interface Fx { node: Node; start: number; dur: number; kind: 'dust' | 'pulse' }
type Pose = 'crouch' | 'airborne' | 'land' | 'ready';
/** 棋子：idle 为站立立绘；跳跃时切换分镜 02 的四个姿态帧（同一 355×306 画布、脚底基线一致），在脚底支点做挤压拉伸。 */
interface Token {
    node: Node; s: number; offX: number; offY: number; avatar: number;
    body: Node; idle: Node | null; poses: Map<Pose, Node> | null; pose: Pose | null;
}
const POSE_CANVAS = { w: 355, h: 306, figure: 288, feet: 298 };
/** 视口在棋盘区上方多留的高度：站在最上一排的人物与"我"气泡会探出棋盘，不能被裁掉。 */
const HEADROOM = 50;

const OWNER_COLORS = Theme.avatarColors;

/** 地产格底部厚边：档位色 + 更深的底沿。 */
const TIER_BASE: Record<string, [string, string]> = {
    LOW: [Theme.c.tierLow, '#58B04A'],
    MID: [Theme.c.tierMid, '#2F7FCC'],
    HIGH: [Theme.c.tierHigh, '#8550E0'],
};

/** 定点移动（设计稿 13 第二张）：从 from 往前 1～6 格标号高亮，selected 为当前选中的步数。 */
export interface StepMarks { from: number; selected: number }

/**
 * 棋盘标号的共享入口：确认面板设置 current 并调用 redraw；最近一次绘制的棋盘负责画和点选。
 * 棋盘每次重建都会按 current 重画，所以面板开着时棋盘刷新标号也不丢。
 */
export const boardMarks = {
    current: null as StepMarks | null,
    redraw: null as (() => void) | null,
    /** 屏幕点（UI 坐标）落在哪个标号上：返回步数，没点中返回 0。 */
    hit: null as ((ui: Pt) => number) | null,
    /** 放大并平移棋盘，让标号露在面板上方（sheetTop 为面板上沿的屏幕 y）。 */
    reveal: null as ((sheetTop: number) => void) | null,
    /** 面板盖住视口底部的高度：相机允许多往上推这么多（重建棋盘时沿用）。 */
    cover: 0,
    /** 打开标号前的相机，关闭时还原。 */
    saved: null as CamState | null,
    /** 关闭标号：清掉标号、还原相机。 */
    clear(): void {
        this.current = null;
        this.cover = 0;
        this.redraw?.();
        this.restore?.();
        this.onToggle?.(false);
    },
    /** 棋盘页收起 / 恢复挡住标号的回合提示与骰子。 */
    onToggle: null as ((on: boolean) => void) | null,
    restore: null as (() => void) | null,
};

export class BoardView {
    readonly viewport: Node;
    private world!: Node;
    private g!: GridSpec;
    private fit = 1;
    private xs: number[] = [];
    private ys: number[] = [];
    /** 设计稿 01：世界节点等比显示（不再纵向拉伸），此值恒为 1，保留给美术等比换算。 */
    private verticalAspect = 1;
    /** 世界（棋盘）宽高：设计稿 01 的棋盘区 = 视口左右各留 16、上下共留 9。 */
    private ww = 0;
    private wh = 0;
    /** 本次绘制中有主地产的房子（格子画完后统一画到房屋层）。 */
    private houses: { key: string; x: number; y: number; base: number; isz: number; cw: number;
        dot: { x: number; y: number; r: number }; ownerColor: string | null }[] = [];
    cam: CamState;
    private pinchDist = 0;
    private moved = 0;
    private target: Pt | null = null;
    private tokens = new Map<string, Token>();
    private fxLayer: Node | null = null;
    private marksLayer: Node | null = null;
    private highlight: number | null = null;
    private fx: Fx[] = [];
    private cellToIndex = new Map<string, number>();
    onUserMove: (() => void) | null = null;
    onTileTap: ((index: number) => void) | null = null;

    constructor(parent: Node, x: number, private readonly top: number, readonly w: number, readonly h: number, cam: CamState | null) {
        const y = top;
        this.viewport = mk(parent, 'BoardViewport', x, y - HEADROOM, w, h + HEADROOM);
        const mask = this.viewport.addComponent(Mask);
        mask.type = Mask.Type.GRAPHICS_RECT;
        this.cam = cam ?? { scale: 0, vx: 0, vy: 0, follow: true, boardId: '' };
        const v = this.viewport;
        v.on(Node.EventType.TOUCH_START, () => {
            this.pinchDist = 0;
            this.moved = 0;
        }, this, true);
        v.on(Node.EventType.TOUCH_MOVE, (e: BoardEvt) => this.onMove(e), this, true);
        v.on(Node.EventType.TOUCH_END, (e: BoardEvt) => {
            if (this.moved <= 10 && this.pinchDist === 0 && e.getAllTouches().length <= 1) this.tapAt(e.getUILocation());
        }, this, true);
        v.on(Node.EventType.MOUSE_WHEEL, (e: BoardEvt) => {
            const sy = e.getScrollY ? e.getScrollY() : 0;
            this.zoomBy(sy > 0 ? 1.1 : 1 / 1.1);
        }, this);
    }

    /** 重绘整个世界（数据变化时调用；相机状态保留）。 */
    render(game: GameView, myId: string, myName: string, deckMode: 'fan' | 'single' = 'fan', highlight: number | null = null): void {
        this.highlight = highlight;
        if (this.world) {
            this.world.removeFromParent();
            this.world.destroy();
        }
        const size = game.tiles.length === 30 ? 30 : 50;
        // 设计稿 01：所有格子（含四角）同样大小、均匀铺满棋盘区，不放大四角、不拉伸
        const grid = gridFor(size);
        const ww = this.w - 32;
        const wh = this.h - 9;
        const px = ww / grid.cols;
        const py = wh / grid.rows;
        this.g = { cols: grid.cols, rows: grid.rows, tile: Math.min(px, py) };
        const g = this.g;
        this.xs = Array.from({ length: g.cols + 1 }, (_, i) => i * px);
        this.ys = Array.from({ length: g.rows + 1 }, (_, i) => i * py);
        this.ww = ww;
        this.wh = wh;
        this.world = mk(this.viewport, 'World', 0, 0, ww, wh);
        this.fit = 1;
        this.verticalAspect = 1;
        if (this.cam.boardId !== game.boardId || this.cam.scale <= 0) {
            this.cam = { scale: this.fit, vx: 0, vy: 0, follow: this.cam.follow, boardId: game.boardId };
            this.centerOn(ww / 2, wh / 2, false);
        }
        // 设计稿 01：格子环下面一圈石板色边框
        const frame = gfx(mk(this.world, 'Frame', -8, -8, ww + 16, wh + 16));
        fillRR(frame, 0, 0, ww + 16, wh + 16, 20, '#B9AE9B');
        fillRR(frame, 2, 2, ww + 12, wh + 12, 18, '#DDD6C7');
        this.drawCenter(ww, wh);
        const tilesNode = mk(this.world, 'Tiles', 0, 0, ww, wh);
        this.cellToIndex.clear();
        this.houses = [];
        for (const t of game.tiles) {
            const c = gridCell(t.index, g);
            this.cellToIndex.set(c.col + ',' + c.row, t.index);
            this.drawTile(tilesNode, t, game);
        }
        this.drawHouses(mk(this.world, 'Houses', 0, 0, ww, wh));
        // 事件牌堆：棋盘内圈左上角展开的三张卡（设计稿 01：约 250×172，含"事件卡"木牌），在格子之上、棋子之下
        drawEventDeck(this.world, this.xs[1] + 29, this.ys[1] + 18, 250, 172, deckMode, 1);
        this.tokens.clear();
        const tokens = mk(this.world, 'Tokens', 0, 0, ww, wh);
        this.drawTokens(tokens, game, myId, myName);
        this.marksLayer = mk(this.world, 'StepMarks', 0, 0, ww, wh);
        this.fxLayer = mk(this.world, 'Fx', 0, 0, ww, wh);
        this.fx = [];
        boardMarks.redraw = () => this.drawMarks();
        boardMarks.hit = (ui) => this.markAt(ui);
        boardMarks.reveal = (sheetTop) => this.revealMarks(sheetTop);
        boardMarks.restore = () => this.restoreCam();
        this.drawMarks();
        this.apply();
    }

    /** 定点移动标号：前方 1～6 格各一个黄色数字牌、格子描黄边；选中的一格换成蓝色。 */
    private drawMarks(): void {
        const layer = this.marksLayer;
        if (!layer || !layer.isValid) return;
        layer.destroyAllChildren();
        const m = boardMarks.current;
        if (!m) return;
        const n = ringLength(this.g);
        const node = mk(layer, 'Marks', 0, 0, this.ww, this.wh);
        const gg = gfx(node);
        for (let k = 1; k <= 6; k++) {
            const idx = (m.from + k) % n;
            const c = gridCell(idx, this.g);
            const x = this.xs[c.col];
            const y = this.ys[c.row];
            const cw = this.xs[c.col + 1] - x;
            const ch = this.ys[c.row + 1] - y;
            const sel = k === m.selected;
            fillRR(gg, x + 2, y + 2, cw - 4, ch - 4, 10, sel ? '#4DA3F04D' : '#FFD64640');
            strokeRR(gg, x + 2, y + 2, cw - 4, ch - 4, 10, sel ? Theme.c.blue : Theme.c.yellow, sel ? 4 : 3);
            // 数字牌放在图标区（格子上半部），下部地名不被挡住
            const r = Math.max(12, Math.min(cw, ch) * 0.22);
            const cx = x + cw / 2;
            const cy = y + ch * 0.3;
            fillCircle(gg, cx, cy + 3, r, '#00000033');
            fillCircle(gg, cx, cy, r, sel ? '#2F7FD0' : Theme.c.yellowDark);
            fillCircle(gg, cx, cy - 2, r - 3, sel ? '#4DA3F0' : '#FFC93C');
            text(node, String(k), cx - r, cy - r - 2, r * 2, r * 2, Math.round(r * 1.2), Theme.c.white, { bold: true });
        }
    }

    /** 放大到约 1.5 倍、把当前位置和前方 6 格的中心移到面板上方可见区的中间（设计稿 13）。 */
    private revealMarks(sheetTop: number): void {
        const m = boardMarks.current;
        if (!m || !this.world) return;
        if (!boardMarks.saved) boardMarks.saved = { ...this.cam };
        boardMarks.cover = Math.max(0, this.top + this.h - sheetTop);
        this.cam.follow = false;
        const n = ringLength(this.g);
        const pts = Array.from({ length: 7 }, (_, k) => this.tileCenter((m.from + k) % n));
        const minX = Math.min(...pts.map((p) => p.x));
        const maxX = Math.max(...pts.map((p) => p.x));
        const minY = Math.min(...pts.map((p) => p.y));
        const maxY = Math.max(...pts.map((p) => p.y));
        const visH = this.h - boardMarks.cover;
        const t = this.g.tile;
        const fitS = Math.min((this.w - 40) / (maxX - minX + t * 1.6), (visH - 40) / (maxY - minY + t * 1.6));
        const s = Math.max(this.fit, Math.min(1.6, fitS));
        this.cam.scale = s;
        this.cam.vx = this.w / 2 - ((minX + maxX) / 2) * s;
        this.cam.vy = visH / 2 - ((minY + maxY) / 2) * s;
        this.target = null;
        this.apply();
    }

    private restoreCam(): void {
        const saved = boardMarks.saved;
        boardMarks.saved = null;
        if (!saved || !this.world) return;
        Object.assign(this.cam, saved);
        this.target = null;
        this.apply();
    }

    /** UI 坐标 → 标号步数（0 = 没点中标号）。 */
    private markAt(ui: Pt): number {
        const m = boardMarks.current;
        const idx = this.tileAtUI(ui);
        if (!m || idx === undefined) return 0;
        const n = ringLength(this.g);
        const k = (idx - m.from + n) % n;
        return k >= 1 && k <= 6 ? k : 0;
    }

    private drawCenter(ww: number, wh: number): void {
        const t = this.g.tile;
        const border = this.xs[1];
        const innerW = ww - border * 2;
        const innerH = wh - border * 2;
        const n = mk(this.world, 'Center', border, border, innerW, innerH);
        const gg = gfx(n);
        // 小镇底图等比铺满（cover）并裁到内圈：世界节点纵向有拉伸，按"显示比例"算图框，原图不再被压扁 / 拉宽
        const ratio = artRatio('board_town');
        if (ratio) {
            const va = this.verticalAspect;
            const dh = Math.max(innerW / ratio, innerH * va); // 显示高度
            const lw = dh * ratio;
            const lh = dh / va;
            const clip = mk(n, 'TownClip', 0, 0, innerW, innerH);
            const mask = clip.addComponent(Mask);
            mask.type = Mask.Type.GRAPHICS_RECT;
            if (art(clip, 'board_town', (innerW - lw) / 2, (innerH - lh) / 2, lw, lh, 'stretch')) return;
            clip.destroy();
        }
        fillRR(gg, 0, 0, ww - t, wh - t, 26, '#BFE8A3');
        fillRR(gg, 8, 8, ww - t - 16, wh - t - 16, 20, '#A9DC8B');
        for (let i = 0; i < 16; i++) {
            const x = 30 + ((i * 97) % (ww - t - 60));
            const y = 30 + ((i * 151) % (wh - t - 60));
            fillCircle(gg, x, y, t * 0.22, '#5DB45A');
            fillCircle(gg, x - 3, y - 4, t * 0.15, '#78CC6E');
        }
    }

    /**
     * 立体房子：按原图比例画在所有格子之上，比格内图标大，底边落在图标区下沿、向上探出格子。
     * 从上到下、从左到右排序后绘制，下方格子的房子盖住上方的，前后关系自然；不超出棋盘边缘。
     * 归属色点画在所有房子之上（原来的位置会被房子挡住）。
     */
    private drawHouses(layer: Node): void {
        const list = this.houses.slice().sort((a, b) => a.y === b.y ? a.x - b.x : a.y - b.y);
        for (const h of list) {
            const ratio = artRatio(h.key);
            if (!ratio) continue;
            // 约 1.35 个图标高，宽不超过格子宽的 1.08 倍；顶部不超出棋盘上沿
            let dh = Math.min(Math.max(h.isz * 1.35, h.cw * 0.9 / ratio), h.base - 2);
            if (dh * ratio > h.cw * 1.08) dh = h.cw * 1.08 / ratio;
            const lw = dh * ratio;
            const x = Math.max(0, Math.min(this.ww - lw, h.x - lw / 2));
            art(layer, h.key, x, h.base - dh, lw, dh, 'stretch');
        }
        const dots = gfx(mk(layer, 'OwnerDots', 0, 0, this.ww, this.wh));
        for (const h of list) {
            if (!h.ownerColor) continue;
            fillCircle(dots, h.dot.x, h.dot.y, h.dot.r, '#FFFFFF');
            fillCircle(dots, h.dot.x, h.dot.y, h.dot.r * 0.75, h.ownerColor);
        }
    }

    private facing(col0: number, row: number): 'top' | 'bottom' | 'left' | 'right' {
        const g = this.g;
        if (row === g.rows - 1) return 'top';
        if (row === 0) return 'bottom';
        if (col0 === 0) return 'right';
        return 'left';
    }

    /**
     * 设计稿 01 的立体格子：白色顶面（事件格浅黄）+ 底部档位色厚边（特殊格浅灰）+ 投影；上部图标、下部地名（深蓝粗体）。
     * 直接按格子实际宽高绘制（不再缩放格子节点，图标与文字都不变形）。
     */
    private drawTile(parent: Node, tile: BoardTile, game: GameView): void {
        const cell = gridCell(tile.index, this.g);
        const x = this.xs[cell.col];
        const y = this.ys[cell.row];
        const cw = this.xs[cell.col + 1] - x;
        const ch = this.ys[cell.row + 1] - y;
        const n = mk(parent, 'Tile' + tile.index, x, y, cw, ch);
        const gg = gfx(n);
        const m = Math.max(1.5, Math.round(cw * 0.03));
        const bw = cw - 2 * m;
        const bh = ch - 2 * m;
        const r = Math.min(10, bw * 0.16);
        const band = Math.max(6, Math.round(bh * 0.2));
        const isProp = tile.type === 'PROPERTY';
        const prop = game.properties.find((p) => p.tileIndex === tile.index);
        const face = tile.type === 'EVENT' ? '#FFF4D8' : '#FFFFFF';
        const [baseColor, baseDark] = isProp ? TIER_BASE[tile.tier ?? 'LOW'] : ['#D6DCE4', '#B4BCC8'];
        fillRR(gg, m, m + 3, bw, bh, r, '#00000026');
        fillRR(gg, m, m, bw, bh, r, baseDark);
        fillRR(gg, m, m, bw, bh - 3, r, baseColor);
        const fh = bh - band;
        fillRR(gg, m, m, bw, fh, r, face);
        if (this.highlight === tile.index) {
            fillRR(gg, m - 1, m - 1, bw + 2, bh + 2, r + 1, '#FFD64655');
            strokeRR(gg, m, m, bw, fh, r, Theme.c.yellow, 3);
        }
        if (tile.auctionLot) fillCircle(gg, m + bw - 8, m + 8, 5, Theme.c.red);
        // 下部地名、上部图标
        const nameH = Math.round(fh * 0.34);
        this.drawTileName(n, tile.name, m + 2, m + fh - nameH - 1, bw - 4, nameH);
        const iconTop = m + fh * 0.06;
        const iconH = fh - nameH - fh * 0.06;
        const isz = Math.min(bw * 0.84, iconH);
        const icx = m + bw / 2;
        const icy = iconTop + iconH / 2;
        const iconKey = isProp ? 'house_lv' + (prop?.level ?? 0)
            : ({ BANK: 'icon_bank', JAIL: 'icon_jail', GAME_ZONE: 'croc_open', START: 'icon_start_flag' } as Record<string, string>)[tile.type];
        const housed = isProp && !!prop?.owner && !prop.mortgaged && !!iconKey && !!artRatio(iconKey);
        if (housed) {
            // 有主地产：立体房子画在格子之上的房屋层（更大，向上探出格子）
            this.houses.push({ key: iconKey, x: x + icx, y, base: y + icy + isz * 0.5, isz, cw,
                dot: { x: x + m + 8, y: y + m + 8, r: Math.max(5, cw * 0.1) }, ownerColor: this.ownerColor(prop, game) });
        } else if (!iconKey || !art(n, iconKey, icx - isz / 2, icy - isz / 2, isz, isz)) {
            this.drawIcon(gg, tile, prop, icx, icy, isz);
        }
        if (isProp && prop && prop.owner) {
            if (!housed) this.drawOwnerDot(gg, prop, game, m + 8, m + 8);
            if (prop.mortgaged) {
                fillRR(gg, m, m, bw, fh, r, '#4A556099');
                text(n, '押', m, m, bw, fh - nameH, Math.round(Math.min(bw, fh) * 0.4), Theme.c.white, { bold: true });
            }
        }
        if (tile.type === 'STATION') this.drawOwnerDot(gg, prop, game, m + bw - 9, m + 9);
        // 路障（设计稿 13）：格子右上角的路障图标
        if ((game.roadblocks ?? []).includes(tile.index)) {
            const rs = Math.max(18, Math.round(bw * 0.46));
            art(n, 'icon_roadblock', m + bw - rs + 2, m - 4, rs, rs);
        }
    }

    /** 地名：按格子宽度自适应字号，2 倍栅格再缩回，小字也清楚。 */
    private drawTileName(parent: Node, name: string, x: number, y: number, w: number, h: number): void {
        const font = Math.max(9, Math.floor(Math.min(h * 0.82, (w - 2) / (textWidth(name, 1) + 0.1))));
        const density = 2;
        const label = text(parent, name, x, y, w * density, h * density, font * density, Theme.c.navy, { bold: true });
        label.overflow = Label.Overflow.CLAMP;
        label.node.setScale(1 / density, 1 / density, 1);
    }

    private drawIcon(gg: ReturnType<typeof gfx>, tile: BoardTile, prop: PropertyState | undefined, cx: number, cy: number, s: number): void {
        switch (tile.type) {
            case 'PROPERTY': {
                const owned = !!(prop && prop.owner);
                drawHouse(gg, cx, cy, s * 0.95, owned ? Theme.c.red : '#B9C2CC', owned ? Theme.c.ivory : '#F1F1EC');
                const lv = prop ? prop.level : 0;
                for (let i = 0; i < lv; i++) fillCircle(gg, cx + s * 0.36 - i * s * 0.16, cy - s * 0.42, s * 0.07, Theme.c.yellow);
                break;
            }
            case 'STATION':
                fillRR(gg, cx - s * 0.4, cy - s * 0.3, s * 0.8, s * 0.55, 6, Theme.c.station);
                fillRR(gg, cx - s * 0.28, cy - s * 0.2, s * 0.56, s * 0.22, 3, '#BFE3FF');
                fillCircle(gg, cx - s * 0.2, cy + s * 0.32, s * 0.08, '#333');
                fillCircle(gg, cx + s * 0.2, cy + s * 0.32, s * 0.08, '#333');
                break;
            case 'EVENT':
                // 设计稿 01：橙红圆底 + 白色问号
                fillCircle(gg, cx, cy, s * 0.4, '#F2663A');
                text(gg.node, '?', cx - s * 0.4, cy - s * 0.42, s * 0.8, s * 0.8, Math.round(s * 0.56), Theme.c.white, { bold: true });
                break;
            case 'BANK':
                fillCircle(gg, cx, cy, s * 0.4, Theme.c.yellow);
                fillCircle(gg, cx, cy, s * 0.28, '#FFE58A');
                fillRR(gg, cx - s * 0.04, cy - s * 0.2, s * 0.08, s * 0.4, 2, Theme.c.yellowDark);
                break;
            case 'JAIL':
                fillRR(gg, cx - s * 0.4, cy - s * 0.38, s * 0.8, s * 0.76, 4, '#6B7480');
                for (let i = 0; i < 4; i++) line(gg, cx - s * 0.26 + i * s * 0.17, cy - s * 0.34, cx - s * 0.26 + i * s * 0.17, cy + s * 0.34, '#E8ECF0', 2.5);
                break;
            case 'REST':
                fillCircle(gg, cx - s * 0.14, cy - s * 0.08, s * 0.26, '#5DB45A');
                fillCircle(gg, cx + s * 0.18, cy + s * 0.02, s * 0.22, '#78CC6E');
                fillRR(gg, cx - s * 0.05, cy + s * 0.12, s * 0.1, s * 0.26, 2, '#8B6B3A');
                break;
            case 'GAME_ZONE':
                fillRR(gg, cx - s * 0.36, cy - s * 0.36, s * 0.72, s * 0.72, 10, Theme.c.white);
                strokeRR(gg, cx - s * 0.36, cy - s * 0.36, s * 0.72, s * 0.72, 10, Theme.c.purple, 2.5);
                drawPips(gg, cx - s * 0.36, cy - s * 0.36, s * 0.72, 5, Theme.c.purple);
                break;
            case 'START':
                fillPoly(gg, [[cx - s * 0.36, cy - s * 0.18], [cx + s * 0.04, cy - s * 0.18], [cx + s * 0.04, cy - s * 0.36], [cx + s * 0.4, cy], [cx + s * 0.04, cy + s * 0.36], [cx + s * 0.04, cy + s * 0.18], [cx - s * 0.36, cy + s * 0.18]], Theme.c.green);
                break;
        }
    }

    private drawOwnerDot(gg: ReturnType<typeof gfx>, prop: PropertyState | undefined, game: GameView, x: number, y: number): void {
        const color = this.ownerColor(prop, game);
        if (!color) return;
        fillCircle(gg, x, y, 8, '#FFFFFF');
        fillCircle(gg, x, y, 6, color);
    }

    private ownerColor(prop: PropertyState | undefined, game: GameView): string | null {
        if (!prop || !prop.owner) return null;
        const owner = game.players.find((p) => p.playerId === prop.owner);
        return owner ? OWNER_COLORS[owner.avatar % 8] : null;
    }

    /** 某格在棋盘上的宽高（四角大格、两侧扁格、上下窄格不一样）。 */
    private cellSize(index: number): { w: number; h: number } {
        const c = gridCell(index % ringLength(this.g), this.g);
        return { w: this.xs[c.col + 1] - this.xs[c.col], h: this.ys[c.row + 1] - this.ys[c.row] };
    }

    /** 棋盘内圈（格子环以内）在父节点坐标中的矩形（事件抽卡时压暗这一块）。 */
    innerRect(): { x: number; y: number; w: number; h: number } {
        const s = this.cam.scale;
        const vp = this.viewport.position;
        const x0 = vp.x + this.cam.vx + this.xs[1] * s;
        const y0 = -vp.y + HEADROOM + this.cam.vy + this.ys[1] * s;
        return { x: x0, y: y0, w: (this.xs[this.xs.length - 2] - this.xs[1]) * s, h: (this.ys[this.ys.length - 2] - this.ys[1]) * s };
    }

    tileCenter(index: number): Pt {
        const c = gridCell(index % ringLength(this.g), this.g);
        return { x: (this.xs[c.col] + this.xs[c.col + 1]) / 2,
            y: (this.ys[c.row] + this.ys[c.row + 1]) / 2 };
    }

    // ---------- 棋子 ----------
    private drawTokens(parent: Node, game: GameView, myId: string, myName: string): void {
        const t = this.g.tile;
        const seen: Record<number, number> = {};
        // 我的人物站在发光底座上、比其他人略大；立绘高约 1.5 倍节点边长（用户反馈原先 1 格偏大，收小到约 0.7 格）
        const sOther = Math.round(t * 0.56);
        const sMe = Math.round(t * 0.72);
        const order = game.players.slice().sort((a, b) => (a.playerId === myId ? 1 : 0) - (b.playerId === myId ? 1 : 0)); // 我最后画，盖在最上
        for (const p of order) {
            if (p.life !== 'ALIVE') continue;
            const k = seen[p.position] ?? 0;
            seen[p.position] = k + 1;
            const c = this.tileCenter(p.position);
            const me = p.playerId === myId;
            // 按格子大小收窄人物，脚踩在格子中心略偏下（人物立绘的脚在节点顶 + s 处，见下方 art 的摆放）
            const cell = this.cellSize(p.position);
            const fit = Math.min(cell.w, cell.h);
            const s = Math.round(me ? sMe : Math.min(sOther, fit * 0.7));
            const offX = me ? 0 : ((k % 3) - 1) * s * 0.45;
            const offY = (me ? 0 : Math.floor(k / 3) * s * 0.3) + t * 0.14 - s / 2;
            const n = mk(parent, 'Token:' + p.playerId, c.x + offX - s / 2, c.y + offY - s / 2, s, s);
            const gg = gfx(n);
            const cur = p.playerId === game.currentPlayer;
            if (me) {
                // 发光底座环
                gg.fillColor = col('#4DA3F046');
                gg.ellipse(s / 2, -(s * 0.92), s * 0.78, s * 0.26);
                gg.fill();
                gg.fillColor = col('#4DA3F0');
                gg.ellipse(s / 2, -(s * 0.92), s * 0.5, s * 0.17);
                gg.fill();
                gg.fillColor = col('#FFFFFFC8');
                gg.ellipse(s / 2, -(s * 0.92), s * 0.34, s * 0.11);
                gg.fill();
            }
            // 支点节点放在脚底：挤压拉伸以脚为中心，不会"浮起来"
            const body = mk(n, 'Body', s / 2, s, 0, 0);
            const idle = art(body, characterKey(p.avatar, true), -s / 2, -s * 1.5, s, s * 1.5, 'contain', false, { bottom: true });
            if (!idle) avatar(n, 0, 0, s, p.avatar, p.nickname, { ring: me ? Theme.c.blue : cur ? Theme.c.yellow : undefined });
            if (p.inJail) this.drawBars(n, s);
            if (me) this.drawBubble(n, s, '我·' + myName);
            this.tokens.set(p.playerId, { node: n, s, offX, offY, avatar: p.avatar, body, idle, poses: null, pose: null });
        }
    }

    /** 在监狱里：人物前面一排铁栏杆。 */
    private drawBars(token: Node, s: number): void {
        const b = mk(token, 'JailBars', -s * 0.08, -s * 0.42, s * 1.16, s * 1.42);
        const g = gfx(b);
        const w = s * 1.16;
        const h = s * 1.42;
        fillRR(g, 0, 0, w, s * 0.1, 3, '#4A5260');
        fillRR(g, 0, h - s * 0.1, w, s * 0.1, 3, '#4A5260');
        for (let i = 0; i < 5; i++) {
            const x = w * (0.1 + i * 0.2);
            line(g, x, s * 0.06, x, h - s * 0.06, '#3A404C', Math.max(3, s * 0.07));
            line(g, x - s * 0.012, s * 0.08, x - s * 0.012, h - s * 0.08, '#C9D1DB', Math.max(1, s * 0.02));
        }
    }

    private drawBubble(token: Node, s: number, label: string): void {
        const fs = Math.max(13, Math.round(this.g.tile * 0.22));
        const bw = textWidth(label, fs) + 22;
        const bh = fs + 12;
        const b = mk(token, 'Bubble', s / 2 - bw / 2, -bh - 10 - s * 0.1, bw, bh);
        const g = gfx(b);
        fillRR(g, 0, 2, bw, bh, bh / 2, '#00000030');
        fillRR(g, 0, 0, bw, bh, bh / 2, Theme.c.blueDark);
        fillPoly(g, [[bw / 2 - 6, bh - 1], [bw / 2 + 6, bh - 1], [bw / 2, bh + 7]], Theme.c.blueDark);
        text(b, label, 0, 0, bw, bh, fs, Theme.c.white, { bold: true });
    }

    /**
     * 逐格跳跃（分镜 02：蓄力 → 起跳腾空 → 落地 → 回弹），k∈[0,1) 为这一步内的进度（由时间戳算出）。
     * 位置全程连续：蓄力时原地压低、腾空沿弧线平滑过去、落地挤压再回弹；抬升 0.16 格（用户修正值）。
     */
    hopTo(id: string, a: number, b: number, k: number): Pt | null {
        const tk = this.tokens.get(id);
        if (!tk) return null;
        const A = this.tileCenter(a);
        const B = this.tileCenter(b);
        const p = Math.max(0, Math.min(1, k));
        // 原地停顿缩短（蓄力 8%、落地 12%，不再有站定阶段），连续多步时更连贯
        const CROUCH = 0.08;
        const AIR = 0.88;
        const LAND = 1;
        let u = 0;
        let lift = 0;
        let sx = 1;
        let sy = 1;
        let pose: Pose;
        if (p < CROUCH) {
            const q = p / CROUCH;
            pose = 'crouch';
            sx = 1 + 0.06 * q;
            sy = 1 - 0.08 * q;
        } else if (p < AIR) {
            const q = (p - CROUCH) / (AIR - CROUCH);
            pose = 'airborne';
            // 线性与缓入缓出各半：每格起落不再"停-走-停"
            u = 0.5 * q + 0.5 * (0.5 - 0.5 * Math.cos(Math.PI * q));
            const arc = Math.sin(Math.PI * q);
            lift = this.g.tile * 0.16 * arc;
            sx = 1 + 0.06 * (1 - q) - 0.04 * arc;
            sy = 1 - 0.08 * (1 - q) + 0.06 * arc;
        } else if (p < LAND) {
            const q = (p - AIR) / (LAND - AIR);
            pose = 'land';
            u = 1;
            sx = 1 + 0.07 * Math.sin(Math.PI * q);
            sy = 1 - 0.09 * Math.sin(Math.PI * q);
        } else {
            pose = 'ready';
            u = 1;
        }
        const x = A.x + (B.x - A.x) * u;
        const y = A.y + (B.y - A.y) * u;
        place(tk.node, x + tk.offX - tk.s / 2, y + tk.offY - tk.s / 2 - lift);
        tk.body.setScale(sx, sy, 1);
        this.showPose(tk, pose);
        return { x, y };
    }

    /** 走完：回到站立立绘、取消挤压。 */
    endHop(id: string): void {
        const tk = this.tokens.get(id);
        if (!tk) return;
        tk.body.setScale(1, 1, 1);
        this.showPose(tk, null);
    }

    /** 切换姿态帧：按站立立绘的人物高度缩放画布，脚底对齐支点；素材未到时保持站立立绘。 */
    private showPose(tk: Token, pose: Pose | null): void {
        if (tk.pose === pose) return;
        if (pose && !tk.poses) {
            const name = characterKey(tk.avatar, true);
            const poses = new Map<Pose, Node>();
            // 站立立绘带蓝色底座：人物（发顶到脚底）约占图高 83%，脚踩在离图底约 15% 处；姿态帧按人物身高缩放并对齐脚底
            const imgH = tk.s * 1.5;
            const scale = (imgH * 0.834) / POSE_CANVAS.figure;
            const feetUp = imgH * 0.15;
            const cw = POSE_CANVAS.w * scale;
            const chh = POSE_CANVAS.h * scale;
            for (const ps of ['crouch', 'airborne', 'land', 'ready'] as Pose[]) {
                const n = art(tk.body, name + '_' + ps, -cw / 2, -(POSE_CANVAS.feet * scale + feetUp), cw, chh, 'stretch');
                if (!n) return; // 姿态帧还没加载完：本次仍用站立立绘
                n.active = false;
                poses.set(ps, n);
            }
            tk.poses = poses;
        }
        tk.pose = pose;
        if (tk.idle) tk.idle.active = !pose || !tk.poses;
        tk.poses?.forEach((n, ps) => { n.active = ps === pose; });
    }

    /** 落地尘土（在格 index 上）。 */
    dust(index: number): void {
        if (!this.fxLayer) return;
        const c = this.tileCenter(index);
        const n = mk(this.fxLayer, 'Dust', c.x - 20, c.y + this.g.tile * 0.2, 40, 20);
        const g = gfx(n);
        fillCircle(g, 8, 10, 7, '#FFFFFFCC');
        fillCircle(g, 20, 8, 9, '#FFFFFFCC');
        fillCircle(g, 32, 10, 7, '#FFFFFFCC');
        this.fx.push({ node: n, start: Date.now(), dur: 320, kind: 'dust' });
    }

    /** 到达格的"我"光圈强调。 */
    pulse(index: number): void {
        if (!this.fxLayer) return;
        const c = this.tileCenter(index);
        const r = this.g.tile * 0.9;
        const n = mk(this.fxLayer, 'Pulse', c.x - r, c.y - r, 2 * r, 2 * r);
        const g = gfx(n);
        g.strokeColor = col('#FFD646');
        g.lineWidth = 5;
        g.circle(r, -r, r * 0.6);
        g.stroke();
        this.fx.push({ node: n, start: Date.now(), dur: Theme.anim.pulseMs, kind: 'pulse' });
    }

    private tickFx(): void {
        const now = Date.now();
        this.fx = this.fx.filter((f) => {
            const k = (now - f.start) / f.dur;
            if (k >= 1) {
                f.node.removeFromParent();
                f.node.destroy();
                return false;
            }
            setOpacity(f.node, Math.round(255 * (1 - k)));
            const s = f.kind === 'pulse' ? 0.8 + 0.8 * k : 1 + 0.3 * k;
            f.node.setScale(s, s, 1);
            return true;
        });
    }

    // ---------- 点击格子 ----------
    private tapAt(ui: Pt): void {
        if (!this.world || !this.onTileTap) return;
        const idx = this.tileAtUI(ui);
        if (idx !== undefined) this.onTileTap(idx);
    }

    private tileAtUI(ui: Pt): number | undefined {
        if (!this.world || !this.world.isValid) return undefined;
        const ut = this.world.getComponent(UITransform) as UITransform;
        // UI 坐标原点在可见区左下；本工程 Canvas 位于世界原点（居中），故减去可见区一半换算成世界坐标
        const vs = view.getVisibleSize();
        const local = ut.convertToNodeSpaceAR(new Vec3(ui.x - vs.width / 2, ui.y - vs.height / 2, 0));
        const col0 = axisCell(this.xs, local.x);
        const row = axisCell(this.ys, -local.y);
        return this.cellToIndex.get(col0 + ',' + row);
    }

    // ---------- 相机 ----------
    private apply(): void {
        const s = this.cam.scale;
        const ww = this.ww * s;
        const wh = this.wh * s;
        const clamp = (v: number, vp: number, size: number) => (size <= vp ? (vp - size) / 2 : Math.max(vp - size - 40, Math.min(40, v)));
        this.cam.vx = clamp(this.cam.vx, this.w, ww);
        // 定点移动面板盖住视口底部时，允许把棋盘多往上推，底边一排也能露出来
        const cover = boardMarks.current ? boardMarks.cover : 0;
        this.cam.vy = cover > 0 ? Math.max(this.h - cover - wh - 40, Math.min(40, this.cam.vy)) : clamp(this.cam.vy, this.h, wh);
        this.world.setScale(s, s, 1);
        place(this.world, this.cam.vx, this.cam.vy + HEADROOM);
    }

    private centerOn(wx: number, wy: number, animate: boolean): void {
        const s = this.cam.scale;
        const vx = this.w / 2 - wx * s;
        const vy = this.h / 2 - wy * s;
        if (animate) this.target = { x: vx, y: vy };
        else {
            this.cam.vx = vx;
            this.cam.vy = vy;
            this.target = null;
        }
    }

    focusTile(index: number, animate = true): void {
        if (!this.world) return;
        const c = this.tileCenter(index);
        this.centerOn(c.x, c.y, animate);
        if (!animate) this.apply();
    }

    /** 跟随某个世界坐标点（仅在跟随开启时）。 */
    followPoint(p: Pt): void {
        if (this.cam.follow && this.world) this.centerOn(p.x, p.y, true);
    }

    zoomBy(f: number): void {
        const old = this.cam.scale;
        const s = Math.max(this.fit, Math.min(2.6, old * f));
        const cx = this.w / 2;
        const cy = this.h / 2;
        this.cam.vx = cx - ((cx - this.cam.vx) / old) * s;
        this.cam.vy = cy - ((cy - this.cam.vy) / old) * s;
        this.cam.scale = s;
        this.target = null;
        if (this.world) this.apply();
    }

    private onMove(e: BoardEvt): void {
        const ts = e.getAllTouches();
        if (ts.length >= 2) {
            const a = ts[0].getUILocation();
            const b = ts[1].getUILocation();
            const d = Math.hypot(a.x - b.x, a.y - b.y);
            if (this.pinchDist > 0 && d > 0) this.zoomBy(d / this.pinchDist);
            this.pinchDist = d;
            this.userMoved();
            return;
        }
        const dl = e.getUIDelta();
        this.moved += Math.abs(dl.x) + Math.abs(dl.y);
        if (this.moved > 10 && this.world) {
            this.cam.vx += dl.x;
            this.cam.vy -= dl.y;
            this.target = null;
            this.apply();
            this.userMoved();
        }
    }

    private userMoved(): void {
        if (this.cam.follow) {
            this.cam.follow = false;
            this.onUserMove?.();
        }
    }

    tick(dt: number): void {
        this.tickFx();
        if (!this.target || !this.world) return;
        const k = Math.min(1, dt * 7);
        this.cam.vx += (this.target.x - this.cam.vx) * k;
        this.cam.vy += (this.target.y - this.cam.vy) * k;
        if (Math.abs(this.target.x - this.cam.vx) < 0.5 && Math.abs(this.target.y - this.cam.vy) < 0.5) this.target = null;
        this.apply();
    }
}
