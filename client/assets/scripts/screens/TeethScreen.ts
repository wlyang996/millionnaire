/**
 * 页面 5：虎口拔牙。牙齿数 = 参与人数 × 2；每颗牙独立可点，已按压牙变暗；危险牙从外观上无法识别
 * （演示里危险牙由客户端随机，真实由服务端固定且结束前不公开）。每次选择 10 秒（截止时间驱动），
 * 超时由系统随机代选；触发闭合者输，其余玩家各获 500，输家不扣钱。
 * 联机：参与者、已按下的牙、当前选牙者与倒计时都取服务端视图（game.minigame 与对应窗口）；轮到我时点牙发送 PickTooth，
 * 代选由服务端完成；结束后按 MinigameEnded（store.toothResult）显示合嘴与结果页，再回到棋盘。联机时不显示返回按钮。
 */
import { Label, Node } from 'cc';
import { Countdown } from '../core/Clock';
import { PlayerView } from '../core/Models';
import { MINIGAME_REWARD, SECONDS, toothCount } from '../core/Rules';
import { Theme, textWidth } from '../core/Theme';
import { IconButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawBack, drawChat, drawCoin, drawMic } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setText, text } from '../ui/Kit';
import { ChatPopup } from '../popups/ChatPopup';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, CountdownBadge } from '../ui/Widgets';
import { art } from '../ui/Art';

type Phase = 'picking' | 'closed';

export class TeethScreen extends Screen {
    readonly id = 'teeth' as const;
    readonly title = '虎口拔牙';
    private participants: PlayerView[] = [];
    private pressed: boolean[] = [];
    private danger = 0;
    private turn = 0;
    private phase: Phase = 'picking';
    private cd = new Countdown(ctx.clock);
    private ring: CountdownBadge | null = null;
    private turnLabel: Label | null = null;
    private nextAutoAt = 0;
    private endAt = 0;
    private inited = false;
    private teethNodes: Node[] = [];
    private loser: PlayerView | null = null;
    /** 闭合后先看鳄鱼合嘴，到此时刻切到结算页 */
    private resultAt = 0;
    /** 联机：本页对应的小游戏编号、本局奖励、已同步的窗口、选牙请求进行中 */
    private minigameId = 0;
    private reward = MINIGAME_REWARD;
    private syncedWindow = 0;
    private sending = false;

    onShow(): void {
        this.inited = false;
    }

    private get myId(): string {
        return ctx.store.myId;
    }

    private init(): void {
        const st = ctx.store;
        if (st.online) {
            this.initOnline();
            return;
        }
        this.participants = st.game.players.filter((p) => p.life === 'ALIVE');
        // 从触发者（当前回合玩家）起按顺序轮流
        const start = Math.max(0, this.participants.findIndex((p) => p.playerId === st.game.currentPlayer));
        this.participants = this.participants.slice(start).concat(this.participants.slice(0, start));
        const n = toothCount(this.participants.length);
        this.pressed = new Array<boolean>(n).fill(false);
        this.danger = Math.floor(Math.random() * n);
        this.turn = 0;
        this.phase = 'picking';
        this.loser = null;
        this.cd.start(SECONDS.tooth);
        this.nextAutoAt = 0;
        this.inited = true;
    }

    // ---------- 联机 ----------
    private initOnline(): void {
        const st = ctx.store;
        const m = st.game?.minigame;
        const r = st.toothResult;
        this.phase = 'picking';
        this.loser = null;
        this.syncedWindow = 0;
        this.sending = false;
        if (m) {
            this.minigameId = m.minigameId;
            this.participants = this.players(m.participants);
            this.pressed = new Array<boolean>(m.teeth).fill(false);
            this.syncOnline();
        } else if (r) {
            this.minigameId = r.minigameId;
            this.participants = this.players(r.participants);
            this.pressed = new Array<boolean>(r.participants.length * 2).fill(false);
            this.finishOnline();
        } else {
            this.participants = [];
            this.pressed = [];
        }
        this.inited = true;
    }

    private players(ids: string[]): PlayerView[] {
        const st = ctx.store;
        return ids.map((id) => st.player(id)).filter((p): p is PlayerView => !!p);
    }

    /** 按服务端视图同步按下的牙、当前选牙者与倒计时；小游戏已结束则转入合嘴。 */
    private syncOnline(): void {
        const st = ctx.store;
        const m = st.game?.minigame;
        if (!m || m.minigameId !== this.minigameId) {
            const r = st.toothResult;
            if (this.phase === 'picking' && r && r.minigameId === this.minigameId) this.finishOnline();
            return;
        }
        m.picks.forEach((t) => (this.pressed[t] = true));
        this.turn = Math.max(0, this.participants.findIndex((p) => p.playerId === m.picker));
        if (m.windowId !== this.syncedWindow) {
            const w = st.game.windows.find((x) => x.windowId === m.windowId);
            if (w) {
                this.syncedWindow = m.windowId;
                this.cd.setDeadline(w.deadline, Math.max(1, Math.round((w.deadline - w.opensAt) / 1000)));
            }
        }
    }

    private finishOnline(): void {
        const r = ctx.store.toothResult;
        if (!r || r.minigameId !== this.minigameId) return;
        r.seen = true; // 结果只展示一次，之后不再把棋盘拉回结果页
        r.picks.forEach((t) => (this.pressed[t] = true));
        this.phase = 'closed';
        this.reward = r.reward;
        this.loser = ctx.store.player(r.loser) ?? null;
        this.resultAt = Date.now() + 1200;
        this.endAt = this.resultAt + 8000;
    }

    private pickOnline(idx: number): void {
        const st = ctx.store;
        const m = st.game?.minigame;
        if (!m || this.phase !== 'picking' || this.sending) return;
        if (m.picker !== this.myId) return Toast.show('还没轮到你');
        if (this.pressed[idx]) return Toast.show('这颗牙已经被按过了');
        this.sending = true;
        void st.online!.act('PickTooth', { windowId: m.windowId, tooth: idx }).then(() => {
            this.sending = false;
        });
    }

    protected build(): void {
        if (!this.inited) this.init();
        if (this.phase === 'closed' && Date.now() >= this.resultAt) {
            this.buildResult();
            return;
        }
        this.backdrop('sky');
        art(this.root, 'card_detail_background', 0, 0, Theme.W, Theme.H, 'stretch');
        art(this.root, 'scene_game_center', 160, 96, 400, 200);
        if (!ctx.store.online) {
            new IconButton(this.root, 80, 24, 60, '', () => ctx.screens.back('board'), Theme.c.ivory, Theme.c.ink, (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.ink));
        }
        const hd = mk(this.root, 'Header', 150, 24, 360, 60);
        text(hd, '虎口拔牙', 0, 0, 360, 60, Theme.font.lg, Theme.c.ink, { bold: true });

        // 参与者：摊位图下方一排（最多 8 人，每人 82 宽），当前选牙者黄色圈；不与摊位图、彼此重叠
        const n = this.participants.length;
        const x0 = (Theme.W - n * 82) / 2;
        const strip = mk(this.root, 'PlayersStrip', x0 - 12, 298, n * 82 + 24, 144);
        fillRR(gfx(strip), 0, 0, n * 82 + 24, 144, 24, '#FFFFFFB8');
        this.participants.forEach((p, i) => {
            const cur = i === this.turn && this.phase === 'picking';
            const cell = mk(this.root, 'Pl' + i, x0 + i * 82, 304, 82, 132);
            if (cur) fillRR(gfx(cell), 2, 0, 78, 130, 16, '#FFF1C9CC');
            avatar(cell, 9, 6, 64, p.avatar, p.nickname, { ring: cur ? Theme.c.yellow : Theme.c.white });
            text(cell, p.playerId === this.myId ? '你' : p.nickname, 0, 74, 82, 26, 20, Theme.c.navy, { bold: true });
            if (cur) text(cell, '选牙中', 0, 100, 82, 24, 16, '#B07A00', { bold: true });
        });

        // 轮到谁 + 倒计时
        const bar = mk(this.root, 'Turn', 120, 452, 480, 76);
        fillRR(gfx(bar), 0, 4, 480, 72, 36, Theme.c.shadow);
        fillRR(gfx(bar), 0, 0, 480, 72, 36, Theme.c.ivory);
        this.turnLabel = text(bar, '', 24, 0, 330, 72, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        this.ring = new CountdownBadge(bar, 480 - 150, 12, 130, 48);

        this.drawCroc();

        // 提示面板
        const info = mk(this.root, 'Info', 100, 1050, 520, 112);
        fillRR(gfx(info), 0, 4, 520, 108, 26, Theme.c.shadow);
        fillRR(gfx(info), 0, 0, 520, 108, 26, Theme.c.ivory);
        text(info, this.phase === 'closed' ? (this.loser?.nickname ?? '') + '触发闭合' : '请选择一颗牙齿', 0, 8, 520, 52, Theme.font.lg, Theme.c.ink, { bold: true });
        text(info, '其余玩家各获得', 120, 58, 190, 40, Theme.font.sm, Theme.c.inkSoft, { align: 'r' });
        drawCoin(gfx(mk(info, 'C', 320, 60, 36, 36)), 18, 18, 15);
        text(info, String(this.reward), 362, 58, 120, 40, Theme.font.lg, Theme.c.yellowDark, { bold: true, align: 'l' });

        // 语音 / 聊天
        new IconButton(this.root, 24, 1190, 72, '', () => Toast.show('麦克风已开启（演示）'), Theme.c.ivory, Theme.c.blueDark, (g, s) => drawMic(g, s / 2, s / 2, s * 0.6, Theme.c.blueDark));
        new IconButton(this.root, 624, 1190, 72, '', () => ctx.popups.open(new ChatPopup()), Theme.c.ivory, Theme.c.blueDark, (g, s) => drawChat(g, s / 2, s / 2, s * 0.62, Theme.c.blueDark));
        this.participants.forEach((p, i) => avatar(this.root, 114 + i * 62, 1194, 54, p.avatar, p.nickname));
        this.tickTexts();
    }

    private drawCroc(): void {
        const wrap = mk(this.root, 'Croc', 0, 530, Theme.W, 510);
        const g = gfx(wrap);
        if (art(wrap, this.phase === 'closed' ? 'croc_closed' : 'croc_open', 40, 0, 640, 510, 'stretch')) {
            if (this.phase === 'picking') this.drawTeeth(wrap);
            return;
        }
        // 头部
        fillRR(g, 40, 30, 640, 520, 240, '#4FAE45');
        fillRR(g, 56, 40, 608, 500, 230, '#63C957');
        // 眼睛
        for (const ex of [170, 550]) {
            fillCircle(g, ex, 40, 62, '#4FAE45');
            fillCircle(g, ex, 36, 50, '#FFFFFF');
            fillCircle(g, ex + (ex < 360 ? 10 : -10), 44, 22, '#2D3B4A');
            fillCircle(g, ex + (ex < 360 ? 16 : -4), 36, 7, '#FFFFFF');
        }
        // 鼻孔
        fillCircle(g, 300, 110, 9, '#3F8E39');
        fillCircle(g, 420, 110, 9, '#3F8E39');
        // 口腔
        fillRR(g, 78, 150, 564, 360, 150, '#7A2432');
        fillRR(g, 130, 270, 460, 210, 100, '#E56B7A');
        fillRR(g, 150, 290, 420, 120, 60, '#F28A98');
        this.drawTeeth(wrap);
    }

    /**
     * 牙齿沿口腔边缘的椭圆弧排布（设计稿 21）：上排挂在上牙龈、尖朝下，下排立在下牙龈、尖朝上；
     * 两侧的牙随弧线倾斜，垂直于牙龈。按下的牙缩回牙龈 14。坐标为鳄鱼图（640×510，左上 40,0）内的口腔边缘实测值。
     */
    private drawTeeth(wrap: Node): void {
        const n = this.pressed.length;
        const top = Math.ceil(n / 2);
        const bottom = n - top;
        this.teethNodes = [];
        const CX = 360;
        const A = 280; // 口腔半宽
        const B = 76; // 弧线拱高
        const TOP_CY = 262; // 上牙龈：中点 y = TOP_CY - B
        const BOT_CY = 352; // 下牙龈：中点 y = BOT_CY + B
        const place = (idx: number, count: number, isTop: boolean, k: number) => {
            const xmax = count <= 1 ? 0 : Math.min(isTop ? 228 : 222, 70 + 28 * count);
            const fmax = Math.asin(xmax / A);
            const f = count <= 1 ? 0 : -fmax + (2 * fmax * k) / (count - 1);
            const x = CX + A * Math.sin(f);
            const y = isTop ? TOP_CY - B * Math.cos(f) : BOT_CY + B * Math.cos(f);
            // 弧线切线方向（y 向下）：上排右侧往下斜、下排右侧往上斜；Cocos 旋转为逆时针
            const tilt = (Math.atan2(B * Math.sin(f), A * Math.cos(f)) * 180) / Math.PI;
            const gap = count <= 1 ? 120 : (2 * xmax) / (count - 1);
            const tw = Math.max(34, Math.min(54, gap - 10));
            const th = Math.round(tw * 1.22);
            const pressed = this.pressed[idx];
            const holder = mk(wrap, 'Tooth' + idx, x, y, 0, 0);
            holder.setRotationFromEuler(0, 0, isTop ? -tilt : tilt);
            // 上排翻转成尖朝下：在翻转空间里与下排同样摆放（牙根压进牙龈 6，按下再缩进 14）
            const axis = mk(holder, 'Axis', 0, 0, 0, 0);
            if (isTop) axis.setScale(1, -1, 1);
            const t = mk(axis, 'Body', -tw / 2, -th + 6 + (pressed ? 14 : 0), tw, th);
            const g = gfx(t);
            if (!art(t, pressed ? 'tooth_pressed' : 'tooth_normal', 0, 0, tw, th, 'stretch')) {
                fillRR(g, 0, 4, tw, th, 14, '#00000030');
                fillRR(g, 0, 0, tw, th, 14, pressed ? '#9AA3AD' : '#FFFFFF');
            }
            this.teethNodes[idx] = holder;
            onTap(t, () => (ctx.store.online ? this.pickOnline(idx) : this.pick(idx, this.myId)), false);
        };
        for (let i = 0; i < top; i++) place(i, top, true, i);
        for (let j = 0; j < bottom; j++) place(top + j, bottom, false, j);
    }

    private pick(idx: number, by: string): void {
        if (this.phase !== 'picking') return;
        const cur = this.participants[this.turn];
        if (cur.playerId !== by) return Toast.show('还没轮到你');
        if (this.pressed[idx]) return Toast.show('这颗牙已经被按过了');
        this.pressed[idx] = true;
        if (idx === this.danger) {
            this.phase = 'closed';
            this.loser = cur;
            for (const p of this.participants) if (p.playerId !== cur.playerId) p.cash += MINIGAME_REWARD;
            // 合嘴 1.2 秒后显示结算页（设计稿 16），停留 8 秒或点"返回棋盘"回到棋盘
            this.resultAt = Date.now() + 1200;
            this.endAt = this.resultAt + 8000;
        } else {
            this.turn = (this.turn + 1) % this.participants.length;
            this.cd.start(SECONDS.tooth);
            this.nextAutoAt = Date.now() + 1300;
        }
        this.rebuild();
    }

    private randomFree(): number {
        const free = this.pressed.map((v, i) => (v ? -1 : i)).filter((i) => i >= 0);
        return free[Math.floor(Math.random() * free.length)];
    }

    private tickTexts(): void {
        if (!this.turnLabel) return;
        const cur = this.participants[this.turn];
        if (this.phase === 'closed') setText(this.turnLabel, (this.loser ? this.loser.nickname : '') + ' 触发闭合！', Theme.c.red);
        else if (cur) setText(this.turnLabel, '轮到' + (cur.playerId === this.myId ? '我' : cur.nickname), Theme.c.ink);
        this.ring?.update(this.cd);
    }

    tick(_dt: number): void {
        if (!this.inited) return;
        this.tickTexts();
        const now = Date.now();
        if (ctx.store.online) {
            // 联机：选牙与代选都由服务端推进，这里只管合嘴 → 结果页 → 回棋盘
            if (this.phase === 'closed') {
                if (now >= this.endAt) this.backToBoard();
                else if (now >= this.resultAt && !this.showingResult) this.rebuild();
            } else if (!ctx.store.game?.minigame && !ctx.store.toothResult) this.backToBoard();
            return;
        }
        if (this.phase === 'closed') {
            if (now >= this.endAt) this.backToBoard();
            else if (now >= this.resultAt && !this.showingResult) this.rebuild();
            return;
        }
        const cur = this.participants[this.turn];
        // 他人回合：模拟其选择；超时（含我自己）由系统随机代选
        if (cur.playerId !== this.myId && this.nextAutoAt && now >= this.nextAutoAt && ctx.store.autoplay !== false) {
            this.nextAutoAt = 0;
            this.pick(this.randomFree(), cur.playerId);
            return;
        }
        if (cur.playerId !== this.myId && !this.nextAutoAt) this.nextAutoAt = now + 1500;
        if (this.cd.consumeExpire()) {
            Toast.show(cur.nickname + ' 超时，系统随机代选');
            this.pick(this.randomFree(), cur.playerId);
        }
    }

    private showingResult = false;

    private backToBoard(): void {
        if (!this.inited) return;
        this.inited = false;
        this.showingResult = false;
        const r = ctx.store.toothResult;
        if (r && r.minigameId === this.minigameId) r.seen = true;
        ctx.store.emit();
        ctx.screens.back('board');
    }

    /**
     * 结算页（设计稿 16 左两张）：木牌标题"虎口拔牙"、气泡"某某触发了闭合！"（我触发时为"你触发了闭合！"）、合嘴鳄鱼、
     * "本次奖励"彩带。胜利者视角：白色面板写触发者本次奖励 0 与"其余 N 名参与者各获得 +500"，蓝色卡写"你获得的奖励 +500"；
     * 落败者视角：大号红色 0、"其他参与玩家各 +500"与全部参与者头像网格（触发者 0、其余 +500）。底部黄色"返回棋盘"。
     */
    private buildResult(): void {
        this.showingResult = true;
        // 结果页没有"轮到谁"条：旧节点已随重建销毁，不能再更新
        this.turnLabel = null;
        this.ring = null;
        const st = ctx.store;
        const loser = this.loser;
        const iLost = !!loser && loser.playerId === st.myId;
        const others = this.participants.length - 1;
        this.backdrop('sky');
        art(this.root, 'card_detail_background', 0, 0, Theme.W, Theme.H, 'stretch');
        const title = mk(this.root, 'Title', 150, 40, 420, 200);
        art(title, 'minigame_title', 0, 0, 420, 200);
        text(title, '虎口拔牙', 40, 42, 340, 110, 64, '#5A3412', { bold: true });
        // 气泡
        const bubbleText = iLost ? '你触发了闭合！' : (loser ? loser.nickname : '') + ' 触发了闭合！';
        const bw = Math.min(560, textWidth(bubbleText, 32) + 80);
        const bubble = mk(this.root, 'Bubble', (Theme.W - bw) / 2, 252, bw, 70);
        fillRR(gfx(bubble), 0, 4, bw, 66, 33, Theme.c.shadow);
        fillRR(gfx(bubble), 0, 0, bw, 66, 33, Theme.c.white);
        text(bubble, bubbleText, 0, 0, bw, 66, 32, iLost ? Theme.c.navy : Theme.c.navy, { bold: true });
        art(this.root, 'croc_closed', 130, 330, 460, 357);
        // 本次奖励彩带
        const rib = mk(this.root, 'Ribbon', 210, 664, 300, 64);
        fillRR(gfx(rib), 0, 0, 300, 64, 32, '#FFD95A');
        text(rib, '本次奖励', 0, 0, 300, 64, 32, '#7A4A00', { bold: true });
        if (!iLost) {
            const pnl = mk(this.root, 'Panel', 40, 712, 640, 250);
            fillRR(gfx(pnl), 0, 4, 640, 246, 28, Theme.c.shadow);
            fillRR(gfx(pnl), 0, 0, 640, 246, 28, '#FFFBF3');
            if (loser) avatar(pnl, 40, 26, 96, loser.avatar, loser.nickname);
            text(pnl, (loser ? loser.nickname : '') + '本次奖励', 156, 26, 300, 96, 32, Theme.c.navy, { align: 'l' });
            text(pnl, '0', 456, 26, 80, 96, 48, Theme.c.payRed, { bold: true, align: 'l' });
            fillRR(gfx(mk(pnl, 'Line', 30, 140, 580, 2)), 0, 0, 580, 2, 1, Theme.c.panelLine);
            text(pnl, '其余 ' + others + ' 名参与者各获得', 40, 150, 380, 90, 30, Theme.c.navy, { bold: true, align: 'l' });
            text(pnl, '+' + this.reward, 430, 150, 180, 90, 52, Theme.c.payRed, { bold: true, align: 'l' });
            // 观战者（未参与）没有"你获得的奖励"卡
            if (this.participants.some((p) => p.playerId === st.myId)) {
                const mine = mk(this.root, 'Mine', 60, 978, 600, 160);
                if (!art(mine, 'result_reward_panel', 0, 0, 600, 160, 'stretch')) fillRR(gfx(mine), 0, 0, 600, 160, 26, '#DCEBFF');
                const me = st.me();
                if (me) avatar(mine, 40, 24, 112, me.avatar, me.nickname, { ring: Theme.c.white });
                text(mine, '你获得的奖励', 190, 18, 340, 50, 30, Theme.c.navy, { bold: true, align: 'l' });
                drawCoin(gfx(mk(mine, 'Coin', 190, 76, 56, 56)), 28, 28, 28);
                text(mine, '+' + this.reward, 258, 70, 260, 70, 56, Theme.c.payRed, { bold: true, align: 'l' });
            }
        } else {
            const pnl = mk(this.root, 'Panel', 40, 712, 640, 426);
            if (!art(pnl, 'result_zero_panel', 0, 0, 640, 426, 'stretch')) fillRR(gfx(pnl), 0, 0, 640, 426, 28, '#FFFBF3');
            drawCoin(gfx(mk(pnl, 'Coin', 236, 30, 60, 60)), 30, 30, 30);
            text(pnl, '0', 306, 22, 100, 76, 64, Theme.c.payRed, { bold: true, align: 'l' });
            text(pnl, '其他参与玩家各 +' + this.reward, 0, 100, 640, 50, 30, Theme.c.navy, { bold: true });
            this.participants.slice(0, 8).forEach((p, i) => {
                const cx = 36 + (i % 4) * 146;
                const cy = 156 + Math.floor(i / 4) * 130;
                const lost = !!loser && p.playerId === loser.playerId;
                const cell = mk(pnl, 'P' + i, cx, cy, 130, 124);
                if (lost) fillRR(gfx(cell), 0, 0, 130, 124, 16, '#FFF1C9');
                avatar(cell, 27, 4, 76, p.avatar, p.nickname, { dim: lost });
                text(cell, p.nickname, 0, 80, 130, 22, 20, Theme.c.navy, { bold: true });
                text(cell, lost ? '0' : '+' + this.reward, 0, 100, 130, 24, 20, lost ? Theme.c.noteGray : Theme.c.payRed, { bold: true });
            });
        }
        const back = primaryButton(this.root, '返回棋盘', 140, 1158, 440, 96, () => this.backToBoard(), 36);
        art(back.node, 'icon_house', 70, 16, 60, 60);
    }

    refresh(): void {
        // 对局数据变化不重置小游戏进度；联机先按服务端视图同步
        if (this.inited && ctx.store.online) this.syncOnline();
        this.rebuild();
    }
}
