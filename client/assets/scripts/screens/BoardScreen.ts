/**
 * 页面 4：对局棋盘（含"破产观战"变体，spectator=true）。布局依据 design/screens/01-board.png、09-ui-states.png、02-motion-storyboard.png。
 * 演示流程：点击骰子→骰子翻滚(约 1.2s)→逐格跳跃(每步约 550ms)→按落点弹出买地/升级/租金/事件/虎口拔牙；
 * 对手回合默认不自动演进（演示面板可打开"自动演进"）。动画均由时间戳驱动，点数由 MockStore 决定。
 */
import { Label, Node } from 'cc';
import { Countdown, formatMMSS } from '../core/Clock';
import { eventViewMode } from '../core/EventDraw';
import { matchClockOpacity, matchClockState } from '../core/MatchClock';
import { GameView, PlayerView } from '../core/Models';
import { ME } from '../core/MockStore';
import { Theme } from '../core/Theme';
import { TileInfoPopup } from '../popups/TileInfoPopup';
import { AssetsPopup } from '../popups/AssetsPopup';
import { ghostButton, IconButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { DiceView } from '../ui/DiceView';
import { drawBack, drawClock } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setOpacity, setText, text } from '../ui/Kit';
import { Screen, ScreenId } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { roundedPanel } from '../ui/Widgets';
import { BoardView, CamState } from './board/BoardView';
import { drawBottom } from './board/BottomBar';
import { beginDebt } from '../popups/DebtPopup';
import { DiscardPopup } from '../popups/DiscardPopup';
import { EventOverlay } from './board/EventOverlay';
import { handleLanding } from './board/Landing';
import { CashChange, CashChangeNode, connBadge, drawPlayerBar } from './board/PlayerBar';

const VP_Y = 270;
const VP_H = 740;

interface Move { id: string; from: number; steps: number; start: number; done: number; demo: boolean; endTurn: boolean }

export class BoardScreen extends Screen {
    readonly id: ScreenId;
    readonly title: string;
    private view!: BoardView;
    private cam: CamState | null = null;
    private turnCd = new Countdown(ctx.clock);
    private lastCurrent = '';
    private cdSec: Label | null = null;
    private clockNode: Node | null = null;
    private clockLabel: Label | null = null;
    private clockIcon: Node | null = null;
    private clockState = '';
    private dice: DiceView | null = null;
    private move: Move | null = null;
    private pendingEnd = false;
    private otherAt = 0;
    private eventOv: EventOverlay | null = null;
    private pendingFine = 0;
    private cashGame: GameView | null = null;
    private cashSnapshot = new Map<string, number>();
    private cashChanges = new Map<string, CashChange>();
    private cashChangeNodes: CashChangeNode[] = [];

    constructor(private readonly spectator: boolean) {
        super();
        this.id = spectator ? 'spectator' : 'board';
        this.title = spectator ? '破产观战' : '对局棋盘';
    }

    onShow(): void {
        this.lastCurrent = '';
    }

    protected build(): void {
        const st = ctx.store;
        const game = st.game;
        this.backdrop('sky');
        // 页眉：返回 + 药丸（房间号 | 时钟图标 + 剩余时间）；本局倒计时按剩余时间变红/闪烁，房间号不变色
        new IconButton(this.root, 80, 24, 60, '', () => ctx.screens.go('lobby'), Theme.c.ivory, Theme.c.ink, (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.ink));
        const hd = mk(this.root, 'Header', 150, 24, 366, 60);
        fillRR(gfx(hd), 0, 0, 366, 60, 30, '#FFFFFFE6');
        text(hd, '房间 ' + st.session.roomId, 14, 0, 150, 60, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
        this.clockNode = mk(hd, 'MatchClock', 168, 0, 190, 60);
        this.clockIcon = mk(this.clockNode, 'ClockIcon', 6, 14, 32, 32);
        this.clockLabel = text(this.clockNode, '', 44, 0, 140, 60, Theme.font.sm, Theme.c.clockNormal, { bold: true, align: 'l' });
        this.clockState = '';

        const ev = st.eventDraw;
        const evMode = eventViewMode(ev, ME);
        this.captureCashChanges(game);
        this.cashChangeNodes = [];
        drawPlayerBar(this.root, 6, 100, game.players, game.currentPlayer, ME,
            ev.phase !== 'IDLE' && ev.actor !== ME ? ev.actor : null,
            id => ctx.popups.open(new AssetsPopup(id)), this.cashChanges, this.cashChangeNodes);

        // 棋盘视口
        this.view = new BoardView(this.root, 0, VP_Y, Theme.W, VP_H, this.cam);
        const evActor = ev.actor ? st.player(ev.actor) : undefined;
        this.view.render(game, ME, st.me().nickname, 'fan', evMode === 'MY_DRAW' && evActor ? evActor.position : null);
        this.cam = this.view.cam;
        this.view.onTileTap = (i) => {
            // Screen22 keeps event interaction on the board; inspecting a tile must not draw an event.
            if (ctx.store.tile(i).type === 'EVENT' || ctx.store.tile(i).type === 'GAME_ZONE') return;
            ctx.popups.open(new TileInfoPopup(i));
        };
        const cur = st.player(game.currentPlayer);
        if (this.cam.follow && cur && !this.move) this.view.focusTile(cur.position, false);

        this.buildOverlays();
        drawBottom(this.root, this.spectator);
        this.tickTexts();
    }

    // ---------- 覆盖层 ----------
    private buildOverlays(): void {
        const st = ctx.store;
        const game = st.game;
        const me = st.me();
        const note = this.connNote(me);
        if (note) {
            const bn = mk(this.root, 'ConnNote', 12, VP_Y + 6, 696, 56);
            fillRR(gfx(bn), 0, 0, 696, 56, 20, '#2D3B4ADD');
            text(bn, note.text, 14, 0, note.action ? 540 : 668, 56, Theme.font.xs, Theme.c.white, { wrap: true, align: 'l', lineHeight: 24 });
            if (note.action) ghostButton(bn, note.action, 566, 8, 120, 40, () => {
                me.control = 'MANUAL';
                st.emit();
            }, Theme.font.sm);
        }
        // 所有观察者看到中央抽卡动画；只有触发者本人可点击卡背。
        this.eventOv = null;
        this.dice = null;
        this.cdSec = null;
        if (st.eventDraw.phase !== 'IDLE') {
            this.eventOv = new EventOverlay(this.root);
            const actor = st.eventDraw.actor ? st.player(st.eventDraw.actor) : undefined;
            this.eventOv.build(st.eventDraw, () => st.eventClick(ME), !this.spectator && st.eventDraw.actor === ME, actor?.nickname ?? '玩家');
            // Screen22 retains the die below the central card; drawing never enables another roll.
            this.dice = new DiceView(this.root, Theme.W / 2 - 54, 900);
            this.dice.setValue(game.lastDice);
        } else this.buildTurnPanel();
        (this.spectator || st.eventDraw.phase !== 'IDLE' ? [] : game.chat.slice(-1)).forEach((c) => {
            const y = VP_Y + 622;
            const w = Math.min(380, 40 + (c.from.length + c.text.length) * 22);
            const b = mk(this.root, 'Chat', (Theme.W - w) / 2, y, w, 34);
            fillRR(gfx(b), 0, 0, w, 34, 17, '#2D3B4ACC');
            text(b, c.from + '：' + c.text, 12, 0, w - 20, 34, 20, Theme.c.white, { align: 'l' });
        });
    }

    private connNote(me: PlayerView): { text: string; action?: string } | null {
        if (me.life !== 'ALIVE') return null;
        if (me.conn === 'SUSPECT') return { text: '网络不稳定：15 秒无消息显示"疑似断线"，30 秒无消息确认掉线并自动投骰' };
        if (me.conn === 'OFFLINE') return { text: '你已被判定掉线：自动投骰，不买地不升级；重连后可恢复手动' };
        if (me.control === 'HOSTED') return { text: '托管中：自动投骰，现金够会买地/升级，不主动用卡', action: '取消托管' };
        if (me.control === 'AWAY') return { text: '暂离中：自动投骰；需要你明确恢复才会取消', action: '我回来了' };
        return null;
    }

    /** 中央回合提示与可点击骰子；按用户修正取消独立投骰按钮。 */
    private buildTurnPanel(): void {
        const st = ctx.store;
        const game = st.game;
        const cur = st.player(game.currentPlayer) as PlayerView;
        const myTurn = st.isMyTurn() && !this.spectator;
        const cx = Theme.W / 2;
        const top = VP_Y + (this.spectator ? 90 : 230);
        const pill = roundedPanel(this.root, cx - 150, top, 300, 100, { fill: '#FFFDF2F2', r: 34 });
        const ic = mk(pill, 'TurnIcon', 22, 26, 44, 44);
        drawClock(gfx(ic), 22, 24, 17, Theme.c.yellowDark);
        text(pill, myTurn ? '轮到你了' : cur.nickname + '的回合', 70, 6, 220, 52, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        text(pill, '剩余', 70, 54, 62, 36, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
        this.cdSec = text(pill, '', 130, 54, 120, 36, Theme.font.md, Theme.c.red, { bold: true, align: 'l' });
        if (this.spectator) {
            const status = roundedPanel(this.root, cx - 180, top + 112, 360, 58, { fill: '#2D3B4AEE', r: 29 });
            text(status, '已' + (st.me().life === 'SURRENDERED' ? '认输' : '破产') + ' · 观战中', 0, 0, 360, 58, Theme.font.lg, Theme.c.white, { bold: true });
            this.dice = new DiceView(this.root, cx - 54, top + 228);
            this.dice.setValue(game.lastDice);
            return;
        }
        // 石板广场 + 骰子
        const plaza = mk(this.root, 'Plaza', cx - 130, top + 104, 260, 190);
        fillCircle(gfx(plaza), 130, 100, 112, '#E8D9B466');
        fillCircle(gfx(plaza), 130, 100, 92, '#F1E5C655');
        this.dice = new DiceView(this.root, cx - 54, top + 142);
        this.dice.setValue(game.lastDice);
        onTap(this.dice.node, () => {
            if (this.canRoll()) this.startRoll();
        }, false);
        if (!myTurn) {
            const badge = connBadge(cur.conn, cur.control);
            const msg = '等待 ' + cur.nickname + ' 行动…' + (badge ? '（' + badge.text + '）' : '');
            const wp = roundedPanel(this.root, cx - 170, top + 296, 340, 48, { fill: '#FFFDF2EE', r: 28 });
            text(wp, msg, 10, 0, 320, 48, Theme.font.sm, Theme.c.inkSoft, { bold: true });
        }
    }

    private busy(): boolean {
        return !!this.move || !!(this.dice && this.dice.playing) || ctx.store.eventDraw.phase !== 'IDLE';
    }

    private canRoll(): boolean {
        return !this.spectator && ctx.store.isMyTurn() && me_manual(ctx.store.me()) && !this.busy() && ctx.popups.count === 0;
    }

    // ---------- 投骰（翻滚动画）与逐格跳跃 ----------
    private startRoll(): void {
        if (this.busy() || !this.dice) return;
        const st = ctx.store;
        const v = st.rollValue();
        this.dice.setReady(false);
        this.dice.play(v, () => {
            Toast.show('掷出 ' + v + ' 点');
            this.startMove(ME, v, false, true);
        });
    }

    /** 演示菜单：只播骰子动画，不移动。 */
    demoRoll(): void {
        if (this.busy() || !this.dice) return;
        const v = ctx.store.rollValue();
        this.dice.play(v, () => Toast.show('骰子动画结束：' + v + ' 点（演示，不移动）'));
    }

    /** 演示菜单：我的棋子连续跳 n 格（真实移动，不触发落点/换人）。 */
    demoHop(n: number): void {
        if (this.busy() || this.spectator || ctx.store.me().life !== 'ALIVE') return;
        this.startMove(ME, n, true, false);
    }

    private startMove(id: string, steps: number, demo: boolean, endTurn: boolean): void {
        const p = ctx.store.player(id);
        if (!p) return;
        this.move = { id, from: p.position, steps, start: Date.now(), done: 0, demo, endTurn };
        this.view.cam.follow = true;
    }

    private tickMove(): void {
        const m = this.move;
        if (!m) return;
        const n = ctx.store.game.tiles.length;
        const hop = Theme.anim.hopMs;
        const e = Date.now() - m.start;
        const step = Math.floor(e / hop);
        // 逐格触地：每完成一步在落点冒尘土
        while (m.done < Math.min(step, m.steps)) {
            m.done++;
            const idx = (m.from + m.done) % n;
            this.view.dust(idx);
        }
        if (step >= m.steps) {
            this.move = null;
            const st = ctx.store;
            const mv = st.moveBy(m.id, m.steps);
            if (m.id === ME) this.view.pulse(mv.pos);
            if (m.endTurn) this.pendingEnd = true;
            st.emit();
            if (m.id === ME && m.endTurn && !m.demo) handleLanding(mv.pos);
            return;
        }
        const k = (e - step * hop) / hop;
        const a = (m.from + step) % n;
        const b = (m.from + step + 1) % n;
        const pt = this.view.hopTo(m.id, a, b, k);
        if (pt) this.view.followPoint(pt);
    }

    // ---------- 事件卡抽卡 ----------
    private confirmEvent(): void {
        const st = ctx.store;
        st.eventClose(ME);
        // 罚款不足：确认后走欠款流程；道具超过 6 张：弃牌
        if (this.pendingFine > 0) {
            const f = this.pendingFine;
            this.pendingFine = 0;
            beginDebt(f, null);
        } else if (st.game.myHand.length > 6) ctx.popups.open(new DiscardPopup());
    }

    private tickEvent(): void {
        const st = ctx.store;
        const ev = st.eventDraw;
        const now = Date.now();
        if (ev.actor && ev.actor !== ME) {
            // 模拟他人客户端：稍后点击卡片；结果停留片刻后由系统收起
            if (ev.phase === 'WAITING' && now - ev.since >= Theme.anim.eventOtherHoldMs) st.eventClick(ev.actor);
            else if (ev.phase === 'RESULT' && now - ev.since >= Theme.anim.eventResultHoldMs) {
                st.eventClose(ev.actor);
            }
        } else if (ev.actor === ME && ev.phase === 'RESULT' && now - ev.since >= Theme.anim.eventResultHoldMs) {
            this.confirmEvent();
        }
        const cashBeforeSettlement = st.me().cash;
        const settled = st.eventTick();
        if (settled && settled.actor === ME && settled.result.kind === 'CASH_FINE' && cashBeforeSettlement < settled.result.amount) this.pendingFine = settled.result.amount;
        this.eventOv?.tick(now);
    }

    /** 演示菜单：我走到事件格并开始抽卡。 */
    demoEventMe(): void {
        const st = ctx.store;
        if (this.spectator || st.me().life !== 'ALIVE' || st.eventDraw.phase !== 'IDLE' || this.move) return;
        st.moveToEventTile(ME);
        st.eventStart(ME);
    }

    /** 演示菜单：他人（第二位玩家）走到事件格并抽卡，我同步观看。 */
    demoEventOther(): void {
        const st = ctx.store;
        if (st.eventDraw.phase !== 'IDLE' || st.game.players.length < 2) return;
        st.moveToEventTile('p2');
        st.eventStart('p2');
    }

    // ---------- 文字/时钟 ----------
    private captureCashChanges(game: GameView): void {
        const now = Date.now();
        if (this.cashGame !== game) {
            this.cashGame = game;
            this.cashSnapshot.clear();
            this.cashChanges.clear();
        }
        for (const p of game.players) {
            const previous = this.cashSnapshot.get(p.playerId);
            if (previous !== undefined && previous !== p.cash)
                this.cashChanges.set(p.playerId, { amount: p.cash - previous, until: now + 2600 });
            this.cashSnapshot.set(p.playerId, p.cash);
        }
        for (const [id, change] of this.cashChanges) if (change.until <= now) this.cashChanges.delete(id);
    }

    private tickCashChanges(): void {
        const now = Date.now();
        for (const change of this.cashChangeNodes) {
            if (!change.node.isValid) continue;
            const remaining = change.until - now;
            change.node.active = remaining > 0;
            if (change.badge?.isValid) change.badge.active = remaining <= 0;
            if (remaining > 0) setOpacity(change.node, Math.min(255, Math.round(remaining / 600 * 255)));
        }
    }

    private tickTexts(): void {
        const st = ctx.store;
        if (this.clockLabel && this.clockNode && this.clockIcon) {
            const timed = st.session.settings.endMode === 'TIME_LIMIT';
            const sec = st.matchRemainingSec();
            const state = timed ? matchClockState(sec) : 'normal';
            const color = state === 'normal' ? Theme.c.clockNormal : Theme.c.clockRed;
            setText(this.clockLabel, timed ? '剩余 ' + formatMMSS(sec * 1000) : '破产模式', color);
            if (this.clockState !== state) {
                this.clockState = state;
                const g = gfx(this.clockIcon);
                g.clear();
                fillCircle(g, 16, 16, 15, color);
                fillCircle(g, 16, 16, 11, Theme.c.white);
                drawClock(g, 16, 16, 9, color);
            }
            setOpacity(this.clockNode, Math.round(255 * matchClockOpacity(state, Date.now())));
        }
        if (this.cdSec) setText(this.cdSec, this.turnCd.remainingSec() + '秒');
    }

    tick(dt: number): void {
        const st = ctx.store;
        const g = st.game;
        if (!this.view) return;
        this.tickCashChanges();
        this.view.tick(dt);
        this.dice?.setReady(this.canRoll());
        this.dice?.update();
        if (g.currentPlayer !== this.lastCurrent) {
            this.lastCurrent = g.currentPlayer;
            this.turnCd.start(st.session.settings.rollSeconds);
            this.otherAt = Date.now() + 2200;
            const cur = st.player(g.currentPlayer);
            this.view.cam.follow = true; // 每次换人恢复跟随（设计已取消"定位我"按钮）
            if (cur && !this.move) this.view.focusTile(cur.position);
        }
        this.tickTexts();
        this.tickEvent();
        if (this.move) {
            this.tickMove();
            return;
        }
        if (this.dice && this.dice.playing) return;
        // 回合结束：弹窗都关了再换人
        if (this.pendingEnd && ctx.popups.count === 0 && st.eventDraw.phase === 'IDLE' && ctx.screens.currentId === this.id) {
            this.pendingEnd = false;
            st.advanceTurn();
            return;
        }
        if (st.autoplay && !this.spectator && ctx.popups.count === 0) {
            const cur = st.player(g.currentPlayer);
            if (cur && cur.playerId !== ME && Date.now() >= this.otherAt) {
                const steps = st.rollValue();
                this.otherAt = Number.MAX_SAFE_INTEGER;
                this.dice?.play(steps, () => this.startMove(cur.playerId, steps, false, true));
            } else if (cur && cur.playerId === ME && this.turnCd.consumeExpire() && st.isMyTurn()) {
                Toast.show('投骰超时，自动投骰');
                this.startRoll();
            }
        }
    }
}

function me_manual(p: PlayerView): boolean {
    return p.control === 'MANUAL' && p.conn !== 'OFFLINE';
}
