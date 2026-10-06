/** 页面 1：登录资料（微信登录 / 昵称输入与检查 / 内置头像选择）。 */
import { Label } from 'cc';
import { checkNickname, NICK_MAX } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, ghostButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { EditField } from '../ui/EditField';
import { drawCheck } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setText, strokeCircle, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, roundedPanel } from '../ui/Widgets';

const RANDOM_NAMES = ['小橘子', '云朵', '阿福', '糖糖', '大雄', '星星', '米粒', '小满'];

export class ProfileScreen extends Screen {
    readonly id = 'profile' as const;
    readonly title = '登录资料';
    private nick = '';
    private avatarIdx = 0;
    private hint!: Label;
    private enter!: Button;
    private field!: EditField;

    protected build(): void {
        const st = ctx.store;
        this.backdrop('sky');
        if (!this.nick) {
            this.nick = st.profile.nickname;
            this.avatarIdx = st.profile.avatar;
        }
        // 木牌标题
        const sign = mk(this.root, 'Logo', 110, 130, 500, 170);
        const g = gfx(sign);
        fillRR(g, 0, 10, 500, 160, 30, '#6B4423');
        fillRR(g, 0, 0, 500, 160, 30, '#A4723C');
        strokeRR(g, 0, 0, 500, 160, 30, '#5A3A1A', 5);
        text(sign, '好友桌游', 0, 6, 500, 110, 78, '#FFF3C4', { bold: true });
        text(sign, '和朋友一起，开启一局新旅程', 0, 108, 500, 44, Theme.font.sm, '#FFE9A8');

        const card = roundedPanel(this.root, 36, 340, 648, 800);
        text(card, '登录与资料', 28, 18, 400, 56, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });

        // 微信登录
        const logged = st.profile.loggedIn;
        new Button(card, logged ? '已登录 · 微信用户 ✓' : '微信一键登录', 28, 90, 592, 92, logged ? 'ghost' : 'success', () => {
            if (st.profile.loggedIn) return;
            st.setProfile({ loggedIn: true });
            if (!this.nick) this.nick = '微信用户';
            Toast.show('微信登录成功（演示，未连接微信）');
            this.rebuild();
        }, Theme.font.lg);

        // 头像
        text(card, '选择头像（内置）', 28, 208, 400, 40, Theme.font.md, Theme.c.inkSoft, { bold: true, align: 'l' });
        for (let i = 0; i < 8; i++) {
            const cx = 28 + (i % 4) * 150 + 10;
            const cy = 260 + Math.floor(i / 4) * 130;
            const cell = mk(card, 'AvatarCell', cx, cy, 110, 110);
            const sel = i === this.avatarIdx;
            avatar(cell, 0, 0, 110, i, i === 0 && this.nick ? this.nick : ['小', '可', '阿', '奶', '凯', '圆', '豆', '毛'][i], { ring: sel ? Theme.c.blue : undefined });
            if (sel) {
                const gg = gfx(mk(cell, 'Sel', 0, 0, 110, 110));
                strokeCircle(gg, 55, 55, 58, Theme.c.blue, 4);
                fillCircle(gg, 95, 15, 15, Theme.c.blue);
                drawCheck(gg, 95, 15, 30, Theme.c.white, 4);
            }
            onTap(cell, () => {
                this.avatarIdx = i;
                this.rebuild();
            }, false);
        }

        // 昵称
        text(card, '昵称', 28, 540, 200, 40, Theme.font.md, Theme.c.inkSoft, { bold: true, align: 'l' });
        this.field = new EditField(card, 28, 584, 440, 76, '输入昵称（最多 ' + NICK_MAX + ' 字）', 20, (s) => {
            this.nick = s;
            this.checkNow();
        });
        if (this.nick) this.field.value = this.nick;
        ghostButton(card, '随机', 484, 584, 136, 76, () => {
            this.nick = RANDOM_NAMES[Math.floor(Math.random() * RANDOM_NAMES.length)];
            this.field.value = this.nick;
            this.checkNow();
        }, Theme.font.md);
        this.hint = text(card, '', 28, 672, 592, 40, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });


        this.enter = primaryButton(this.root, '进入大厅', 36, 1160, 648, 96, () => {
            const r = checkNickname(this.nick);
            if (!st.profile.loggedIn) return Toast.show('请先微信登录');
            if (!r.ok) return Toast.show(r.reason);
            st.setProfile({ nickname: this.nick.trim(), avatar: this.avatarIdx });
            ctx.screens.go('lobby');
        });
        this.checkNow();
    }

    private checkNow(): void {
        const r = checkNickname(this.nick);
        const ok = r.ok && ctx.store.profile.loggedIn;
        setText(this.hint, (r.ok ? '✓ ' : '× ') + r.reason, r.ok ? Theme.c.greenDark : Theme.c.red);
        this.enter.setEnabled(ok, ctx.store.profile.loggedIn ? r.reason : '请先微信登录');
    }

    /** store 变化时不整页重建，避免打断输入。 */
    refresh(): void {
        this.checkNow();
    }
}
