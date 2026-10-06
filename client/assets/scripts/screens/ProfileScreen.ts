/** 页面 1：登录资料（微信登录 / 昵称输入与检查 / 内置头像选择）。 */
import { Label } from 'cc';
import { checkNickname, NICK_MAX } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { EditField } from '../ui/EditField';
import { drawCheck } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setText, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { art, AVATAR_NAMES } from '../ui/Art';

export class ProfileScreen extends Screen {
    readonly id = 'profile' as const;
    readonly title = '登录资料';
    private nick = '';
    private avatarIdx = 0;
    private initialized = false;
    private hint!: Label;
    private enter!: Button;
    private field!: EditField;

    protected build(): void {
        const st = ctx.store;
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        if (!this.initialized) {
            this.nick = st.profile.nickname;
            this.avatarIdx = st.profile.avatar;
            this.initialized = true;
        }
        // 木牌标题
        const sign = mk(this.root, 'Logo', 110, 130, 500, 170);
        const g = gfx(sign);
        fillRR(g, 0, 10, 500, 160, 30, '#6B4423');
        fillRR(g, 0, 0, 500, 160, 30, '#A4723C');
        strokeRR(g, 0, 0, 500, 160, 30, '#5A3A1A', 5);
        if (art(sign, 'title_wood', 0, 0, 500, 160, 'stretch')) g.clear();
        text(sign, '好友桌游', 0, 6, 500, 110, 78, '#FFF3C4', { bold: true });
        text(sign, '和朋友一起，开启一局新旅程', 0, 108, 500, 44, Theme.font.sm, '#FFE9A8');

        const card = mk(this.root, 'ProfilePanel', 36, 612, 648, 646);
        art(card, 'info_asset_panel', 0, 0, 648, 646, 'panel');
        text(card, '登录资料', 28, 12, 592, 56, 38, '#613C24', { bold: true });

        // 微信登录
        const logged = st.profile.loggedIn;
        new Button(this.root, logged ? '已登录 · 微信用户 ✓' : '微信登录', 36, 500, 648, 92, logged ? 'ghost' : 'success', () => {
            if (st.profile.loggedIn) return;
            st.setProfile({ loggedIn: true });
            if (!this.nick && !st.online) this.nick = '微信用户';
            Toast.show(st.online ? '测试登录：填写昵称后保存即可联机（暂未接入微信）' : '微信登录成功（演示，未连接微信）');
            this.rebuild();
        }, Theme.font.lg);

        // 头像
        text(card, '内置头像', 28, 222, 400, 36, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        for (let i = 0; i < 8; i++) {
            const cx = 28 + (i % 4) * 150;
            const cy = 260 + Math.floor(i / 4) * 128;
            const cell = mk(card, 'AvatarCell', cx, cy, 138, 120);
            const sel = i === this.avatarIdx;
            fillRR(gfx(cell), 0, 0, 138, 120, 18, sel ? Theme.c.white : '#F2E9D6');
            avatar(cell, 24, 4, 90, i, AVATAR_NAMES[i]);
            text(cell, AVATAR_NAMES[i], 0, 94, 138, 26, Theme.font.xs, sel ? Theme.c.blueDark : Theme.c.ink, { bold: sel });
            if (sel) {
                const gg = gfx(mk(cell, 'Sel', 0, 0, 138, 120));
                strokeRR(gg, 0, 0, 138, 120, 18, Theme.c.blue, 4);
                fillCircle(gg, 115, 88, 15, Theme.c.blue);
                drawCheck(gg, 115, 88, 30, Theme.c.white, 4);
            }
            onTap(cell, () => {
                this.avatarIdx = i;
                this.rebuild();
            }, false);
        }

        // 昵称
        text(card, '昵称', 28, 76, 200, 36, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        this.field = new EditField(card, 28, 116, 592, 60, '输入昵称（最多 ' + NICK_MAX + ' 字）', 20, (s) => {
            this.nick = s;
            this.checkNow();
        });
        if (this.nick) this.field.value = this.nick;
        this.hint = text(card, '', 28, 180, 592, 36, 21, Theme.c.inkSoft, { align: 'l' });


        this.enter = primaryButton(card, '保存资料', 28, 520, 592, 96, () => {
            const r = checkNickname(this.nick);
            if (!st.profile.loggedIn) return Toast.show('请先微信登录');
            if (!r.ok) return Toast.show(r.reason);
            st.setProfile({ nickname: this.nick.trim(), avatar: this.avatarIdx });
            if (!st.online) return ctx.screens.go('lobby');
            // 联机：测试身份登录并连接服务器
            this.enter.setEnabled(false, '正在连接服务器…');
            st.online.login(this.nick.trim()).then(() => ctx.screens.go('lobby'), (e: { code?: string }) => {
                Toast.show(e && e.code === 'INVALID_NICKNAME' ? '昵称不合法，请换一个' : '连接服务器失败，请稍后重试');
                this.checkNow();
            });
        });
        this.checkNow();
    }

    private checkNow(): void {
        const r = checkNickname(this.nick);
        const ok = r.ok && ctx.store.profile.loggedIn;
        setText(this.hint, this.nick.trim() ? (r.ok ? '✓ ' : '× ') + r.reason : '昵称需检查，检查通过后可继续',
            !this.nick.trim() || r.ok ? Theme.c.greenDark : Theme.c.red);
        this.enter.setEnabled(ok, ctx.store.profile.loggedIn ? r.reason : '请先微信登录');
    }

    /** store 变化时不整页重建，避免打断输入。 */
    refresh(): void {
        this.checkNow();
    }
}
