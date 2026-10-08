/** 页面 3：好友房间（房号分享、8 个座位、房主设置、语音条/聊天片段、准备/开局）。 */
import { Node } from 'cc';
import { copyText, shareRoom } from '../net/Wx';
import { BANKRUPTCY_CAP_MINUTES, boardSizeOf, INITIAL_CASH_OPTIONS, MAX_HAND, maxPlayers, ROLL_SECONDS_OPTIONS, TIME_LIMIT_OPTIONS } from '../core/Rules';
import { EndMode, Member } from '../core/Models';
import { Theme } from '../core/Theme';
import { ChatPopup } from '../popups/ChatPopup';
import { ConfirmPopup } from '../popups/ConfirmPopup';
import { Button, IconButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawChat, drawCopy, drawMic } from '../ui/Icons';
import { fillCircle, fillRR, gfx, line, mk, onTap, strokeCircle, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, chip, roundedPanel, Segmented } from '../ui/Widgets';
import { art, informationCharacterKey } from '../ui/Art';

export class RoomScreen extends Screen {
    readonly id = 'room' as const;
    readonly title = '好友房间';

    protected build(): void {
        const st = ctx.store;
        const s = st.session;
        const isHost = s.hostId === st.myId;
        const cap = maxPlayers(boardSizeOf(s.settings.boardId));
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        this.header('好友房间', () => {
            // 联机：返回即离开房间（最后一人离开时房间关闭）
            if (st.online && s.roomId) void st.online.leave().then((r) => r.ok && ctx.screens.go('lobby'));
            else ctx.screens.go('lobby');
        });
        if (st.online && !s.members.some((m) => m.playerId === st.myId)) {
            text(this.root, '你不在任何房间里', 0, 560, Theme.W, 80, Theme.font.lg, Theme.c.inkSoft);
            primaryButton(this.root, '返回大厅', 140, 680, 440, 100, () => ctx.screens.go('lobby'), Theme.font.lg);
            return;
        }

        // 房间号卡
        const code = roundedPanel(this.root, 24, 96, 672, 130);
        text(code, '房间号', 28, 12, 200, 34, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        text(code, s.roomId, 24, 40, 330, 84, 76, Theme.c.ink, { bold: true, align: 'l' });
        new IconButton(code, 360, 40, 64, '', () => {
            if (!st.online) return Toast.show('房间号已复制：' + s.roomId);
            void copyText(s.roomId).then((ok) => Toast.show(ok ? '房间号已复制：' + s.roomId : '复制失败，请手动记下房间号'));
        }, Theme.c.ivoryDark, Theme.c.ink, (g, z) => drawCopy(g, z / 2, z / 2, z * 0.7, Theme.c.ink));
        // 微信里拉起转发（好友点卡片直接进本房间）；浏览器里复制带 ?room= 的链接
        secondaryButton(code, '分享邀请', 450, 28, 198, 76, () => {
            if (!st.online) return Toast.show('已调起微信分享（演示）');
            void shareRoom(s.roomId, st.profile.nickname).then((r) => {
                if (r === 'copied') Toast.show('邀请链接已复制，发给好友即可加入');
                else if (r === 'failed') Toast.show('请把房间号 ' + s.roomId + ' 告诉好友');
            });
        }, Theme.font.md);

        // 座位 2×4
        const seats = roundedPanel(this.root, 24, 238, 672, 372);
        for (let i = 0; i < 8; i++) {
            const cx = 12 + (i % 4) * 162;
            const cy = 14 + Math.floor(i / 4) * 176;
            const cell = mk(seats, 'Seat' + i, cx, cy, 150, 164);
            const m: Member | undefined = s.members[i];
            if (m) this.drawMember(cell, m, isHost);
            else this.drawEmpty(cell, i >= cap, isHost);
        }

        // 设置
        const set = roundedPanel(this.root, 24, 622, 672, 408);
        const rows = 6;
        const rowH = 66;
        const lockHint = '仅房主可修改设置';
        const mk1 = (label: string, i: number) => text(set, label, 24, 8 + i * rowH, 150, rowH - 8, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        const segX = 180;
        const segW = 672 - segX - 20;
        const n = s.members.length;
        mk1('地图', 0);
        const map = new Segmented(set, segX, 12, segW, 50, [
            { label: '30格', value: 'classic-30', disabled: n > 4, note: '30 格最多 4 人（当前 ' + n + ' 人）' },
            { label: '50格', value: 'classic-50' },
        ], s.settings.boardId, (v) => st.setSetting({ boardId: v as 'classic-30' | 'classic-50' }));
        if (n > 4) text(set, n + ' 人时 30 格不可选', segX, 62, segW, 18, Theme.font.xs, Theme.c.red, { align: 'l' });
        mk1('初始资金', 1);
        const cash = new Segmented(set, segX, 12 + rowH, segW, 50, INITIAL_CASH_OPTIONS.map((v) => ({ label: String(v), value: v })), s.settings.initialCash,
            (v) => st.setSetting({ initialCash: v as number }));
        mk1('结束模式', 2);
        const mode = new Segmented(set, segX, 12 + rowH * 2, segW, 50, [{ label: '限时', value: 'TIME_LIMIT' }, { label: '破产', value: 'BANKRUPTCY' }],
            s.settings.endMode, (v) => st.setSetting({ endMode: v as EndMode }));
        mk1('游戏时长', 3);
        const limited = s.settings.endMode === 'TIME_LIMIT';
        const dur = new Segmented(set, segX, 12 + rowH * 3, segW, 50,
            TIME_LIMIT_OPTIONS.map((v) => ({ label: v + '分钟', value: v, disabled: !limited, note: '破产模式不限时长（最长 ' + BANKRUPTCY_CAP_MINUTES + ' 分钟）' })),
            s.settings.timeLimitMinutes, (v) => st.setSetting({ timeLimitMinutes: v as number }));
        mk1('投骰时间', 4);
        const roll = new Segmented(set, segX, 12 + rowH * 4, segW, 50, ROLL_SECONDS_OPTIONS.map((v) => ({ label: v + '秒', value: v })),
            s.settings.rollSeconds, (v) => st.setSetting({ rollSeconds: v as number }), 8, Theme.font.sm);
        // 开局道具（用户 2026-10-07）：默认不发；选 1～道具上限（后台可配，默认 6）张则开局每人随机发这么多张（事件仍可获得道具）
        mk1('开局道具', 5);
        const cards = new Segmented(set, segX, 12 + rowH * 5, segW, 50,
            [{ label: '无', value: 0 }, ...Array.from({ length: MAX_HAND }, (_, i) => ({ label: String(i + 1), value: i + 1 }))],
            s.settings.initialCards, (v) => st.setSetting({ initialCards: v as number }), 6, Theme.font.sm);
        for (const sg of [map, cash, mode, dur, roll, cards]) {
            sg.locked = !isHost;
            sg.lockHint = lockHint;
        }
        void rows;

        // 语音条 + 聊天片段
        const voice = roundedPanel(this.root, 24, 1042, 672, 76, { r: 38 });
        new IconButton(voice, 10, 8, 60, '', () => Toast.show('麦克风已开启（演示，未接入语音服务）'), Theme.c.blueSoft, Theme.c.blueDark,
            (g, z) => drawMic(g, z / 2, z / 2, z * 0.6, Theme.c.blueDark));
        s.members.slice(0, 8).forEach((m, i) => {
            const ax = 84 + i * 46;
            avatar(voice, ax, 12, 40, m.avatar, m.nickname, { ring: m.speaking ? Theme.c.green : undefined });
            const bars = gfx(mk(voice, 'Lvl', ax + 10, 56, 24, 14));
            for (let b = 0; b < 3; b++) fillRR(bars, b * 8, m.speaking ? 2 - b * 2 : 8, 5, m.speaking ? 12 + b * 2 : 5, 2, m.speaking ? Theme.c.green : Theme.c.grayDark);
        });
        const last = st.roomChat[st.roomChat.length - 1];
        const bub = mk(voice, 'Bubble', 460, 10, 200, 56);
        fillRR(gfx(bub), 0, 0, 200, 56, 28, Theme.c.ivoryDark);
        text(bub, last ? last.text : '说点什么…', 14, 0, 172, 56, Theme.font.xs, Theme.c.ink, { align: 'l' });
        onTap(bub, () => ctx.popups.open(new ChatPopup()));
        void drawChat;

        // 底部按钮
        const me = s.members.find((m) => m.playerId === st.myId) as Member;
        const ready = st.allReady();
        let main: Button;
        if (isHost) {
            main = primaryButton(this.root, ready ? '开始游戏' : '等待全员准备', 24, 1136, 440, 100, () => {
                if (st.online) return void st.online.start(); // 开局后服务端推送对局视图，自动进入棋盘
                st.patchScenario({ players: s.members.length });
                Toast.show('对局开始（演示）');
                ctx.screens.go('board');
            }, Theme.font.lg);
            main.setEnabled(ready, s.members.length < 2 ? '至少 2 人才能开局' : '需全员准备后才能开局');
        } else {
            main = primaryButton(this.root, me.ready ? '已准备，等待开局' : '准备', 24, 1136, 440, 100, () => st.setReady(st.myId, true), Theme.font.lg);
            main.setEnabled(!me.ready, '已准备');
        }
        const sec = new Button(this.root, me.ready ? '取消准备' : (isHost ? '准备' : '未准备'), 480, 1136, 216, 100, 'ghost',
            () => st.setReady(st.myId, !me.ready), Theme.font.md);
        sec.setEnabled(isHost || me.ready, '请先点击左侧"准备"');
    }

    private drawMember(cell: Node, m: Member, isHost: boolean): void {
        const st = ctx.store;
        const s = st.session;
        const isMe = m.playerId === st.myId;
        // 设计稿 06/08：圆角方形人物卡（头像底色的浅色）、房主橙色角标在右上、名字、准备状态胶囊
        const card = mk(cell, 'Card', 23, 2, 104, 104);
        const cg = gfx(card);
        fillRR(cg, 0, 3, 104, 104, 20, Theme.c.shadow);
        fillRR(cg, 0, 0, 104, 104, 20, Theme.avatarColors[((m.avatar % 8) + 8) % 8] + '55');
        strokeRR(cg, 1, 1, 102, 102, 20, '#FFFFFF', 3);
        // 半身人物图（透明底）直接站在色块上；素材未到时退回圆形头像
        if (!art(card, informationCharacterKey(m.avatar), 6, 4, 92, 100)) avatar(card, 10, 10, 84, m.avatar, m.nickname);
        if (m.playerId === s.hostId) {
            const tag = mk(cell, 'Host', 86, -8, 56, 30);
            fillRR(gfx(tag), 0, 0, 56, 30, 12, Theme.c.orange);
            text(tag, '房主', 0, 0, 56, 30, Theme.font.xs, Theme.c.white, { bold: true });
        }
        text(cell, m.nickname + (isMe ? '(我)' : ''), 0, 108, 150, 30, Theme.font.sm, Theme.c.ink, { bold: true });
        const rc = m.ready ? chip(cell, 22, 138, '✓ 已准备', Theme.c.greenSoft, Theme.c.greenDark, Theme.font.xs, 26)
            : chip(cell, 22, 138, '◷ 未准备', '#E9EDF1', Theme.c.inkSoft, Theme.font.xs, 26);
        // 胶囊在格内居中
        rc.node.setPosition(rc.node.position.x + (106 - rc.w) / 2, rc.node.position.y, 0);
        // 演示：点别人的准备标签可切换其准备状态（真实环境由对方自己操作）
        if (!isMe && !st.online) onTap(rc.node, () => {
            Toast.show('演示：切换 ' + m.nickname + ' 的准备状态');
            st.setReady(m.playerId, !m.ready);
        });
        if (isHost && !isMe) {
            const x = mk(cell, 'Kick', 4, -6, 40, 40);
            const g = gfx(x);
            fillCircle(g, 20, 20, 17, Theme.c.red);
            line(g, 13, 13, 27, 27, Theme.c.white, 4);
            line(g, 27, 13, 13, 27, Theme.c.white, 4);
            onTap(x, () => ctx.popups.open(new ConfirmPopup({
                title: '移除成员', message: '确定将「' + m.nickname + '」移出房间吗？开局后将无法再移除玩家。', confirmText: '移除', danger: true,
                onConfirm: () => {
                    st.removeMember(m.playerId);
                    Toast.show('已移除 ' + m.nickname);
                },
            }, 'kick')));
        }
    }

    private drawEmpty(cell: Node, locked: boolean, isHost: boolean): void {
        const g = gfx(cell);
        // 联机测试：房主点空位加一个机器人（服务端托管代打），一个人也能开局
        const bot = !locked && isHost && !!ctx.store.online;
        strokeCircle(g, 75, 42, 40, locked ? Theme.c.gray : bot ? Theme.c.blue : Theme.c.ivoryLine, 3);
        text(cell, locked ? '×' : '+', 33, 2, 84, 80, Theme.font.xl, bot ? Theme.c.blue : Theme.c.inkFaint);
        text(cell, locked ? '30格不可用' : bot ? '加机器人' : '空位', 0, 88, 150, 32, Theme.font.sm, bot ? Theme.c.blue : Theme.c.inkFaint, { bold: bot });
        if (bot) onTap(cell, () => void ctx.store.online?.addBot());
    }
}
