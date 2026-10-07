/**
 * 页面 5：虎口拔牙。牙齿数 = 参与人数 × 2；每颗牙独立可点，已按压牙变暗；危险牙从外观上无法识别
 * （演示里危险牙由客户端随机，真实由服务端固定且结束前不公开）。每次选择 10 秒（截止时间驱动），
 * 超时由系统随机代选；触发闭合者输，其余玩家各获 500，输家不扣钱。
 */
import { Label, Node } from 'cc';
import { Countdown } from '../core/Clock';
import { ME } from '../core/MockStore';
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

    onShow(): void {
        this.inited = false;
    }

    private init(): void {
        const st = ctx.store;
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

    protected build(): void {
        if (!this.inited) this.init();
        if (this.phase === 'closed' && Date.now() >= this.resultAt) {
            this.buildResult();
            return;
        }
        this.backdrop('sky');
        art(this.root, 'card_detail_background', 0, 0, Theme.W, Theme.H, 'stretch');
        art(this.root, 'scene_game_center', 50, 110, 620, 260);
        new IconButton(this.root, 80, 24, 60, '', () => ctx.screens.back('board'), Theme.c.ivory, Theme.c.ink, (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.ink));
        const hd = mk(this.root, 'Header', 150, 24, 360, 60);
        text(hd, '虎口拔牙', 0, 0, 360, 60, Theme.font.lg, Theme.c.ink, { bold: true });

        // 参与者
        this.participants.forEach((p, i) => {
            const cx = 20 + (i % 4) * 172;
            const cy = 270 + Math.floor(i / 4) * 88;
            const cur = i === this.turn && this.phase === 'picking';
            const cell = mk(this.root, 'Pl' + i, cx, cy, 160, 116);
            avatar(cell, 46, 0, 66, p.avatar, p.nickname, { ring: cur ? Theme.c.yellow : undefined });
            text(cell, p.nickname, 0, 66, 160, 26, Theme.font.sm, Theme.c.ink, { bold: true });
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
        text(info, String(MINIGAME_REWARD), 362, 58, 120, 40, Theme.font.lg, Theme.c.yellowDark, { bold: true, align: 'l' });

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

    private drawTeeth(wrap: Node): void {
        const n = this.pressed.length;
        const top = Math.ceil(n / 2);
        const bottom = n - top;
        this.teethNodes = [];
        const place = (idx: number, count: number, isTop: boolean, k: number) => {
            const x0 = 130;
            const x1 = 590;
            const gap = (x1 - x0) / Math.max(1, count - 1 || 1);
            const cx = count === 1 ? 360 : x0 + k * gap;
            const tw = Math.min(54, gap - 8 || 54);
            const th = 66;
            const pressed = this.pressed[idx];
            const dx = (cx - 360) / 230;
            const baseY = isTop ? 196 + dx * dx * 18 : 430 - th - dx * dx * 18;
            const y = pressed ? baseY + (isTop ? -14 : 14) : baseY;
            const t = mk(wrap, 'Tooth' + idx, cx - tw / 2, y, tw, th);
            const g = gfx(t);
            if (!art(t, pressed ? 'tooth_pressed' : 'tooth_normal', 0, 0, tw, th, 'stretch')) {
                fillRR(g, 0, 4, tw, th, 14, '#00000030');
                fillRR(g, 0, 0, tw, th, 14, pressed ? '#9AA3AD' : '#FFFFFF');
            }
            this.teethNodes[idx] = t;
            onTap(t, () => this.pick(idx, ME), false);
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
        else setText(this.turnLabel, '轮到' + (cur.playerId === ME ? '我' : cur.nickname), Theme.c.ink);
        this.ring?.update(this.cd);
    }

    tick(_dt: number): void {
        if (!this.inited) return;
        this.tickTexts();
        const now = Date.now();
        if (this.phase === 'closed') {
            if (now >= this.endAt) this.backToBoard();
            else if (now >= this.resultAt && !this.showingResult) this.rebuild();
            return;
        }
        const cur = this.participants[this.turn];
        // 他人回合：模拟其选择；超时（含我自己）由系统随机代选
        if (cur.playerId !== ME && this.nextAutoAt && now >= this.nextAutoAt && ctx.store.autoplay !== false) {
            this.nextAutoAt = 0;
            this.pick(this.randomFree(), cur.playerId);
            return;
        }
        if (cur.playerId !== ME && !this.nextAutoAt) this.nextAutoAt = now + 1500;
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
            text(pnl, '+' + MINIGAME_REWARD, 430, 150, 180, 90, 52, Theme.c.payRed, { bold: true, align: 'l' });
            const mine = mk(this.root, 'Mine', 60, 978, 600, 160);
            if (!art(mine, 'result_reward_panel', 0, 0, 600, 160, 'stretch')) fillRR(gfx(mine), 0, 0, 600, 160, 26, '#DCEBFF');
            const me = st.me();
            if (me) avatar(mine, 40, 24, 112, me.avatar, me.nickname, { ring: Theme.c.white });
            text(mine, '你获得的奖励', 190, 18, 340, 50, 30, Theme.c.navy, { bold: true, align: 'l' });
            drawCoin(gfx(mk(mine, 'Coin', 190, 76, 56, 56)), 28, 28, 28);
            text(mine, '+' + MINIGAME_REWARD, 258, 70, 260, 70, 56, Theme.c.payRed, { bold: true, align: 'l' });
        } else {
            const pnl = mk(this.root, 'Panel', 40, 712, 640, 426);
            if (!art(pnl, 'result_zero_panel', 0, 0, 640, 426, 'stretch')) fillRR(gfx(pnl), 0, 0, 640, 426, 28, '#FFFBF3');
            drawCoin(gfx(mk(pnl, 'Coin', 236, 30, 60, 60)), 30, 30, 30);
            text(pnl, '0', 306, 22, 100, 76, 64, Theme.c.payRed, { bold: true, align: 'l' });
            text(pnl, '其他参与玩家各 +' + MINIGAME_REWARD, 0, 100, 640, 50, 30, Theme.c.navy, { bold: true });
            this.participants.slice(0, 8).forEach((p, i) => {
                const cx = 36 + (i % 4) * 146;
                const cy = 156 + Math.floor(i / 4) * 130;
                const lost = !!loser && p.playerId === loser.playerId;
                const cell = mk(pnl, 'P' + i, cx, cy, 130, 124);
                if (lost) fillRR(gfx(cell), 0, 0, 130, 124, 16, '#FFF1C9');
                avatar(cell, 27, 4, 76, p.avatar, p.nickname, { dim: lost });
                text(cell, p.nickname, 0, 80, 130, 22, 20, Theme.c.navy, { bold: true });
                text(cell, lost ? '0' : '+' + MINIGAME_REWARD, 0, 100, 130, 24, 20, lost ? Theme.c.noteGray : Theme.c.payRed, { bold: true });
            });
        }
        const back = primaryButton(this.root, '返回棋盘', 140, 1158, 440, 96, () => this.backToBoard(), 36);
        art(back.node, 'icon_house', 70, 16, 60, 60);
    }

    refresh(): void {
        // 对局数据变化不重置小游戏进度
        this.rebuild();
    }
}
