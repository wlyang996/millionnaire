/**
 * 页面 4：对局棋盘（含"破产观战"变体，spectator=true）。布局依据 design/screens/01-board.png、09-ui-states.png、02-motion-storyboard.png。
 * 演示流程：点击骰子→骰子翻滚(约 1.2s)→逐格跳跃(每步约 550ms)→按落点弹出买地/升级/租金/事件/虎口拔牙；
 * 对手回合默认不自动演进（演示面板可打开"自动演进"）。动画均由时间戳驱动，点数由 MockStore 决定。
 */
import { BlockInputEvents, Label, Node } from 'cc';
import { Countdown, formatMMSS } from '../core/Clock';
import { eventViewMode } from '../core/EventDraw';
import { matchClockOpacity, matchClockState } from '../core/MatchClock';
import { GameView, PlayerView } from '../core/Models';
import { Theme } from '../core/Theme';
import { TileInfoPopup } from '../popups/TileInfoPopup';
import { AssetsPopup } from '../popups/AssetsPopup';
import { ghostButton, IconButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { DiceView } from '../ui/DiceView';
import { drawBack, drawClock } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setOpacity, setText, strokeRR, text } from '../ui/Kit';
import { Screen, ScreenId } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, roundedPanel } from '../ui/Widgets';
import { boardMarks, BoardView, CamState } from './board/BoardView';
import { drawBottom } from './board/BottomBar';
import { BuyPopup } from '../popups/BuyPopup';
import { UpgradePopup } from '../popups/UpgradePopup';
import { BankPopup } from '../popups/BankPopup';
import { beginDebt, DebtPopup } from '../popups/DebtPopup';
import { DebtSecondPopup } from '../popups/DebtSecondPopup';
import { DiscardPopup } from '../popups/DiscardPopup';
import { JailPopup } from '../popups/JailPopup';
import { RentPopup } from '../popups/RentPopup';
import { CardResponsePopup } from '../popups/CardResponsePopup';
import { QueryResultPopup } from '../popups/QueryPopups';
import { rentAt } from '../popups/CardUse';
import { AuctionPopup } from '../popups/AuctionPopup';
import { TradePopup } from '../popups/TradePopup';
import { EventOverlay } from './board/EventOverlay';
import { JailOverlay } from './board/JailOverlay';
import { handleLanding } from './board/Landing';
import { CASH_DELTA_MS, CashChange, CashChangeNode, drawPlayerBar, tickCashDelta } from './board/PlayerBar';

/** 设计稿 01：棋盘区 y≈262–1095（视口 256–1098），手牌栏 1102–1192，页脚 1198–1280。 */
const VP_Y = 256;
const VP_H = 842;

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
    /** 联机：已为哪个服务端窗口弹过窗（同一窗口只弹一次） */
    private openedFor = -1;
    /** 联机：回合倒计时对应的服务端窗口 */
    private cdWindow = -1;
    /** 入狱动画（谁、何时开始）；页面重建后按时间戳续播 */
    private jail: { name: string; start: number } | null = null;
    private jailOv: JailOverlay | null = null;

    constructor(private readonly spectator: boolean) {
        super();
        this.id = spectator ? 'spectator' : 'board';
        this.title = spectator ? '破产观战' : '对局棋盘';
    }

    onShow(): void {
        this.lastCurrent = '';
    }

    /** 骰子翻滚或人物跳跃时推迟整页重建（重建会打断动画、造成卡顿），动画结束后补一次。 */
    private dirty = false;

    refresh(): void {
        if (this.move || (this.dice && this.dice.playing)) {
            this.dirty = true;
            return;
        }
        this.dirty = false;
        super.refresh();
    }

    private get myId(): string {
        return ctx.store.myId;
    }

    protected build(): void {
        const st = ctx.store;
        const game = this.displayGame(st.game);
        this.backdrop('sky');
        // 页眉：返回 + 药丸（房间号 | 时钟图标 + 剩余时间）；本局倒计时按剩余时间变红/闪烁，房间号不变色
        // 设计稿 01 页眉：返回（x 88–148）+ 房间号 / 本局剩余时间药丸（x 155–530，y 12–62）
        new IconButton(this.root, 88, 7, 60, '', () => ctx.screens.go('lobby'), Theme.c.ivory, Theme.c.ink, (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.navy));
        const hd = mk(this.root, 'Header', 155, 12, 375, 50);
        fillRR(gfx(hd), 0, 0, 375, 50, 25, '#FFFFFFE6');
        text(hd, '房间 ' + st.session.roomId, 18, 0, 170, 50, 26, Theme.c.navy, { bold: true, align: 'l' });
        this.clockNode = mk(hd, 'MatchClock', 196, 0, 175, 50);
        this.clockIcon = mk(this.clockNode, 'ClockIcon', 2, 9, 32, 32);
        this.clockLabel = text(this.clockNode, '', 40, 0, 135, 50, 26, Theme.c.clockNormal, { bold: true, align: 'l' });
        this.clockState = '';

        const ev = st.eventDraw;
        const evMode = eventViewMode(ev, this.myId);
        this.captureCashChanges(game);
        this.cashChangeNodes = [];
        drawPlayerBar(this.root, 44, 74, game.players, game.currentPlayer, this.myId,
            ev.phase !== 'IDLE' && ev.actor !== this.myId ? ev.actor : null,
            id => ctx.popups.open(new AssetsPopup(id)), this.cashChanges, this.cashChangeNodes);

        // 棋盘视口
        this.view = new BoardView(this.root, 0, VP_Y, Theme.W, VP_H, this.cam);
        const evActor = ev.actor ? st.player(ev.actor) : undefined;
        this.view.render(game, this.myId, st.me().nickname, 'fan', evMode === 'MY_DRAW' && evActor ? evActor.position : null);
        this.cam = this.view.cam;
        this.view.onTileTap = (i) => {
            // Screen22 keeps event interaction on the board; inspecting a tile must not draw an event.
            if (ctx.store.tile(i).type === 'EVENT' || ctx.store.tile(i).type === 'GAME_ZONE') return;
            ctx.popups.open(new TileInfoPopup(i));
        };
        const cur = st.player(game.currentPlayer);
        if (this.cam.follow && cur && !this.move) this.view.focusTile(cur.position, false);

        this.buildOverlays();
        drawBottom(this.root, this.spectator, this.cashChanges.get(this.myId), this.cashChangeNodes);
        this.buildAwayOverlay();
        this.jailOv = this.jail ? new JailOverlay(this.root, this.jail.name, this.jail.start) : null;
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
                if (st.online) return void st.online.act('ResumeControl', { gameNo: game.gameNo });
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
            this.eventOv.build(st.eventDraw, () => st.eventClick(this.myId), !this.spectator && st.eventDraw.actor === this.myId, actor?.nickname ?? '玩家',
                st.online ? () => st.eventClose(this.myId) : undefined, this.view.innerRect());
            // 设计稿 10：抽卡时中央只有卡片，不显示骰子
        } else this.buildTurnPanel();
    }

    private connNote(me: PlayerView): { text: string; action?: string } | null {
        if (me.life !== 'ALIVE') return null;
        if (me.conn === 'SUSPECT') return { text: '网络不稳定：15 秒无消息显示"疑似断线"，30 秒无消息确认掉线并自动投骰' };
        if (me.conn === 'OFFLINE') return { text: '你已被判定掉线：自动投骰，不买地不升级；重连后可恢复手动' };
        return null; // 暂离 / 托管：全屏遮罩（buildAwayOverlay）
    }

    /** 我被判定挂机（暂离，服务端在连续两次投骰超时后判定）或托管：全屏遮罩，挡住棋盘操作，只留"恢复手动"按钮。 */
    private buildAwayOverlay(): void {
        const st = ctx.store;
        if (this.spectator) return;
        const me = st.me();
        if (!me || me.life !== 'ALIVE' || me.control === 'MANUAL') return;
        const hosted = me.control === 'HOSTED';
        const ov = mk(this.root, 'AwayOverlay', 0, 0, Theme.W, Theme.H);
        fillRR(gfx(ov), 0, 0, Theme.W, Theme.H, 0, '#0E1420CC');
        ov.addComponent(BlockInputEvents);
        const cy = 520;
        const badge = mk(ov, 'Badge', Theme.W / 2 - 90, cy - 200, 180, 180);
        const bg = gfx(badge);
        fillCircle(bg, 90, 90, 90, hosted ? '#4DA3F055' : '#FFB54755');
        fillCircle(bg, 90, 90, 70, hosted ? Theme.c.blue : Theme.c.orange);
        text(badge, hosted ? '托' : 'Zz', 0, 0, 180, 180, 76, Theme.c.white, { bold: true });
        text(ov, hosted ? '托管中' : '挂机中', 0, cy, Theme.W, 110, 88, Theme.c.white, { bold: true });
        text(ov, hosted ? '系统正在替你操作：自动投骰，现金够会买地 / 升级，不主动用卡'
            : '连续没有投骰，已被判定挂机。系统正在替你操作：自动投骰，现金够会买地 / 升级',
            60, cy + 120, Theme.W - 120, 80, Theme.font.md, '#D6DEE8', { wrap: true, lineHeight: 36 });
        text(ov, '全员都挂机或托管时，本局会直接结束', 60, cy + 196, Theme.W - 120, 40, Theme.font.sm, '#AAB6C4');
        primaryButton(ov, hosted ? '取消托管' : '我回来了', Theme.W / 2 - 170, cy + 268, 340, 96, () => {
            if (st.online) return void st.online.act('ResumeControl', { gameNo: st.game.gameNo });
            me.control = 'MANUAL';
            st.emit();
        }, Theme.font.lg);
    }

    /** 中央回合提示与可点击骰子；按用户修正取消独立投骰按钮。 */
    private buildTurnPanel(): void {
        const st = ctx.store;
        const game = st.game;
        const cur = st.player(game.currentPlayer) as PlayerView;
        const myTurn = st.isMyTurn() && !this.spectator;
        const cx = Theme.W / 2;
        // 设计稿 01 / 11：白色圆角药丸（x 250–466，y 498–578）+ 黄色秒表；本人"轮到你了 / 剩余 N秒"，他人"X的回合 / 等待X投骰"
        const top = 498;
        if (this.spectator) {
            // 设计稿 03 破产观战：加大的白色药丸 + 黄色描边 + 当前玩家头像"X的回合"；下方深色"已破产 · 观战中"；骰子在其下
            const sp = roundedPanel(this.root, cx - 170, 452, 340, 96, { fill: '#FFFFFFF5', r: 30 });
            strokeRR(gfx(sp), 1, 1, 338, 94, 30, '#F5C33B', 4);
            avatar(sp, 12, 10, 76, cur.avatar, cur.nickname);
            text(sp, cur.nickname + '的回合', 98, 0, 232, 96, 34, Theme.c.navy, { bold: true, align: 'l' });
            const status = roundedPanel(this.root, cx - 150, 562, 300, 56, { fill: '#2D3B4AEE', r: 28 });
            text(status, '已' + (st.me().life === 'SURRENDERED' ? '认输' : '破产') + ' · 观战中', 0, 0, 300, 56, Theme.font.md, Theme.c.white, { bold: true });
            this.dice = new DiceView(this.root, cx - 54, 640);
            this.dice.setValue(game.lastDice);
            return;
        }
        const pill = roundedPanel(this.root, cx - 110, top, 220, 80, { fill: '#FFFFFFF2', r: 24 });
        // 本人回合：黄色秒表；他人回合：当前玩家头像（设计稿 09 / 03）
        if (myTurn) drawClock(gfx(mk(pill, 'TurnIcon', 12, 14, 44, 44)), 22, 24, 17, Theme.c.yellowDark);
        else avatar(pill, 8, 14, 50, cur.avatar, cur.nickname);
        text(pill, myTurn ? '轮到你了' : cur.nickname + '的回合', 58, 6, 156, 40, 28, Theme.c.navy, { bold: true, align: 'l' });
        if (myTurn) {
            text(pill, '剩余', 58, 42, 56, 32, 22, Theme.c.navy, { bold: true, align: 'l' });
            this.cdSec = text(pill, '', 108, 42, 100, 32, 22, Theme.c.payRed, { bold: true, align: 'l' });
        } else {
            text(pill, '等待' + cur.nickname + '投骰', 58, 42, 156, 32, 20, Theme.c.navy, { align: 'l' });
        }
        // 骰子：设计稿 01 中央（x 300–420，y 610–730）
        this.dice = new DiceView(this.root, cx - 54, 616);
        this.dice.setValue(game.lastDice);
        onTap(this.dice.node, () => {
            if (this.canRoll()) this.startRoll();
        }, false);
        // 定点移动标号显示期间，收起回合提示与骰子，免得挡住棋盘上的标号
        const dice = this.dice;
        boardMarks.onToggle = (on) => {
            if (pill.isValid) pill.active = !on;
            if (dice.node.isValid) dice.node.active = !on;
        };
        if (boardMarks.current) boardMarks.onToggle(true);
        const jailWin = st.online && myTurn && game.stage === 'JAIL_DECISION' ? st.online.myWindow('TURN') : undefined;
        if (jailWin) {
            text(this.root, '点骰子掷出狱判定（偶数出狱）', cx - 220, 736, 440, 40, Theme.font.sm, Theme.c.ink, { bold: true });
            ghostButton(this.root, '付 500 出狱', cx - 110, 780, 220, 64, () => void st.online!.act('PayBail', { windowId: jailWin.windowId }), Theme.font.md);
        }
    }

    private busy(): boolean {
        return !!this.move || !!(this.dice && this.dice.playing) || ctx.store.eventDraw.phase !== 'IDLE';
    }

    private canRoll(): boolean {
        const online = ctx.store.online;
        if (online) {
            const g = ctx.store.game;
            const w = online.myWindow('TURN');
            return !this.spectator && !!w && online.isOpen(w) && (g.stage === 'PRE_ROLL' || g.stage === 'JAIL_DECISION')
                && !this.busy() && ctx.popups.count === 0 && online.cues.length === 0;
        }
        return !this.spectator && ctx.store.isMyTurn() && me_manual(ctx.store.me()) && !this.busy() && ctx.popups.count === 0;
    }

    // ---------- 投骰（翻滚动画）与逐格跳跃 ----------
    private startRoll(): void {
        if (this.busy() || !this.dice) return;
        const st = ctx.store;
        if (st.online) {
            // 联机：只发命令；骰子与走棋动画由服务端推送的 DiceRolled / PlayerMoved 驱动
            const w = st.online.myWindow('TURN');
            if (!w) return;
            this.dice.setReady(false);
            void st.online.act('RollDice', { windowId: w.windowId });
            return;
        }
        const v = st.rollValue();
        this.dice.setReady(false);
        this.dice.play(v, () => this.startMove(this.myId, v, false, true));
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
        this.startMove(this.myId, n, true, false);
    }

    private startMove(id: string, steps: number, demo: boolean, endTurn: boolean, from?: number): void {
        const p = ctx.store.player(id);
        if (!p) return;
        this.move = { id, from: from ?? p.position, steps, start: Date.now(), done: 0, demo, endTurn };
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
            this.view.endHop(m.id);
            const st = ctx.store;
            if (st.online) {
                // 联机：位置已是服务端结果，动画结束后按真实视图重绘
                if (m.id === this.myId) this.view.pulse((m.from + m.steps) % n);
                st.emit();
                return;
            }
            const mv = st.moveBy(m.id, m.steps);
            if (m.id === this.myId) this.view.pulse(mv.pos);
            if (m.endTurn) this.pendingEnd = true;
            st.emit();
            if (m.id === this.myId && m.endTurn && !m.demo) handleLanding(mv.pos);
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
        st.eventClose(this.myId);
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
        if (ev.actor && ev.actor !== this.myId) {
            // 模拟他人客户端：稍后点击卡片；结果停留片刻后由系统收起
            if (ev.phase === 'WAITING' && now - ev.since >= Theme.anim.eventOtherHoldMs) st.eventClick(ev.actor);
            else if (ev.phase === 'RESULT' && now - ev.since >= Theme.anim.eventResultHoldMs) {
                st.eventClose(ev.actor);
            }
        } else if (ev.actor === this.myId && ev.phase === 'RESULT' && now - ev.since >= Theme.anim.eventResultHoldMs) {
            this.confirmEvent();
        }
        const cashBeforeSettlement = st.me().cash;
        const settled = st.eventTick();
        if (settled && settled.actor === this.myId && settled.result.kind === 'CASH_FINE' && cashBeforeSettlement < settled.result.amount) this.pendingFine = settled.result.amount;
        this.eventOv?.tick(now);
    }

    /** 演示菜单：我走到事件格并开始抽卡。 */
    demoEventMe(): void {
        const st = ctx.store;
        if (this.spectator || st.me().life !== 'ALIVE' || st.eventDraw.phase !== 'IDLE' || this.move) return;
        st.moveToEventTile(this.myId);
        st.eventStart(this.myId);
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
                this.cashChanges.set(p.playerId, { amount: p.cash - previous, until: now + CASH_DELTA_MS });
            this.cashSnapshot.set(p.playerId, p.cash);
        }
        for (const [id, change] of this.cashChanges) if (change.until <= now) this.cashChanges.delete(id);
    }

    private tickCashChanges(): void {
        const now = Date.now();
        for (const change of this.cashChangeNodes) tickCashDelta(change, now);
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
        if (!this.view || !g) return;
        if (this.dirty && !this.move && !(this.dice && this.dice.playing)) {
            this.refresh();
            return;
        }
        this.tickCashChanges();
        this.view.tick(dt);
        this.dice?.setReady(this.canRoll());
        this.dice?.update();
        if (st.online) {
            this.tickOnline();
            return;
        }
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
            if (cur && cur.playerId !== this.myId && Date.now() >= this.otherAt) {
                const steps = st.rollValue();
                this.otherAt = Number.MAX_SAFE_INTEGER;
                this.dice?.play(steps, () => this.startMove(cur.playerId, steps, false, true));
            } else if (cur && cur.playerId === this.myId && this.turnCd.consumeExpire() && st.isMyTurn()) {
                this.startRoll();
            }
        }
    }

    // ---------- 联机 ----------
    /** 动画进行中或待播放时，棋子先显示在动画起点（服务端视图里已是终点）。 */
    private displayGame(game: GameView): GameView {
        const online = ctx.store.online;
        if (!online || !game) return game;
        const n = game.tiles.length;
        const shown = new Map<string, number>();
        if (this.move) shown.set(this.move.id, (this.move.from + this.move.done) % n);
        for (const c of online.cues) if (c.kind === 'move' && !shown.has(c.playerId)) shown.set(c.playerId, c.from);
        if (!shown.size) return game;
        return { ...game, players: game.players.map((p) => (shown.has(p.playerId) ? { ...p, position: shown.get(p.playerId)! } : p)) };
    }

    private tickOnline(): void {
        const st = ctx.store;
        const online = st.online!;
        const g = st.game;
        if (ctx.screens.currentId === this.id && this.spectator !== st.isSpectator()) {
            ctx.screens.go(st.isSpectator() ? 'spectator' : 'board');
            return;
        }
        // 回合倒计时：当前玩家回合窗口的截止时刻（服务器时间）
        const tw = online.turnWindow(g.currentPlayer);
        if (tw && tw.windowId !== this.cdWindow) {
            this.cdWindow = tw.windowId;
            this.turnCd.setDeadline(tw.deadline, Math.max(1, Math.round((tw.deadline - tw.opensAt) / 1000)));
        }
        if (g.currentPlayer !== this.lastCurrent) {
            this.lastCurrent = g.currentPlayer;
            this.view.cam.follow = true;
        }
        this.tickTexts();
        st.eventTick();
        this.eventOv?.tick(Date.now());
        if (!this.spectator && !me_manual(st.me()) && ctx.popups.count > 0) {
            // 债务窗口锁定为手动流程，掉线 / 托管不关；响应窗转托管后由服务端自动处理
            ctx.popups.closeIds(['buy', 'upgrade', 'bank', 'discard', 'auction', 'rent', 'card-response']);
        }
        if (this.jailOv) {
            // 入狱动画期间暂停后续动画与弹窗
            this.jailOv.tick(Date.now());
            if (!this.jailOv.done) return;
            this.jailOv.root.destroy();
            this.jailOv = null;
            this.jail = null;
        }
        if (this.move) {
            this.tickMove();
            return;
        }
        if (this.dice && this.dice.playing) return;
        // 事件卡翻牌与结果展示期间不播后续走棋（先看清结果再移动）
        if (st.eventDraw.phase === 'FLIPPING' || st.eventDraw.phase === 'RESULT') return;
        // 拍卖进行中（土地拍卖或拍卖卡）：所有手动的存活玩家都弹出竞价页（卖家 / 发起人只能看）
        if (g.auction && online.cues.length === 0 && !this.spectator && me_manual(st.me()) && st.me().life === 'ALIVE'
            && !ctx.popups.has('auction')) {
            ctx.popups.open(new AuctionPopup(g.auction.tile, g.auction.kind === 'LAND'));
            return;
        }
        // 交易卡：我是买家时弹出交易确认（设计稿 04）
        const td = g.trade;
        if (td && td.buyer === this.myId && online.cues.length === 0 && me_manual(st.me()) && !ctx.popups.has('trade')) {
            const w = g.windows.find((x) => x.windowId === td.windowId);
            const pop = new TradePopup(td.tile, td.seller, td.price);
            ctx.popups.open(w ? pop.withDeadline(w.deadline) : pop);
            return;
        }
        // 查询卡结果（只发给我）：弹出手牌快照
        const qr = st.queryResult;
        if (qr && !qr.seen && online.cues.length === 0) {
            qr.seen = true;
            ctx.popups.open(new QueryResultPopup(st.player(qr.target)?.nickname ?? '玩家', qr.cards));
            return;
        }
        // 虎口拔牙（落到游戏区、走棋动画播完后）：所有人进入小游戏页；刚结束的也去看一眼结果
        const tr = st.toothResult;
        if (ctx.screens.currentId === this.id && online.cues.length === 0 && (g.minigame || (tr && !tr.seen))) {
            ctx.screens.push('teeth');
            return;
        }
        const cue = online.cues.shift();
        if (cue) {
            const who = st.player(cue.playerId);
            if (cue.kind === 'dice') {
                if (this.dice) this.dice.play(cue.value, () => undefined);
            } else if (cue.kind === 'jail') {
                this.jail = { name: cue.playerId === this.myId ? '你' : who?.nickname ?? '玩家', start: Date.now() };
                this.jailOv = new JailOverlay(this.root, this.jail.name, this.jail.start);
            } else this.startMove(cue.playerId, cue.steps, false, false, cue.from);
            return;
        }
        this.openOnlinePopup();
    }

    /** 按服务端窗口与落点弹出需要我决策的弹窗；同一窗口只弹一次，倒计时用窗口截止时刻。 */
    private openOnlinePopup(): void {
        const st = ctx.store;
        const online = st.online!;
        const g = st.game;
        if (this.spectator || ctx.popups.count > 0 || online.cues.length > 0 || !me_manual(st.me())) return;
        const w = online.myWindow();
        if (!w || !online.isOpen(w) || w.windowId === this.openedFor) return;
        const id = w.windowId;
        const landing = g.landing;
        if (w.kind === 'TURN' && g.stage === 'LANDING' && landing && landing.decisionPending) {
            this.openedFor = id;
            if (landing.step === 'BUY') ctx.popups.open(new BuyPopup(landing.tile, id).withDeadline(w.deadline));
            else if (landing.step === 'UPGRADE') ctx.popups.open(new UpgradePopup(landing.tile, id).withDeadline(w.deadline));
            else if (landing.step === 'BANK') ctx.popups.open(new BankPopup(id));
            else if (landing.step === 'EVENT') this.openedFor = -1; // 事件格不弹窗：棋盘中央的卡牌由 eventDraw 驱动，点卡即抽
            else if (landing.step === 'DISCARD') ctx.popups.open(new DiscardPopup(id).withDeadline(w.deadline)); // 事件得卡超出上限
            else if (landing.step === 'RESPONSE') {
                // 免租响应（设计稿 04）：持有免租卡时才有这一步
                const owner = st.player(st.prop(landing.tile)?.owner ?? '');
                ctx.popups.open(new RentPopup(landing.tile, owner ? owner.nickname : '对手', rentAt(landing.tile), id).withDeadline(w.deadline));
            }
            else this.openedFor = -1; // 其他步骤由服务端自动推进
        } else if (w.kind === 'TURN' && g.stage === 'JAIL_DECISION') {
            // 设计稿 15 右：出狱判定页；掷骰交回棋盘页播放骰子动画
            this.openedFor = id;
            ctx.popups.open(new JailPopup(id, () => this.startRoll()));
        } else if (w.kind === 'DEBT' && g.debt && g.debt.debtor === this.myId) {
            this.openedFor = id;
            const d = g.debt;
            // 应急抵押（设计稿 05）：首段勾选资产抵押；第二段先问"继续抵押 / 确认破产"（不选默认继续）
            const od = { debtId: d.debtId, windowId: id, deadline: w.deadline };
            const creditor = d.creditor ?? null;
            if (d.segment >= 2 && !d.continued) ctx.popups.open(new DebtSecondPopup(d.amount, creditor, od));
            else ctx.popups.open(new DebtPopup(d.amount, creditor, d.segment >= 2 ? 2 : 1, 0, od));
        } else if (w.kind === 'RESPONSE' && g.cards?.response && g.cards.response.owner === this.myId) {
            // 有人对我的地产用了降级 / 拆楼 / 清地 / 强购，我持有房屋保护 / 拒绝购买
            this.openedFor = id;
            ctx.popups.open(new CardResponsePopup(g.cards.response).withDeadline(w.deadline));
        } else if (w.kind === 'DISCARD') {
            this.openedFor = id;
            ctx.popups.open(new DiscardPopup(id).withDeadline(w.deadline));
        }
    }
}

function me_manual(p: PlayerView): boolean {
    return p.control === 'MANUAL' && p.conn !== 'OFFLINE';
}
