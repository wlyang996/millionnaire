/**
 * 环形棋盘视图：世界节点（格子/中心装饰/棋子）放在带 Mask 的视口内；支持拖动、双指/滚轮缩放、当前角色跟随、点击格子。
 * （v6 设计已取消右侧放大/缩小/定位按钮。）
 * 世界节点锚点左上，缩放以世界左上角为原点：屏幕点 = (vx + wx*s, vy + wy*s)。
 * 格子：上部小房屋/图标，下部三字地名（统一取自 core/BoardNames），色条与归属标记保留。
 * 棋子：我的棋子更大，带"我·昵称"气泡与发光底座；逐格跳跃由 hopTo() 按时间插值（BoardScreen 用时间戳驱动）。
 */
import { Label, Mask, Node, UITransform, Vec3, view } from 'cc';
import { axisCell, boardAxis, eventDeckRect, gridCell, gridFor, GridSpec, ringLength } from '../../core/BoardLayout';
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

const OWNER_COLORS = Theme.avatarColors;

export class BoardView {
    readonly viewport: Node;
    private world!: Node;
    private g!: GridSpec;
    private fit = 1;
    private xs: number[] = [];
    private ys: number[] = [];
    /** Fill the portrait viewport with rectangular tiles while retaining the exact ring/grid data. */
    private verticalAspect = 1;
    /** 本次绘制中有主地产的房子（格子画完后统一画到房屋层）。 */
    private houses: { key: string; n: Node; icx: number; icy: number; isz: number;
        dot: { x: number; y: number }; ownerColor: string | null }[] = [];
    cam: CamState;
    private pinchDist = 0;
    private moved = 0;
    private target: Pt | null = null;
    private tokens = new Map<string, { node: Node; s: number; offX: number; offY: number }>();
    private fxLayer: Node | null = null;
    private highlight: number | null = null;
    private fx: Fx[] = [];
    private cellToIndex = new Map<string, number>();
    onUserMove: (() => void) | null = null;
    onTileTap: ((index: number) => void) | null = null;

    constructor(parent: Node, x: number, y: number, readonly w: number, readonly h: number, cam: CamState | null) {
        this.viewport = mk(parent, 'BoardViewport', x, y, w, h);
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
        this.g = gridFor(size);
        const g = this.g;
        this.xs = boardAxis(g.cols, g.tile);
        this.ys = boardAxis(g.rows, g.tile);
        const ww = g.cols * g.tile;
        const wh = g.rows * g.tile;
        this.world = mk(this.viewport, 'World', 0, 0, ww, wh);
        this.fit = this.w / ww;
        this.verticalAspect = this.h / (wh * this.fit);
        if (this.cam.boardId !== game.boardId || this.cam.scale <= 0) {
            this.cam = { scale: this.fit, vx: 0, vy: 0, follow: this.cam.follow, boardId: game.boardId };
            this.centerOn(ww / 2, wh / 2, false);
        }
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
        // 事件牌堆：棋盘内圈左上角的装饰（design/screens/11、10），在格子之上、棋子之下
        const dr = eventDeckRect(g, this.fit);
        dr.x += this.xs[1] - g.tile;
        dr.y += this.ys[1] - g.tile;
        drawEventDeck(this.world, dr.x, dr.y, dr.w, dr.h, deckMode, this.verticalAspect);
        this.tokens.clear();
        const tokens = mk(this.world, 'Tokens', 0, 0, ww, wh);
        this.drawTokens(tokens, game, myId, myName);
        this.fxLayer = mk(this.world, 'Fx', 0, 0, ww, wh);
        this.fx = [];
        this.apply();
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
        const va = this.verticalAspect;
        const ww = this.g.cols * this.g.tile;
        const list = this.houses.slice().sort((a, b) => a.n.position.y === b.n.position.y
            ? a.n.position.x - b.n.position.x : b.n.position.y - a.n.position.y);
        for (const h of list) {
            const ratio = artRatio(h.key);
            if (!ratio) continue;
            const sx = h.n.scale.x;
            const sy = h.n.scale.y;
            const baseX = h.n.position.x + h.icx * sx;
            const baseY = -h.n.position.y + (h.icy + h.isz / 2 + h.isz * 0.08) * sy;
            // 显示尺寸：约 1.35 个图标高，宽不超过格子显示宽度的 1.08 倍；顶部不超出棋盘上沿
            const cellW = this.g.tile * sx;
            const iconH = h.isz * sy * va;
            let dh = Math.min(Math.max(iconH * 1.35, cellW * 0.9 / ratio), (baseY - 2) * va);
            if (dh * ratio > cellW * 1.08) dh = cellW * 1.08 / ratio;
            const lw = dh * ratio;
            const lh = dh / va;
            const x = Math.max(0, Math.min(ww - lw, baseX - lw / 2));
            art(layer, h.key, x, baseY - lh, lw, lh, 'stretch');
        }
        const dots = gfx(mk(layer, 'OwnerDots', 0, 0, ww, this.g.rows * this.g.tile));
        for (const h of list) {
            if (!h.ownerColor) continue;
            const cx = h.n.position.x + h.dot.x * h.n.scale.x;
            const cy = -h.n.position.y + h.dot.y * h.n.scale.y;
            const r = 8 * h.n.scale.x;
            // 世界节点纵向有拉伸：竖直半径按比例放大，显示为正圆
            dots.fillColor = col('#FFFFFF');
            dots.ellipse(cx, -cy, r, r / va);
            dots.fill();
            dots.fillColor = col(h.ownerColor);
            dots.ellipse(cx, -cy, r * 0.75, (r * 0.75) / va);
            dots.fill();
        }
    }

    private facing(col0: number, row: number): 'top' | 'bottom' | 'left' | 'right' {
        const g = this.g;
        if (row === g.rows - 1) return 'top';
        if (row === 0) return 'bottom';
        if (col0 === 0) return 'right';
        return 'left';
    }

    private drawTile(parent: Node, tile: BoardTile, game: GameView): void {
        const g = this.g;
        const t = g.tile;
        const cell = gridCell(tile.index, g);
        const n = mk(parent, 'Tile' + tile.index, this.xs[cell.col], this.ys[cell.row], t, t);
        n.setScale((this.xs[cell.col + 1] - this.xs[cell.col]) / t,
            (this.ys[cell.row + 1] - this.ys[cell.row]) / t, 1);
        const gg = gfx(n);
        const m = 2;
        fillRR(gg, m, m + 2, t - 2 * m, t - 2 * m, 8, '#00000022');
        fillRR(gg, m, m, t - 2 * m, t - 2 * m, 8, '#FFFDF5');
        if (this.highlight === tile.index) {
            fillRR(gg, 0, 0, t, t, 10, '#FFD64666');
            strokeRR(gg, m, m, t - 2 * m, t - 2 * m, 8, Theme.c.yellow, 4);
        } else strokeRR(gg, m, m, t - 2 * m, t - 2 * m, 8, '#D8CBA0', 2);
        const prop = game.properties.find((p) => p.tileIndex === tile.index);
        // 内容区（扣掉色条）
        let x0 = m;
        let y0 = m;
        let cw = t - 2 * m;
        let ch = t - 2 * m;
        const isProp = tile.type === 'PROPERTY';
        const bar = Math.round(t * 0.26);
        const side = this.facing(cell.col, cell.row);
        if (isProp) {
            const color = tile.tier === 'LOW' ? Theme.c.tierLow : tile.tier === 'MID' ? Theme.c.tierMid : Theme.c.tierHigh;
            let bx = m;
            let by = m;
            let bw = cw;
            let bh = bar;
            if (side === 'top') { y0 += bar; ch -= bar; }
            else if (side === 'bottom') { by = t - m - bar; ch -= bar; }
            else if (side === 'left') { x0 += bar; cw -= bar; bw = bar; bh = t - 2 * m; }
            else { bx = t - m - bar; cw -= bar; bw = bar; bh = t - 2 * m; }
            fillRR(gg, bx, by, bw, bh, 6, color);
            if (tile.auctionLot) fillCircle(gg, t - 11, t - 11, 6, Theme.c.red);
        }
        // 下部三字地名，上部图标
        const lh = Math.ceil(t * (side === 'left' || side === 'right' ? 0.4 : 0.32));
        this.drawTileName(n, tile.name, x0, y0 + ch - lh, cw, lh);
        const icx = x0 + cw / 2;
        const icy = y0 + (ch - lh) / 2;
        const isz = Math.min(cw, ch - lh) * 0.9;
        const iconKey = tile.type === 'PROPERTY' ? 'house_lv' + (prop?.level ?? 0)
            : ({ BANK: 'icon_bank', JAIL: 'icon_jail', EVENT: 'event_card_back', GAME_ZONE: 'croc_open' } as Record<string, string>)[tile.type];
        // 格子节点与世界节点都有非等比缩放：图标按显示比例等比画，不再被压扁 / 拉长
        const aspect = (n.scale.y * this.verticalAspect) / n.scale.x;
        const housed = isProp && !!prop?.owner && !prop.mortgaged && !!iconKey && !!artRatio(iconKey);
        if (housed) {
            // 有主地产：立体房子画在格子之上的房屋层（更大，向上探出格子），这里只留地基阴影
            gg.fillColor = col('#00000022');
            gg.ellipse(icx, -(icy + isz * 0.36), isz * 0.42, isz * 0.12 / aspect);
            gg.fill();
            this.houses.push({ key: iconKey, n, icx, icy, isz, dot: { x: x0 + 8, y: y0 + 8 }, ownerColor: this.ownerColor(prop, game) });
        } else if (!iconKey || !art(n, iconKey, icx - isz / 2, icy - isz / 2, isz, isz, 'contain', false, { aspect })) {
            this.drawIcon(gg, tile, prop, icx, icy, isz);
        }
        if (isProp && prop && prop.owner) {
            if (!housed) this.drawOwnerDot(gg, prop, game, x0 + 8, y0 + 8);
            if (prop.mortgaged) {
                fillRR(gg, m, m, t - 2 * m, t - 2 * m, 8, '#4A556099');
                text(n, '押', 0, 0, t, t - lh, Math.round(t * 0.4), Theme.c.white, { bold: true });
            }
        }
        if (tile.type === 'STATION') this.drawOwnerDot(gg, prop, game, t - 12, 12);
    }

    /** Rasterize small names at 3x; cancel the tile/world stretch on glyphs only.
     * The text box still fills its original band, and follows camera zoom normally.
     */
    private drawTileName(parent: Node, name: string, x: number, y: number, w: number, h: number): void {
        const sx = parent.scale.x;
        const sy = parent.scale.y * this.verticalAspect;
        const uniform = Math.min(sx, sy);
        const bw = w * sx / uniform;
        const bh = h * sy / uniform;
        const font = Math.floor(Math.min(bh * 0.78, (bw - 4) / (textWidth(name, 1) + 0.25)));
        const density = 3;
        const label = text(parent, name, x, y, bw * density, bh * density,
            font * density, Theme.c.ink, { bold: true });
        label.overflow = Label.Overflow.CLAMP;
        label.node.setScale(uniform / sx / density, uniform / sy / density, 1);
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
                fillCircle(gg, cx, cy, s * 0.4, Theme.c.orange);
                fillRR(gg, cx - s * 0.05, cy - s * 0.22, s * 0.1, s * 0.26, 3, Theme.c.white);
                fillCircle(gg, cx, cy + s * 0.22, s * 0.06, Theme.c.white);
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

    tileCenter(index: number): Pt {
        const c = gridCell(index % ringLength(this.g), this.g);
        return { x: (this.xs[c.col] + this.xs[c.col + 1]) / 2,
            y: (this.ys[c.row] + this.ys[c.row + 1]) / 2 };
    }

    // ---------- 棋子 ----------
    private drawTokens(parent: Node, game: GameView, myId: string, myName: string): void {
        const t = this.g.tile;
        const seen: Record<number, number> = {};
        const sOther = Math.round(t * 0.52);
        const sMe = Math.round(t * 0.82);
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
            const s = Math.round(me ? Math.min(sMe, fit * 0.9) : Math.min(sOther, fit * 0.62));
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
            if (!art(n, characterKey(p.avatar, true), 0, -s * 0.5, s, s * 1.5, 'contain', false, { aspect: this.verticalAspect, bottom: true }))
                avatar(n, 0, 0, s, p.avatar, p.nickname, { ring: me ? Theme.c.blue : cur ? Theme.c.yellow : undefined });
            if (p.inJail) this.drawBars(n, s);
            if (me) this.drawBubble(n, s, '我·' + myName);
            this.tokens.set(p.playerId, { node: n, s, offX, offY });
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
        const fs = Math.max(15, Math.round(this.g.tile * 0.28));
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
     * 逐格跳跃：从格 a 跳到相邻格 b，k∈[0,1) 为这一步内的进度（由时间戳算出）。
     * 连续缓入缓出，小幅抬脚；保留同格偏移，避免起步与落地时突然错位。
     */
    hopTo(id: string, a: number, b: number, k: number): Pt | null {
        const tk = this.tokens.get(id);
        if (!tk) return null;
        const A = this.tileCenter(a);
        const B = this.tileCenter(b);
        const progress = Math.max(0, Math.min(1, k));
        const eased = progress * progress * (3 - 2 * progress);
        const x = A.x + (B.x - A.x) * eased;
        const y = A.y + (B.y - A.y) * eased;
        const yo = -this.g.tile * 0.16 * Math.sin(Math.PI * progress);
        place(tk.node, x + tk.offX - tk.s / 2, y + tk.offY - tk.s / 2 + yo);
        return { x, y };
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
        const ut = this.world.getComponent(UITransform) as UITransform;
        // UI 坐标原点在可见区左下；本工程 Canvas 位于世界原点（居中），故减去可见区一半换算成世界坐标
        const vs = view.getVisibleSize();
        const local = ut.convertToNodeSpaceAR(new Vec3(ui.x - vs.width / 2, ui.y - vs.height / 2, 0));
        const col0 = axisCell(this.xs, local.x);
        const row = axisCell(this.ys, -local.y);
        const idx = this.cellToIndex.get(col0 + ',' + row);
        if (idx !== undefined) this.onTileTap(idx);
    }

    // ---------- 相机 ----------
    private apply(): void {
        const s = this.cam.scale;
        const ww = this.g.cols * this.g.tile * s;
        const wh = this.g.rows * this.g.tile * s * this.verticalAspect;
        const clamp = (v: number, vp: number, size: number) => (size <= vp ? (vp - size) / 2 : Math.max(vp - size - 40, Math.min(40, v)));
        this.cam.vx = clamp(this.cam.vx, this.w, ww);
        this.cam.vy = clamp(this.cam.vy, this.h, wh);
        this.world.setScale(s, s * this.verticalAspect, 1);
        place(this.world, this.cam.vx, this.cam.vy);
    }

    private centerOn(wx: number, wy: number, animate: boolean): void {
        const s = this.cam.scale;
        const vx = this.w / 2 - wx * s;
        const vy = this.h / 2 - wy * s * this.verticalAspect;
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
        const s = Math.max(this.fit * 0.8, Math.min(2.6, old * f));
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
