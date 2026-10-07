/** 页面 2：大厅（创建房间 / 房号加入 / 返回对局 / 我的战绩）。 */
import { UITransform } from 'cc';
import { Theme } from '../core/Theme';
import { HistoryPopup } from '../popups/HistoryPopup';
import { JoinRoomPopup } from '../popups/JoinRoomPopup';
import { IconButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawTrophy } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, roundedPanel } from '../ui/Widgets';
import { art } from '../ui/Art';

export class LobbyScreen extends Screen {
    readonly id = 'lobby' as const;
    readonly title = '大厅';

    protected build(): void {
        const st = ctx.store;
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');

        // 顶部：头像 + 昵称 + 在线信号 + 设置/音量
        const me = mk(this.root, 'MeBar', 80, 18, 330, 72);
        const g = gfx(me);
        fillRR(g, 0, 3, 330, 68, 34, Theme.c.shadow);
        fillRR(g, 0, 0, 330, 68, 34, Theme.c.ivory);
        avatar(me, 4, 4, 60, st.profile.avatar, st.profile.nickname || '我');
        text(me, st.profile.nickname || '微信用户', 76, 0, 190, 68, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        for (let i = 0; i < 3; i++) fillRR(gfx(mk(me, 'Sig', 280 + i * 11, 40 - i * 8, 7, 12 + i * 8)), 0, 0, 7, 12 + i * 8, 2, Theme.c.green);
        // 设计稿 08：昵称胶囊右侧依次是音量、设置（"…"与圆点由微信胶囊绘制）
        new IconButton(this.root, 424, 22, 64, '♪', () => Toast.show('音量设置（演示）'));
        const settings = new IconButton(this.root, 500, 22, 64, '', () => Toast.show('房间内可调整地图、资金与回合设置'));
        art(settings.node, 'icon_settings', 10, 10, 44, 44);

        // 设计稿 08：彩色木牌品牌标志（与登录资料页同一素材）+ 四人围坐桌游插画
        if (!art(this.root, 'brand_logo', 140, 92, 440, 280)) {
            text(this.root, '好友桌游', 0, 160, Theme.W, 120, 88, '#A4723C', { bold: true });
        }
        art(this.root, 'lobby_friends', 50, 360, 620, 304);

        // 主按钮
        const create = primaryButton(this.root, '创建房间', 48, 668, 624, 112, () => {
            if (st.online) {
                void st.online.createRoom().then((r) => {
                    if (r.ok) ctx.screens.push('room');
                });
                return;
            }
            st.patchScenario({ host: true });
            ctx.screens.push('room');
        }, Theme.font.xl);
        art(create.node, 'icon_house', 34, 24, 64, 64);
        text(create.node, '›', 544, 0, 50, 106, Theme.font.xl, Theme.c.yellowText);
        const join = secondaryButton(this.root, '房间号加入', 48, 806, 624, 112, () => ctx.popups.open(new JoinRoomPopup()), Theme.font.xl);
        // 设计稿 08：白色圆角小牌上的蓝色"123"
        const badge = mk(join.node, 'Badge123', 30, 26, 80, 54);
        fillRR(gfx(badge), 0, 0, 80, 54, 12, Theme.c.white);
        text(badge, '123', 0, 0, 80, 54, Theme.font.md, Theme.c.blueDark, { bold: true });
        text(join.node, '›', 544, 0, 50, 106, Theme.font.xl, Theme.c.white);

        // 返回对局卡
        const inGame = st.session.status === 'PLAYING';
        const card = roundedPanel(this.root, 48, 948, 624, 108);
        if (inGame) {
            fillRR(gfx(mk(card, 'Ic', 18, 18, 72, 72)), 0, 0, 72, 72, 14, Theme.c.greenSoft);
            this.drawTown(mk(card, 'MiniTown', 18, 18, 72, 72));
            text(card, '返回对局', 110, 12, 300, 48, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
            text(card, '房间 ' + st.session.roomId + (st.isSpectator() ? ' · 观战中' : ' · 进行中'), 110, 58, 360, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
            text(card, '›', 554, 20, 50, 68, Theme.font.xl, Theme.c.inkSoft);
            onTap(card, () => ctx.screens.go(st.isSpectator() ? 'spectator' : 'board'));
        } else if (st.online && st.session.roomId) {
            // 联机：已在房间里（大厅阶段）
            text(card, '返回房间 ' + st.session.roomId, 110, 0, 420, 108, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
            text(card, '›', 554, 20, 50, 68, Theme.font.xl, Theme.c.inkSoft);
            onTap(card, () => ctx.screens.push('room'));
        } else {
            text(card, '当前没有进行中的对局', 0, 0, 624, 108, Theme.font.md, Theme.c.inkSoft);
        }

        // 我的战绩：设计稿 08 为深绿色胶囊、白字、金色奖杯
        const rec = mk(this.root, 'History', 148, 1088, 424, 84);
        const recG = gfx(rec);
        fillRR(recG, 0, 4, 424, 80, 40, '#2B5E2C');
        fillRR(recG, 0, 0, 424, 80, 40, '#3F7F3C');
        strokeRR(recG, 2, 2, 420, 76, 38, '#7FB46A', 2);
        const rg = gfx(mk(rec, 'Cup', 70, 16, 48, 48));
        drawTrophy(rg, 24, 24, 44);
        text(rec, '我的战绩', 120, 0, 200, 80, Theme.font.lg, Theme.c.white, { bold: true });
        text(rec, '›', 352, 0, 40, 80, Theme.font.xl, Theme.c.white);
        onTap(rec, () => ctx.popups.open(new HistoryPopup()));
    }

    private drawTown(parent: import('cc').Node): boolean {
        const size = parent.getComponent(UITransform)?.contentSize;
        return !!art(parent, 'board_town', 0, 0, size?.width ?? 500, size?.height ?? 270, 'stretch');
    }

}
